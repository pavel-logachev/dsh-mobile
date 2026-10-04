import { createHash, createPrivateKey, X509Certificate } from 'node:crypto';
import { lstat, mkdir, readFile, realpath, stat } from 'node:fs/promises';
import { isIP } from 'node:net';
import { basename, dirname, isAbsolute, join, resolve } from 'node:path';
import { HostError } from './errors.ts';
import type { HostConfiguration, PreparedHostConfiguration, WorkspaceConfig } from './types.ts';
import { validateRelayUrl } from './relay-connector.ts';

export type LocalHostConfiguration = HostConfiguration & {
  /** Operator-configured invitation URL. Never includes a credential or query. */
  publicUrl?: string;
  /** Explicitly include the leaf certificate as an invitation trust anchor. */
  includeCertificatePem?: boolean;
}
export type PreparedLocalHostConfiguration = LocalHostConfiguration & PreparedHostConfiguration;
export interface PreparedConfiguration {
  config: PreparedLocalHostConfiguration;
  tls?: { cert: string; key: string; minVersion: 'TLSv1.2' };
  pinSha256?: string;
  certificatePem?: string;
}
export function isLoopbackBind(bind: string): boolean { return bind === '127.0.0.1' || bind === 'localhost' || bind === '::1'; }
export function isHttpDevelopmentBind(bind: string): boolean { return bind === '127.0.0.1' || bind === 'localhost'; }
export function validOpaqueId(id: unknown): id is string { return typeof id === 'string' && /^[A-Za-z0-9_-]{1,64}$/.test(id); }

async function regularFile(path: string, maxBytes: number): Promise<string> {
  if (typeof path !== 'string' || !isAbsolute(path)) throw new HostError('invalid_config');
  const canonical = await realpath(path);
  const info = await stat(canonical);
  if (!info.isFile() || info.size > maxBytes) throw new HostError('invalid_config');
  return canonical;
}

