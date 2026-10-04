import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { mkdirSync, openSync, closeSync, chmodSync } from 'node:fs';
import { dirname, isAbsolute } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { HostError } from './errors.ts';
import type { CommandReceipt, DeviceGrants, RelayGrant, RelayAccess } from './types.ts';

export interface AuthorizedDevice { deviceId: string; deviceName: string; grants: DeviceGrants; createdAt: number }
export interface DeviceSummary extends AuthorizedDevice { revokedAt: number | null }
export interface PairingOffer { pairingToken: string; expiresAt: number }
export interface PairingResult { deviceId: string; deviceToken: string }
export interface RemotePairingOffer extends PairingOffer { relayAccess: RelayAccess }
export interface RemotePairingResult extends PairingResult { relayAccess: RelayAccess }
export interface RelayPublication { published: boolean; generation: string | null; revoked: boolean; expiresAt: number }
export interface StoredCommand { receipt: CommandReceipt; httpStatus: number; workspaceId: string | null }
export interface CommandAdmission extends StoredCommand { fresh: boolean }
export interface StateOptions { now?: () => number }

type Row = Record<string, unknown>;
const TOKEN = /^[A-Za-z0-9_-]{43}$/;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function isRequestId(value: unknown): value is string { return typeof value === 'string' && UUID.test(value); }
export function hashSecret(value: string): string { return createHash('sha256').update(value, 'utf8').digest('hex'); }
/** '*' is a grant marker, never a mobile workspace id. */
export function allowsWorkspace(ids: readonly string[], workspaceId: string): boolean { return ids.includes('*') || ids.includes(workspaceId); }
export function validateGrants(grants: DeviceGrants): DeviceGrants {
  if (!grants || !Array.isArray(grants.readWorkspaceIds) || !Array.isArray(grants.executeWorkspaceIds)) throw new HostError('invalid_request');
  for (const ids of [grants.readWorkspaceIds, grants.executeWorkspaceIds]) {
    if (ids.length > 100 || ids.some(id => typeof id !== 'string' || (id !== '*' && !/^[A-Za-z0-9_-]{1,64}$/.test(id))) || new Set(ids).size !== ids.length || (ids.includes('*') && ids.length !== 1)) throw new HostError('invalid_request');
  }
  if (grants.executeWorkspaceIds.some(id => !allowsWorkspace(grants.readWorkspaceIds, id))) throw new HostError('invalid_request');
  return { readWorkspaceIds: [...grants.readWorkspaceIds].sort(), executeWorkspaceIds: [...grants.executeWorkspaceIds].sort() };
}

/** Local admin/storage boundary. No transcripts or plaintext credentials are retained. */
export class HostState {
  readonly path: string;
  private readonly db: DatabaseSync;
  private readonly now: () => number;
  private closed = false;
  private readonly revokeListeners = new Set<(deviceId: string) => void>();
  private runtimeId: string | undefined;
  private runtimeLockDb: DatabaseSync | undefined;

