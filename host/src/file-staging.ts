import { lstat, open, readdir, unlink } from 'node:fs/promises';
import { createReadStream } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { randomBytes } from 'node:crypto';
import { privateDirectory, privatePath } from './private-files.ts';
import { HostError } from './errors.ts';
import { UPLOAD_LIMIT } from './phone.ts';

// Old and reloaded hosts in this process may overlap until dispatch settlement.
const claimedPaths = new Set<string>();

/** Plugin-owned transient bytes; never use a display name as a filesystem path. */
export class FileStaging {
  readonly directory: string;
  private ready?: Promise<void>;
  private readonly entries = new Map<string, { size: number; claimed?: boolean; pending?: boolean; removing?: Promise<void> }>();
  private closed = false;
  private sweeping: Promise<void> | undefined;
  private cleanupFailures = 0;
  private lastCleanupFailureAt: number | undefined;
  private readonly sweepTimer: ReturnType<typeof setInterval>;
  // Reloaded instances cannot know old dispatch ownership. Leave orphan bytes a
  // full day (well beyond the ten-minute pick TTL) before reclaiming them.
  private static readonly orphanGraceMs = 24 * 60 * 60_000;
  constructor(statePath: string) {
    this.directory = resolve(dirname(statePath), 'mobile-uploads');
    this.sweepTimer = setInterval(() => { void this.sweep().catch(() => {}); }, 30_000); this.sweepTimer.unref();
  }
  assertOutside(workspaces: readonly { path: string }[]): void {
    for (const workspace of workspaces) {
      const path = relative(resolve(workspace.path), this.directory);
      if (!path || (!isAbsolute(path) && path !== '..' && !path.startsWith('..' + sep))) throw new HostError('unsafe_private_path');
    }
  }
  private prepare(): Promise<void> { return this.ready ??= privateDirectory(this.directory); }
  /** Runtime lock is held before this runs; old opaque upload IDs cannot replay. */
  async startup(workspaces: readonly { path: string }[]): Promise<void> {
    try { await lstat(this.directory); } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw error; }
    this.assertOutside(workspaces); await this.prepare(); await this.removeOrphans();
  }
  private async removeOrphans(): Promise<void> {
    let names;
    try { names = await readdir(this.directory, { withFileTypes: true }); } catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return; throw error; }
    for (const entry of names) {
      if (!/^[a-f0-9]{48}\.tmp$/.test(entry.name) || !entry.isFile() || entry.isSymbolicLink()) throw new HostError('unsafe_private_path');
      const path = join(this.directory, entry.name);
      if (this.entries.has(path) || claimedPaths.has(path)) continue;
      try {
        const target = await privatePath(path);
        if (dirname(target) !== this.directory) throw new HostError('unsafe_private_path');
        if (Date.now() - (await lstat(target)).mtimeMs >= FileStaging.orphanGraceMs) await unlink(target);
      } catch (error) {
        if (error instanceof HostError) throw error;
        this.cleanupFailures++; this.lastCleanupFailureAt = Date.now();
        // Orphans stay discoverable on disk; retry on the next bounded sweep.
      }
    }
  }
  private stopIfDrained(): void { if (this.closed && !this.entries.size) clearInterval(this.sweepTimer); }
  private sweep(): Promise<void> {
    return this.sweeping ??= (async () => {
      await Promise.all([...this.entries].filter(([, entry]) => entry.pending && !entry.claimed).map(([path]) => this.remove(path).catch(() => {})));
      if (!this.closed && this.ready) await this.removeOrphans();
    })().finally(() => { this.sweeping = undefined; this.stopIfDrained(); });
  }
  /** Admission synchronously transfers ownership before dispatch's first await. */
  claim(path: string): void {
    const entry = this.entries.get(path);
    if (!entry || entry.pending || entry.claimed || this.closed) throw new HostError('not_found');
    entry.claimed = true; claimedPaths.add(path);
  }
  release(path: string): void {
    const entry = this.entries.get(path); if (entry) entry.claimed = false;
    claimedPaths.delete(path);
  }
  async save(data: AsyncIterable<Uint8Array>, size: number, signal: AbortSignal): Promise<string> {
    // Reserve before any await, including directory creation and concurrent saves.
    if (size > UPLOAD_LIMIT || size < 1) throw new HostError('payload_too_large');
    if (this.entries.size >= 64 || [...this.entries.values()].reduce((n, e) => n + e.size, 0) + size > 512 * 1024 * 1024) throw new HostError('rate_limited');
    const path = join(this.directory, randomBytes(24).toString('hex') + '.tmp');
    this.entries.set(path, { size });
    try {
      await this.prepare(); signal.throwIfAborted();
      // The Windows private directory grants only this SID, including inherited
      // file access; POSIX creation is 0600. O_EXCL rejects existing/symlink names.
      const handle = await open(path, 'wx', 0o600);
      try {
        let received = 0;
        for await (const chunk of data) {
          signal.throwIfAborted(); received += chunk.byteLength;
          if (received > size) throw new HostError('payload_too_large');
          let offset = 0;
          while (offset < chunk.byteLength) { const result = await handle.write(chunk, offset, chunk.byteLength - offset); offset += result.bytesWritten; }
        }
        signal.throwIfAborted(); if (received !== size) throw new HostError('invalid_request');
      } finally { await handle.close(); }
      return path;
    } catch (error) { await this.remove(path); throw error; }
  }
  read(path: string, signal: AbortSignal): AsyncIterable<Uint8Array> {
    if (!this.entries.has(path) || dirname(path) !== this.directory) throw new HostError('not_found');
    return createReadStream(path, { highWaterMark: 65536, signal });
  }
  async remove(path: string): Promise<void> {
    const entry = this.entries.get(path); if (!entry) return;
    if (dirname(path) !== this.directory || !/^[a-f0-9]{48}\.tmp$/.test(path.slice(this.directory.length + 1))) throw new HostError('unsafe_private_path');
    entry.pending = true;
    if (entry.claimed) return;
    if (entry.removing) return entry.removing;
    entry.removing = (async () => {
      try { await unlink(path); } catch (error) {
        if ((error as NodeJS.ErrnoException).code !== 'ENOENT') {
          this.cleanupFailures++; this.lastCleanupFailureAt = Date.now(); throw error;
        }
      }
      this.entries.delete(path); this.stopIfDrained();
    })();
    try { await entry.removing; } finally { delete entry.removing; }
  }
  async close(): Promise<void> {
    this.closed = true;
    await Promise.allSettled([...this.entries.keys()].map(path => this.remove(path)));
    this.stopIfDrained();
  }
  /** Path-free operational counters; no staged filename or content leaves this object. */
  cleanupStats(): { pending: number; failures: number; lastFailureAt?: number } {
    const pending = [...this.entries.values()].filter(entry => entry.pending).length;
    return this.lastCleanupFailureAt === undefined
      ? { pending, failures: this.cleanupFailures }
      : { pending, failures: this.cleanupFailures, lastFailureAt: this.lastCleanupFailureAt };
  }
}
