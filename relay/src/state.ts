import { createHash, randomBytes, timingSafeEqual } from 'node:crypto';
import { chmodSync, existsSync, lstatSync, mkdirSync } from 'node:fs';
import { dirname, isAbsolute } from 'node:path';
import { DatabaseSync } from 'node:sqlite';

export const ROUTE_ID = /^[a-f0-9]{32}$/;
export const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$/;
export const TOKEN = /^[A-Za-z0-9_-]{43}$/;
export const DIGEST = /^[a-f0-9]{64}$/;
export function hashSecret(token: string): string { return createHash('sha256').update(token, 'utf8').digest('hex'); }
export function equalDigest(actual: string, expected: string): boolean {
  return DIGEST.test(actual) && DIGEST.test(expected) && timingSafeEqual(Buffer.from(actual, 'hex'), Buffer.from(expected, 'hex'));
}
export function validToken(token: string): boolean {
  return TOKEN.test(token) && Buffer.from(token, 'base64url').toString('base64url') === token;
}

/** Only ownership digests are durable. No live grant or tunnel is recovered from this database. */
export class RelayState {
  readonly path: string;
  private readonly db: DatabaseSync;
  private readonly revokeListeners = new Set<(routeId: string) => void>();
  private closed = false;
  constructor(path: string, _options: { now?: () => number } = {}) {
    if (path !== ':memory:' && !isAbsolute(path)) throw new Error('invalid_state_path');
    this.path = path;
    if (path !== ':memory:') {
      mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
      if (existsSync(path)) {
        const file = lstatSync(path);
        if (!file.isFile() || file.isSymbolicLink()) throw new Error('invalid_state_path');
      }
    }
    this.db = new DatabaseSync(path);
    this.db.exec('PRAGMA busy_timeout=5000; PRAGMA journal_mode=WAL; CREATE TABLE IF NOT EXISTS routes (route_id TEXT PRIMARY KEY, connector_hash TEXT NOT NULL);');
    if (path !== ':memory:' && process.platform !== 'win32') chmodSync(path, 0o600);
  }
  provisionRoute(): { routeId: string; connectorToken: string } {
    const routeId = randomBytes(16).toString('hex'), connectorToken = randomBytes(32).toString('base64url');
    this.db.exec('BEGIN IMMEDIATE');
    try {
      const count = this.db.prepare('SELECT COUNT(*) AS count FROM routes').get();
      if (Number(count?.count) >= 256) throw new Error('route_capacity');
      this.db.prepare('INSERT INTO routes (route_id, connector_hash) VALUES (?, ?)').run(routeId, hashSecret(connectorToken));
      this.db.exec('COMMIT');
      return { routeId, connectorToken };
    } catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }
  authenticateRoute(routeId: string, token: string): boolean {
    if (!ROUTE_ID.test(routeId) || !validToken(token)) return false;
    const row = this.db.prepare('SELECT connector_hash FROM routes WHERE route_id=?').get(routeId);
    const match = equalDigest(hashSecret(token), typeof row?.connector_hash === 'string' ? row.connector_hash : '0'.repeat(64));
    return Boolean(row) && match;
  }
  routeExists(routeId: string): boolean { return Boolean(this.db.prepare('SELECT 1 FROM routes WHERE route_id=?').get(routeId)); }
  listRoutes(): { routeId: string }[] {
    return this.db.prepare('SELECT route_id FROM routes ORDER BY route_id').all().map(row => ({ routeId: String(row.route_id) }));
  }
  revokeRoute(routeId: string): boolean {
    if (!ROUTE_ID.test(routeId)) return false;
    const changed = this.db.prepare('DELETE FROM routes WHERE route_id=?').run(routeId).changes !== 0;
    if (changed) for (const listener of this.revokeListeners) listener(routeId);
    return changed;
  }
  onRevoke(listener: (routeId: string) => void): () => void {
    this.revokeListeners.add(listener); return () => this.revokeListeners.delete(listener);
  }
  close(): void { if (this.closed) return; this.closed = true; this.revokeListeners.clear(); this.db.close(); }
}