  constructor(path: string, options: StateOptions = {}) {
    if (!isAbsolute(path)) throw new HostError('invalid_config');
    this.path = path;
    this.now = options.now ?? Date.now;
    mkdirSync(dirname(path), { recursive: true, mode: 0o700 });
    try { closeSync(openSync(path, 'wx', 0o600)); } catch (e) { if ((e as NodeJS.ErrnoException).code !== 'EEXIST') throw e; }
    if (process.platform !== 'win32') chmodSync(path, 0o600);
    this.db = new DatabaseSync(path, { timeout: 5_000, enableForeignKeyConstraints: true, enableDoubleQuotedStringLiterals: false, allowExtension: false });
    this.db.exec(`
      PRAGMA journal_mode = WAL;
      PRAGMA synchronous = FULL;
      CREATE TABLE IF NOT EXISTS devices (
        id TEXT PRIMARY KEY, name TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
        grants TEXT NOT NULL, created_at INTEGER NOT NULL, revoked_at INTEGER
      ) STRICT;
      CREATE TABLE IF NOT EXISTS pairings (
        token_hash TEXT PRIMARY KEY, grants TEXT NOT NULL, expires_at INTEGER NOT NULL, consumed_at INTEGER
      ) STRICT;
      CREATE TABLE IF NOT EXISTS commands (
        device_id TEXT NOT NULL REFERENCES devices(id), request_id TEXT NOT NULL,
        operation TEXT NOT NULL, payload_hash TEXT NOT NULL, workspace_id TEXT,
        status TEXT NOT NULL CHECK(status IN ('pending','dispatching','accepted','rejected','uncertain')),
        result TEXT, error TEXT, http_status INTEGER NOT NULL, updated_at INTEGER NOT NULL,
        PRIMARY KEY(device_id, request_id)
      ) STRICT;
      CREATE TABLE IF NOT EXISTS runtime_lock (id INTEGER PRIMARY KEY CHECK(id=1), owner TEXT NOT NULL, pid INTEGER NOT NULL) STRICT;
    `);
    // Early development databases have no scope column; unknown prior scope fails closed.
    if (!this.db.prepare('PRAGMA table_info(commands)').all().some(column => column.name === 'workspace_id')) this.db.exec('ALTER TABLE commands ADD COLUMN workspace_id TEXT');
    if (!this.db.prepare('PRAGMA table_info(devices)').all().some(column => column.name === 'publication_pending')) this.db.exec('ALTER TABLE devices ADD COLUMN publication_pending INTEGER NOT NULL DEFAULT 0');
    this.db.exec(`CREATE TABLE IF NOT EXISTS relay_grants (
      access_id TEXT PRIMARY KEY, route_id TEXT NOT NULL, token_hash TEXT NOT NULL UNIQUE,
      device_id TEXT REFERENCES devices(id), pairing_hash TEXT REFERENCES pairings(token_hash),
      expires_at INTEGER NOT NULL, max_streams INTEGER NOT NULL CHECK(max_streams IN (2,8)),
      published INTEGER NOT NULL DEFAULT 0, generation TEXT, revoked_at INTEGER,
      pending_since INTEGER
    ) STRICT;
    CREATE TABLE IF NOT EXISTS relay_status (route_id TEXT PRIMARY KEY, connected INTEGER NOT NULL, ready INTEGER NOT NULL, generation TEXT, updated_at INTEGER NOT NULL) STRICT;
    PRAGMA user_version = 3;`);
  }

  private transaction<T>(fn: () => T): T {
    this.db.exec('BEGIN IMMEDIATE');
    try { const result = fn(); this.db.exec('COMMIT'); return result; }
    catch (error) { this.db.exec('ROLLBACK'); throw error; }
  }

  createPairing(grants: DeviceGrants, ttlMs = 300_000): PairingOffer {
    const safeGrants = validateGrants(grants);
    if (!Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > 900_000) throw new HostError('invalid_request');
    const pairingToken = randomBytes(32).toString('base64url');
    const expiresAt = this.now() + ttlMs;
    this.transaction(() => {
      this.db.prepare('DELETE FROM pairings WHERE (expires_at <= ? OR consumed_at IS NOT NULL) AND NOT EXISTS (SELECT 1 FROM relay_grants WHERE pairing_hash=pairings.token_hash)').run(this.now());
      this.db.prepare('INSERT INTO pairings (token_hash, grants, expires_at) VALUES (?, ?, ?)').run(hashSecret(pairingToken), JSON.stringify(safeGrants), expiresAt);
    });
    return { pairingToken, expiresAt };
  }

