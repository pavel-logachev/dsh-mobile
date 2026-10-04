import { realpath, stat } from 'node:fs/promises';
import { basename, isAbsolute, parse } from 'node:path';
import { validOpaqueId } from './config.ts';
import { HostError } from './errors.ts';
import type { HostConfiguration, WorkspaceConfig } from './types.ts';

/** Read-only structural seams verified against installed dsh-workspace rc.2. */
export interface DshWorkspace {
  readonly id: string;
  readonly path: string;
  readonly title: string;
  status(): Promise<'ok' | 'missing-dir'>;
}
export interface DshWorkspaceRegistry {
  list(): readonly DshWorkspace[];
  readonly archivedSessionIds: readonly string[];
}
export interface WorkspaceSource {
  /** Registry identities are also valid upstream creation/attachment targets. */
  readonly kind: 'explicit' | 'dsh-registry';
  /** Fresh owner-selected scope, in display order. No client-supplied paths. */
  list(): Promise<readonly WorkspaceConfig[]>;
  /** Optional synchronous final revision check for a previously returned view.
   * The live registry implements this so no filesystem await can leave an old
   * workspace binding authoritative at upstream mutation admission.
   */
  isCurrent?(view: readonly WorkspaceConfig[]): boolean;
  /** Empty for legacy explicit mode; registry mode uses the live global archive. */
  archivedSessionIds(): ReadonlySet<string>;
}
/** DSH rc.2 identity is exact fs.realpath output, including Windows case.
 * Canonicalizing spelling again or case-folding could merge distinct directories
 * on a case-sensitive volume. Explicit roots use this same safe identity.
 */
export function workspacePathKey(canonicalPath: string): string { return canonicalPath; }
function title(value: string, path: string): string {
  const clean = (text: string) => text.replace(/[\x00-\x1f\x7f-\x9f]/g, '').trim();
  const name = clean(value) || clean(basename(path) || parse(path).root) || 'Workspace';
  let result = '', bytes = 0;
  for (const char of name) { const size = Buffer.byteLength(char); if (bytes + size > 128) break; result += char; bytes += size; }
  return result.trim();
}
async function directory(path: string): Promise<boolean> { try { return (await stat(path)).isDirectory(); } catch { return false; } }
/** Inspected Cordis 4.0.4 ctx.get() returns a NEW tracing proxy per lookup.
 * Its original symbol identifies the provider's actual service; keep calling
 * through the proxy so Cordis context tracing/realm ownership stays intact.
 * Plain structural services use their own reference (including test fixtures).
 */
function registryIdentity(service: DshWorkspaceRegistry): unknown {
  return Reflect.get(service, Symbol.for('cordis.original')) ?? service;
}

/** Explicit roots are canonicalized once; registry identities/titles/order are never cached. */
export async function createWorkspaceSource(config: Pick<HostConfiguration, 'workspaceSource' | 'workspaces'>, registry?: () => DshWorkspaceRegistry | undefined): Promise<WorkspaceSource> {
  if (config.workspaceSource === 'dsh-registry') {
    if (config.workspaces?.length) throw new HostError('invalid_config');
    const current = (): DshWorkspaceRegistry => {
      try {
        const service = registry?.();
        if (!service || typeof service.list !== 'function' || !Array.isArray(service.archivedSessionIds)) throw new Error();
        return service;
      } catch { throw new HostError('workspace_registry_unavailable'); }
    };
    type Revision = { identity: unknown; entries: readonly { id: string; path: string; title: string }[] };
    const revisions = new WeakMap<readonly WorkspaceConfig[], Revision>();
    const unchanged = ({ identity, entries }: Revision): boolean => {
      const service = current(); // Still require the currently ACTIVE service on every check.
      if (registryIdentity(service) !== identity) return false;
      const rows = service.list();
      return Array.isArray(rows) && Math.min(rows.length, 100) === entries.length && entries.every((entry, index) => {
        const row = rows[index];
        return !!row && row.id === entry.id && row.path === entry.path && row.title === entry.title;
      });
    };
    current(); // Fail closed at startup, rather than serving an accidental empty/all-folders scope.
    return {
      kind: 'dsh-registry',
      async list() {
        try {
          const service = current();
          const rows = service.list(); // Synchronous durable sidebar order, on EVERY request/check.
          if (!Array.isArray(rows)) throw new Error();
          const ids = new Set<string>(), paths = new Set<string>();
          const entries = rows.slice(0, 100).map(row => {
            if (!row || !validOpaqueId(row.id) || typeof row.title !== 'string' || typeof row.path !== 'string' || !isAbsolute(row.path) || typeof row.status !== 'function' || ids.has(row.id) || paths.has(workspacePathKey(row.path))) throw new Error();
            ids.add(row.id); paths.add(workspacePathKey(row.path));
            return { row, id: row.id, path: row.path, title: row.title, workspace: { id: row.id, name: title(row.title, row.path), path: row.path } };
          });
          const available = await Promise.all(entries.map(async ({ row, workspace }) => await row.status() === 'ok' ? workspace : undefined));
          // status() yields to filesystem I/O. A service replacement/removal or
          // revision of the bounded visible registry must never authorize the
          // old projection. rc.2 has no revision counter: compare exact identity,
          // canonical paths, titles and order synchronously after every await.
          const revision = { identity: registryIdentity(service), entries };
          if (!unchanged(revision)) throw new Error();
          const visible = available.filter((item): item is WorkspaceConfig => item !== undefined);
          revisions.set(visible, revision);
          return visible;
        } catch { throw new HostError('workspace_registry_unavailable'); }
      },
      isCurrent(view) {
        try { const revision = revisions.get(view); return !!revision && unchanged(revision); }
        catch { throw new HostError('workspace_registry_unavailable'); }
      },
      archivedSessionIds() {
        try { return new Set(current().archivedSessionIds); }
        catch { throw new HostError('workspace_registry_unavailable'); }
      },
    };
  }
  if (config.workspaceSource !== undefined && config.workspaceSource !== 'explicit') throw new HostError('invalid_config');
  if (!Array.isArray(config.workspaces) || !config.workspaces.length || config.workspaces.length > 100) throw new HostError('invalid_config');
  const workspaces: WorkspaceConfig[] = [], ids = new Set<string>(), paths = new Set<string>();
  try {
    for (const entry of config.workspaces) {
      if (!entry || !validOpaqueId(entry.id) || typeof entry.name !== 'string' || !entry.name.trim() || !isAbsolute(entry.path)) throw new Error();
      const path = await realpath(entry.path), key = workspacePathKey(path);
      if (!await directory(path) || ids.has(entry.id) || paths.has(key)) throw new Error();
      ids.add(entry.id); paths.add(key); workspaces.push({ ...entry, path });
    }
  } catch { throw new HostError('invalid_config'); }
  return { kind: 'explicit', async list() { return workspaces; }, archivedSessionIds() { return new Set(); } };
}