/** Preparation performs no listeners, OS trust changes, or networking changes. */
export async function prepareConfiguration(input: HostConfiguration, options: { administrationOnly?: boolean; allowInsecureRelayLoopback?: boolean } = {}): Promise<PreparedConfiguration> {
  try {
    const raw = input as LocalHostConfiguration;
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new HostError('invalid_config');
    const hostName = raw.hostName;
    const bind = raw.bind ?? '127.0.0.1';
    const port = raw.port ?? 9443;
    if (typeof hostName !== 'string' || !hostName.trim() || Buffer.byteLength(hostName) > 128 || /[\x00-\x1f\x7f]/.test(hostName)) throw new HostError('invalid_config');
    if (typeof bind !== 'string' || (!isIP(bind) && bind !== 'localhost') || !Number.isInteger(port) || port < 0 || port > 65535) throw new HostError('invalid_config');
    if (raw.allowInsecureLoopback !== undefined && typeof raw.allowInsecureLoopback !== 'boolean') throw new HostError('invalid_config');
    if (!raw.tls && !(raw.allowInsecureLoopback === true && isHttpDevelopmentBind(bind))) throw new HostError('invalid_config');
    if (raw.allowInsecureLoopback && !isHttpDevelopmentBind(bind)) throw new HostError('invalid_config');
    if (typeof raw.statePath !== 'string' || !isAbsolute(raw.statePath)) throw new HostError('invalid_config');
    if (raw.workspaceSource !== undefined && raw.workspaceSource !== 'explicit' && raw.workspaceSource !== 'dsh-registry') throw new HostError('invalid_config');
    const registryMode = raw.workspaceSource === 'dsh-registry';
    if (registryMode ? raw.workspaces !== undefined && (!Array.isArray(raw.workspaces) || raw.workspaces.length !== 0) : !Array.isArray(raw.workspaces) || raw.workspaces.length > 100 || raw.workspaces.length < 1) throw new HostError('invalid_config');
    const ids = new Set<string>(), paths = new Set<string>();
    const workspaces: WorkspaceConfig[] = [];
    for (const workspace of raw.workspaces ?? []) {
      if (!workspace || !validOpaqueId(workspace.id) || ids.has(workspace.id) || typeof workspace.name !== 'string' || !workspace.name.trim() || Buffer.byteLength(workspace.name) > 128 || /[\x00-\x1f\x7f]/.test(workspace.name) || typeof workspace.path !== 'string' || !isAbsolute(workspace.path)) throw new HostError('invalid_config');
      const path = await realpath(workspace.path);
      if (!(await stat(path)).isDirectory() || paths.has(path)) throw new HostError('invalid_config');
      ids.add(workspace.id); paths.add(path); // Exact realpath identity, matching DSH even on case-sensitive Windows directories.
      workspaces.push({ id: workspace.id, name: workspace.name.trim(), path });
    }
    // Refuse state symlinks and canonicalize its parent before creating private state.
    await mkdir(dirname(raw.statePath), { recursive: true, mode: 0o700 });
    const statePath = join(await realpath(dirname(raw.statePath)), basename(raw.statePath));
    try { const info = await lstat(statePath); if (!info.isFile() || info.isSymbolicLink()) throw new HostError('invalid_config'); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
    const config: PreparedLocalHostConfiguration = { hostName: hostName.trim(), bind, port, statePath, workspaces, ...(raw.workspaceSource ? { workspaceSource: raw.workspaceSource } : {}) };
    if (raw.allowInsecureLoopback === true) config.allowInsecureLoopback = true;
    if (raw.publicUrl !== undefined) config.publicUrl = validateBaseUrl(raw.publicUrl, raw.allowInsecureLoopback === true).href.replace(/\/$/, '');
    if (raw.includeCertificatePem !== undefined) {
      if (typeof raw.includeCertificatePem !== 'boolean') throw new HostError('invalid_config');
      config.includeCertificatePem = raw.includeCertificatePem;
    }
    if (raw.relay !== undefined) {
      if (!raw.relay || typeof raw.relay !== 'object' || !/^[a-f0-9]{32}$/.test(raw.relay.routeId) || !/^[A-Za-z0-9_-]{43}$/.test(raw.relay.connectorToken) || bind !== '127.0.0.1' || port < 1 || !raw.tls || raw.allowInsecureLoopback) throw new HostError('invalid_config');
      config.relay = { url: validateRelayUrl(raw.relay.url, options.allowInsecureRelayLoopback === true), routeId: raw.relay.routeId, connectorToken: raw.relay.connectorToken };
      const innerUrl = `https://h-${raw.relay.routeId}.dsh.invalid`;
      if (config.publicUrl && config.publicUrl !== innerUrl) throw new HostError('invalid_config');
      config.publicUrl = innerUrl; config.includeCertificatePem = true;
    }
    if (options.administrationOnly) return { config };
    if (!raw.tls) {
      if (config.publicUrl && new URL(config.publicUrl).protocol !== 'http:') throw new HostError('invalid_config');
      return { config };
    }
    const certPath = await regularFile(raw.tls.certPath, 1_048_576);
    const keyPath = await regularFile(raw.tls.keyPath, 65_536);
    const cert = await readFile(certPath, 'utf8'), key = await readFile(keyPath, 'utf8');
    const leafPem = cert.match(/-----BEGIN CERTIFICATE-----[\s\S]*?-----END CERTIFICATE-----/)?.[0];
    if (!leafPem) throw new HostError('invalid_config');
    const x509 = new X509Certificate(leafPem);
    if (Date.parse(x509.validFrom) > Date.now() || Date.parse(x509.validTo) <= Date.now() || !x509.checkPrivateKey(createPrivateKey(key))) throw new HostError('invalid_config');
    const pinSha256 = 'sha256/' + createHash('sha256').update(x509.publicKey.export({ format: 'der', type: 'spki' })).digest('base64');
    config.tls = { certPath, keyPath };
    if (config.publicUrl) {
      const url = new URL(config.publicUrl);
      if (url.protocol !== 'https:') throw new HostError('invalid_config');
      const host = url.hostname.replace(/^\[|\]$/g, '');
      if (!(isIP(host) ? x509.checkIP(host) : x509.checkHost(host))) throw new HostError('invalid_config');
      if (config.relay && x509.checkHost(host, { wildcards: false, partialWildcards: false, multiLabelWildcards: false, subject: 'never' }) !== host) throw new HostError('invalid_config');
    }
    return { config, tls: { cert, key, minVersion: 'TLSv1.2' }, pinSha256, certificatePem: leafPem + '\n' };
  } catch { throw new HostError('invalid_config'); }
}

export function validateBaseUrl(value: string, allowInsecureLoopback: boolean): URL {
  try {
    if (typeof value !== 'string' || value.length > 2048 || /[\x00-\x20\\]/.test(value)) throw new Error();
    // Reject raw userinfo, even empty query/fragment, and normalized dot paths before URL parsing.
    const syntax = /^(https|http):\/\/(\[[0-9a-fA-F:]+\]|[A-Za-z0-9.-]+)(?::([0-9]{1,5}))?\/?$/.exec(value);
    if (!syntax || (syntax[3] !== undefined && (Number(syntax[3]) < 1 || Number(syntax[3]) > 65535))) throw new Error();
    const url = new URL(value);
    if (url.username || url.password || url.search || url.hash || (url.pathname !== '/' && url.pathname !== '')) throw new Error();
    if (url.protocol === 'https:') return url;
    // Inspect the original hostname too: WHATWG normalization must not permit 127.1/hex aliases.
    const literalAuthority = /^http:\/\/([^/:]+)(?::[0-9]+)?\/?$/.exec(value)?.[1];
    if (url.protocol === 'http:' && allowInsecureLoopback && (literalAuthority === '127.0.0.1' || literalAuthority === 'localhost')) return url;
    throw new Error();
  } catch { throw new HostError('invalid_config'); }
}

export async function loadConfiguration(path: string, options: { administrationOnly?: boolean; allowInsecureRelayLoopback?: boolean } = {}): Promise<PreparedLocalHostConfiguration> {
  try {
    const file = await regularFile(resolve(path), 65_536);
    const raw = JSON.parse(await readFile(file, 'utf8')) as LocalHostConfiguration;
    if (!raw || typeof raw !== 'object') throw new Error();
    const base = dirname(file);
    if (typeof raw.statePath !== 'string' || (raw.workspaces !== undefined && !Array.isArray(raw.workspaces))) throw new Error();
    raw.statePath = resolve(base, raw.statePath);
    if (raw.workspaces) raw.workspaces = raw.workspaces.map(workspace => ({ ...workspace, path: resolve(base, workspace.path) }));
    if (raw.tls) raw.tls = { certPath: resolve(base, raw.tls.certPath), keyPath: resolve(base, raw.tls.keyPath) };
    return (await prepareConfiguration(raw, options)).config;
  } catch { throw new HostError('invalid_config'); }
}

export function pairingInvitation(prepared: PreparedConfiguration, pairingToken: string, baseUrl?: string) {
  const base = validateBaseUrl(baseUrl ?? prepared.config.publicUrl ?? `${prepared.tls ? 'https' : 'http'}://${prepared.config.bind === '::1' ? '[::1]' : prepared.config.bind}:${prepared.config.port}`, prepared.config.allowInsecureLoopback === true);
  if ((base.protocol === 'https:') !== Boolean(prepared.tls)) throw new HostError('invalid_config');
  if (prepared.certificatePem && base.protocol === 'https:') {
    const certificate = new X509Certificate(prepared.certificatePem), host = base.hostname.replace(/^\[|\]$/g, '');
    if (!(isIP(host) ? certificate.checkIP(host) : certificate.checkHost(host))) throw new HostError('invalid_config');
  }
  return {
    version: 1,
    baseUrl: base.href.replace(/\/$/, ''),
    pairingToken,
    ...(prepared.pinSha256 ? { pinSha256: prepared.pinSha256 } : {}),
    ...(prepared.config.includeCertificatePem && prepared.certificatePem ? { certificatePem: prepared.certificatePem } : {}),
  };
}