  consumePairing(pairingToken: string, deviceName: string): PairingResult {
    if (typeof pairingToken !== 'string' || !TOKEN.test(pairingToken)) throw new HostError('unauthorized');
    if (typeof deviceName !== 'string' || !deviceName.trim() || Buffer.byteLength(deviceName, 'utf8') > 128 || /[\x00-\x1f\x7f]/.test(deviceName)) throw new HostError('invalid_request');
    return this.transaction(() => {
      const row = this.db.prepare('SELECT grants FROM pairings WHERE token_hash=? AND consumed_at IS NULL AND expires_at > ? AND NOT EXISTS (SELECT 1 FROM relay_grants WHERE pairing_hash=pairings.token_hash)').get(hashSecret(pairingToken), this.now());
      if (!row) throw new HostError('unauthorized');
      const deviceId = randomUUID();
      const deviceToken = randomBytes(32).toString('base64url');
      this.db.prepare('UPDATE pairings SET consumed_at=? WHERE token_hash=?').run(this.now(), hashSecret(pairingToken));
      this.db.prepare('INSERT INTO devices (id, name, token_hash, grants, created_at) VALUES (?, ?, ?, ?, ?)').run(deviceId, deviceName.trim(), hashSecret(deviceToken), String(row.grants), this.now());
      return { deviceId, deviceToken };
    });
  }

  createRemotePairing(grants: DeviceGrants, routeId: string, ttlMs = 900_000): RemotePairingOffer {
    const safeGrants = validateGrants(grants);
    if (!/^[a-f0-9]{32}$/.test(routeId) || !Number.isSafeInteger(ttlMs) || ttlMs < 1 || ttlMs > 900_000) throw new HostError('invalid_request');
    const pairingToken = randomBytes(32).toString('base64url'), accessToken = randomBytes(32).toString('base64url');
    const accessId = randomUUID(), expiresAt = this.now() + ttlMs;
    this.transaction(() => {
      this.pruneRelayRecords();
      const count = this.db.prepare('SELECT count(*) AS n FROM relay_grants WHERE route_id=? AND device_id IS NULL AND revoked_at IS NULL AND expires_at>?').get(routeId, this.now());
      if (Number(count?.n) >= 16 || this.liveRelayGrantCount(routeId) >= 64) throw new HostError('rate_limited');
      this.db.prepare('INSERT INTO pairings (token_hash,grants,expires_at) VALUES (?,?,?)').run(hashSecret(pairingToken), JSON.stringify(safeGrants), expiresAt);
      this.db.prepare('INSERT INTO relay_grants (access_id,route_id,token_hash,pairing_hash,expires_at,max_streams,pending_since) VALUES (?,?,?,?,?,2,?)').run(accessId, routeId, hashSecret(accessToken), hashSecret(pairingToken), expiresAt, this.now());
    });
    return { pairingToken, expiresAt, relayAccess: { accessId, accessToken, expiresAt } };
  }
  remotePairingRoute(pairingToken: string): string | undefined {
    if (!TOKEN.test(pairingToken)) return undefined;
    const row = this.db.prepare('SELECT route_id FROM relay_grants WHERE pairing_hash=? AND revoked_at IS NULL AND expires_at>?').get(hashSecret(pairingToken), this.now());
    return row ? String(row.route_id) : undefined;
  }
  consumeRemotePairing(pairingToken: string, deviceName: string, routeId: string): RemotePairingResult {
    if (typeof pairingToken !== 'string' || !TOKEN.test(pairingToken)) throw new HostError('unauthorized');
    if (typeof deviceName !== 'string' || !deviceName.trim() || Buffer.byteLength(deviceName) > 128 || /[\x00-\x1f\x7f]/.test(deviceName)) throw new HostError('invalid_request');
    return this.transaction(() => {
      this.pruneRelayRecords();
      const row = this.db.prepare('SELECT pairings.grants FROM pairings JOIN relay_grants ON pairing_hash=pairings.token_hash WHERE pairings.token_hash=? AND consumed_at IS NULL AND pairings.expires_at>? AND route_id=? AND relay_grants.revoked_at IS NULL AND published=1').get(hashSecret(pairingToken), this.now(), routeId);
      if (!row) throw new HostError('unauthorized');
      const devices = this.db.prepare('SELECT count(*) AS n FROM relay_grants JOIN devices ON devices.id=device_id WHERE route_id=? AND relay_grants.revoked_at IS NULL AND devices.revoked_at IS NULL AND expires_at>?').get(routeId, this.now());
      if (Number(devices?.n) >= 16 || this.liveRelayGrantCount(routeId) >= 64) throw new HostError('rate_limited');
      const deviceId = randomUUID(), deviceToken = randomBytes(32).toString('base64url'), accessId = randomUUID(), accessToken = randomBytes(32).toString('base64url');
      const expiresAt = this.now() + 365 * 24 * 60 * 60 * 1000;
      this.db.prepare('UPDATE pairings SET consumed_at=? WHERE token_hash=?').run(this.now(), hashSecret(pairingToken));
      this.db.prepare('INSERT INTO devices (id,name,token_hash,grants,created_at,publication_pending) VALUES (?,?,?,?,?,1)').run(deviceId, deviceName.trim(), hashSecret(deviceToken), String(row.grants), this.now());
      this.db.prepare('INSERT INTO relay_grants (access_id,route_id,token_hash,device_id,expires_at,max_streams,pending_since) VALUES (?,?,?,?,?,8,?)').run(accessId, routeId, hashSecret(accessToken), deviceId, expiresAt, this.now());
      return { deviceId, deviceToken, relayAccess: { accessId, accessToken, expiresAt } };
    });
  }
  private liveRelayGrantCount(routeId: string): number { return Number(this.db.prepare('SELECT count(*) AS n FROM relay_grants WHERE route_id=? AND revoked_at IS NULL AND expires_at>?').get(routeId, this.now())?.n); }
  /** Called inside a write transaction: invalidate linked credentials BEFORE deleting binding rows. */
  private pruneRelayRecords(): void {
    const expired = 'revoked_at IS NOT NULL OR expires_at<=? OR (pending_since IS NOT NULL AND pending_since<=?)';
    this.db.prepare(`UPDATE pairings SET consumed_at=? WHERE consumed_at IS NULL AND token_hash IN (SELECT pairing_hash FROM relay_grants WHERE ${expired})`).run(this.now(), this.now(), this.now() - 5000);
    this.db.prepare(`UPDATE devices SET revoked_at=?,publication_pending=0 WHERE revoked_at IS NULL AND id IN (SELECT device_id FROM relay_grants WHERE ${expired})`).run(this.now(), this.now(), this.now() - 5000);
    this.db.prepare(`DELETE FROM relay_grants WHERE ${expired}`).run(this.now(), this.now() - 5000);
    this.db.prepare('DELETE FROM pairings WHERE (consumed_at IS NOT NULL OR expires_at<=?) AND NOT EXISTS (SELECT 1 FROM relay_grants WHERE pairing_hash=pairings.token_hash)').run(this.now());
  }
  relayGrantSnapshot(routeId: string): RelayGrant[] {
    this.transaction(() => this.pruneRelayRecords());
    return this.db.prepare('SELECT relay_grants.* FROM relay_grants LEFT JOIN devices ON devices.id=device_id WHERE route_id=? AND relay_grants.revoked_at IS NULL AND expires_at>? AND (device_id IS NULL OR devices.revoked_at IS NULL) ORDER BY access_id LIMIT 65').all(routeId, this.now()).map(row => ({ accessId: String(row.access_id), tokenHash: String(row.token_hash), deviceId: row.device_id === null ? null : String(row.device_id), expiresAt: Number(row.expires_at), maxStreams: Number(row.max_streams) as 2 | 8 }));
  }
  relayPublication(accessId: string): RelayPublication | undefined {
    const row = this.db.prepare('SELECT * FROM relay_grants WHERE access_id=?').get(accessId);
    return row ? { published: row.published === 1, generation: row.generation === null ? null : String(row.generation), revoked: row.revoked_at !== null, expiresAt: Number(row.expires_at) } : undefined;
  }
  acknowledgeRelaySnapshot(routeId: string, grants: RelayGrant[], generation: string): void {
    this.transaction(() => {
      for (const grant of grants) {
        const changed = this.db.prepare('UPDATE relay_grants SET published=1,generation=?,pending_since=NULL WHERE route_id=? AND access_id=? AND token_hash=? AND expires_at=? AND max_streams=? AND revoked_at IS NULL AND expires_at>?').run(generation, routeId, grant.accessId, grant.tokenHash, grant.expiresAt, grant.maxStreams, this.now());
        if (changed.changes && grant.deviceId) this.db.prepare('UPDATE devices SET publication_pending=0 WHERE id=? AND revoked_at IS NULL').run(grant.deviceId);
      }
    });
  }
  revokeRelayGrant(accessId: string): void {
    this.transaction(() => {
      const row = this.db.prepare('SELECT device_id,pairing_hash FROM relay_grants WHERE access_id=?').get(accessId);
      if (!row) return;
      this.db.prepare('UPDATE relay_grants SET revoked_at=?,published=0,pending_since=NULL WHERE access_id=?').run(this.now(), accessId);
      if (row.device_id) {
        this.db.prepare('UPDATE devices SET revoked_at=?,publication_pending=0 WHERE id=? AND revoked_at IS NULL').run(this.now(), String(row.device_id));
        this.db.prepare('UPDATE relay_grants SET revoked_at=?,published=0,pending_since=NULL WHERE device_id=?').run(this.now(), String(row.device_id));
      }
      if (row.pairing_hash) this.db.prepare('UPDATE pairings SET consumed_at=? WHERE token_hash=?').run(this.now(), String(row.pairing_hash));
    });
  }
  recoverPendingRelayPublications(): void {
    this.transaction(() => {
      this.db.prepare('UPDATE devices SET revoked_at=?,publication_pending=0 WHERE publication_pending=1 AND revoked_at IS NULL').run(this.now());
      this.db.prepare('UPDATE pairings SET consumed_at=? WHERE token_hash IN (SELECT pairing_hash FROM relay_grants WHERE pending_since IS NOT NULL) AND consumed_at IS NULL').run(this.now());
      this.db.prepare('UPDATE relay_grants SET revoked_at=?,published=0,pending_since=NULL WHERE pending_since IS NOT NULL AND revoked_at IS NULL').run(this.now());
      this.db.prepare('UPDATE relay_grants SET published=0,generation=NULL').run();
      this.db.prepare('UPDATE relay_status SET connected=0,ready=0,generation=NULL,updated_at=?').run(this.now());
    });
  }
  updateRelayStatus(routeId: string, connected: boolean, ready: boolean, generation: string | null): void {
    this.transaction(() => {
      this.db.prepare('INSERT INTO relay_status (route_id,connected,ready,generation,updated_at) VALUES (?,?,?,?,?) ON CONFLICT(route_id) DO UPDATE SET connected=excluded.connected,ready=excluded.ready,generation=excluded.generation,updated_at=excluded.updated_at').run(routeId, connected ? 1 : 0, ready ? 1 : 0, generation, this.now());
      if (!connected) this.db.prepare('UPDATE relay_grants SET published=0,generation=NULL WHERE route_id=?').run(routeId);
    });
  }
  relayStatus(routeId: string): { connected: boolean; ready: boolean; generation: string | null; updatedAt: number; activeGrants: number } {
    const row = this.db.prepare('SELECT * FROM relay_status WHERE route_id=?').get(routeId);
    return { connected: row?.connected === 1, ready: row?.ready === 1, generation: row?.generation ? String(row.generation) : null, updatedAt: row ? Number(row.updated_at) : 0, activeGrants: this.liveRelayGrantCount(routeId) };
  }

  authenticate(deviceToken: string): AuthorizedDevice | undefined {
    if (typeof deviceToken !== 'string' || !TOKEN.test(deviceToken)) return undefined;
    const row = this.db.prepare('SELECT * FROM devices WHERE token_hash=? AND revoked_at IS NULL AND publication_pending=0 AND NOT EXISTS (SELECT 1 FROM relay_grants WHERE device_id=devices.id AND (revoked_at IS NOT NULL OR expires_at<=?))').get(hashSecret(deviceToken), this.now());
    return row ? this.device(row) : undefined;
  }
  getDevice(deviceId: string): AuthorizedDevice | undefined {
    const row = this.db.prepare('SELECT * FROM devices WHERE id=? AND revoked_at IS NULL AND publication_pending=0 AND NOT EXISTS (SELECT 1 FROM relay_grants WHERE device_id=devices.id AND (revoked_at IS NOT NULL OR expires_at<=?))').get(deviceId, this.now());
    return row ? this.device(row) : undefined;
  }
  private device(row: Row): AuthorizedDevice {
    return { deviceId: String(row.id), deviceName: String(row.name), grants: validateGrants(JSON.parse(String(row.grants)) as DeviceGrants), createdAt: Number(row.created_at) };
  }
  listDevices(): DeviceSummary[] {
    return this.db.prepare('SELECT * FROM devices ORDER BY created_at, id').all().map(row => ({ ...this.device(row), revokedAt: row.revoked_at === null ? null : Number(row.revoked_at) }));
  }
  /** Replace ONLY active device grants; credentials, relay capabilities and receipts are unchanged. */
  replaceDeviceGrants(deviceId: string, grants: DeviceGrants): AuthorizedDevice {
    const safeGrants = validateGrants(grants);
    if (!isRequestId(deviceId)) throw new HostError('invalid_request');
    return this.transaction(() => {
      const device = this.getDevice(deviceId);
      if (!device) throw new HostError('not_found');
      const changed = this.db.prepare('UPDATE devices SET grants=? WHERE id=? AND revoked_at IS NULL').run(JSON.stringify(safeGrants), deviceId);
      if (!changed.changes) throw new HostError('not_found');
      return { ...device, grants: safeGrants };
    });
  }
  revokeDevice(deviceId: string): boolean {
    const changed = this.transaction(() => {
      const result = this.db.prepare('UPDATE devices SET revoked_at=?,publication_pending=0 WHERE id=? AND revoked_at IS NULL').run(this.now(), deviceId);
      this.db.prepare('UPDATE relay_grants SET revoked_at=?,published=0,pending_since=NULL WHERE device_id=? AND revoked_at IS NULL').run(this.now(), deviceId);
      return result.changes !== 0;
    });
    if (changed) for (const listener of this.revokeListeners) listener(deviceId);
    return changed;
  }
  onRevoke(listener: (deviceId: string) => void): () => void { this.revokeListeners.add(listener); return () => this.revokeListeners.delete(listener); }

  admitCommand(deviceId: string, requestId: string, operation: string, payloadHash: string, workspaceId: string | null = null): CommandAdmission {
    if (!isRequestId(requestId) || !/^[a-f0-9]{64}$/.test(payloadHash)) throw new HostError('invalid_request');
    requestId = requestId.toLowerCase();
    return this.transaction(() => {
      if (!this.getDevice(deviceId)) throw new HostError('unauthorized');
      const prior = this.db.prepare('SELECT * FROM commands WHERE device_id=? AND request_id=?').get(deviceId, requestId);
      if (prior) {
        if (prior.operation !== operation || prior.payload_hash !== payloadHash) throw new HostError('conflict');
        return { ...this.command(prior), fresh: false };
      }
      const updatedAt = this.now();
      // Dispatch intent is committed before any adapter call, including across a process crash.
      this.db.prepare('INSERT INTO commands (device_id,request_id,operation,payload_hash,workspace_id,status,http_status,updated_at) VALUES (?,?,?,?,?,\'dispatching\',202,?)').run(deviceId, requestId, operation, payloadHash, workspaceId, updatedAt);
      return { fresh: true, receipt: { requestId, status: 'pending', updatedAt }, httpStatus: 202, workspaceId };
    });
  }
  getCommand(deviceId: string, requestId: string): StoredCommand | undefined {
    const row = this.db.prepare('SELECT * FROM commands WHERE device_id=? AND request_id=?').get(deviceId, requestId.toLowerCase());
    return row ? this.command(row) : undefined;
  }
  private command(row: Row): StoredCommand {
    const receipt: CommandReceipt = { requestId: String(row.request_id), status: row.status === 'dispatching' ? 'pending' : row.status as CommandReceipt['status'], updatedAt: Number(row.updated_at) };
    if (row.result !== null) receipt.result = JSON.parse(String(row.result)) as NonNullable<CommandReceipt['result']>;
    if (row.error !== null) receipt.error = JSON.parse(String(row.error)) as NonNullable<CommandReceipt['error']>;
    return { receipt, httpStatus: Number(row.http_status), workspaceId: row.workspace_id === null ? null : String(row.workspace_id) };
  }
  finishCommand(deviceId: string, requestId: string, status: Exclude<CommandReceipt['status'], 'pending'>, options: { result?: CommandReceipt['result']; error?: CommandReceipt['error']; httpStatus?: number } = {}): StoredCommand {
    const httpStatus = options.httpStatus ?? (status === 'uncertain' ? 202 : status === 'accepted' ? 200 : 400);
    this.db.prepare("UPDATE commands SET status=?,result=?,error=?,http_status=?,updated_at=? WHERE device_id=? AND request_id=? AND status IN ('pending','dispatching')").run(status, options.result ? JSON.stringify(options.result) : null, options.error ? JSON.stringify(options.error) : null, httpStatus, this.now(), deviceId, requestId.toLowerCase());
    const result = this.getCommand(deviceId, requestId);
    if (!result) throw new HostError('not_found');
    return result;
  }
  recoverUnfinished(): void {
    this.db.prepare("UPDATE commands SET status='uncertain',http_status=202,updated_at=? WHERE status IN ('pending','dispatching')").run(this.now());
  }

  /** A local admin connection never claims this lock or recovers active dispatches. */
  claimRuntime(): void {
    if (this.runtimeId) throw new HostError('conflict');
    const owner = randomUUID();
    // A distinct rollback-journal database holds an OS-enforced lock for this runtime.
    // Main-state transactions remain available to the local admin CLI. A crash releases
    // the exclusive file lock; PID reuse and wall-clock/heartbeat races are irrelevant.
    const lockPath = this.path + '.lock.sqlite';
    try { closeSync(openSync(lockPath, 'wx', 0o600)); } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'EEXIST') throw new HostError('unavailable'); }
    let lock: DatabaseSync | undefined;
    try {
      lock = new DatabaseSync(lockPath, { timeout: 0 });
      lock.exec('PRAGMA journal_mode=DELETE; CREATE TABLE IF NOT EXISTS lock_anchor (id INTEGER PRIMARY KEY); INSERT OR IGNORE INTO lock_anchor (id) VALUES (1);');
      if (lock.prepare('PRAGMA journal_mode').get()?.journal_mode !== 'delete') throw new HostError('conflict');
      lock.exec('BEGIN EXCLUSIVE;');
      this.transaction(() => {
        this.db.prepare('INSERT OR REPLACE INTO runtime_lock (id,owner,pid) VALUES (1,?,?)').run(owner, process.pid);
        this.recoverUnfinished();
      });
      this.runtimeLockDb = lock;
      this.runtimeId = owner;
    } catch {
      lock?.close();
      throw new HostError('conflict');
    }
  }
  releaseRuntime(): void {
    if (this.runtimeId) {
      this.db.prepare('DELETE FROM runtime_lock WHERE owner=?').run(this.runtimeId);
      this.runtimeId = undefined;
      this.runtimeLockDb?.exec('ROLLBACK');
      this.runtimeLockDb?.close();
      this.runtimeLockDb = undefined;
    }
  }
  close(): void {
    if (this.closed) return;
    this.releaseRuntime();
    this.closed = true;
    this.revokeListeners.clear();
    this.db.close();
  }
}
