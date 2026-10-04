import { execFileSync, spawnSync } from 'node:child_process';
import { X509Certificate } from 'node:crypto';
import { lstat, readFile } from 'node:fs/promises';
import { isIP } from 'node:net';
import { dirname, join } from 'node:path';
import { isCompatibleDshVersion } from './compatibility.ts';
import { prepareConfiguration } from './config.ts';
import type { LocalHostConfiguration } from './config.ts';
import { HostError } from './errors.ts';
import { assertPrivate, privateDirectory, privatePath, privateRead, privateWrite, protectNewFile } from './private-files.ts';

export function declaredDshVersion(value = 'auto'): string {
  let version = value;
  if (value === 'auto') {
    try {
      const output = process.platform === 'win32'
        ? execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-Command', "$ErrorActionPreference='Stop'; $global:LASTEXITCODE=0; $dsh=Get-Command dsh -ErrorAction Stop; & $dsh --version; if (-not $? -or $LASTEXITCODE -ne 0) { exit 1 }"], { encoding: 'utf8', timeout: 15000, windowsHide: true, stdio: ['ignore', 'pipe', 'ignore'] })
        : execFileSync('dsh', ['--version'], { encoding: 'utf8', timeout: 15000, stdio: ['ignore', 'pipe', 'ignore'] });
      version = output.trim(); // No range, guessed suffix or runtime method probing.
    } catch { throw new HostError('unsupported_dsh_version'); }
  }
  if (!isCompatibleDshVersion(version)) throw new HostError('unsupported_dsh_version');
  return version;
}
export function directHosts(hosts: readonly string[]): string[] {
  if (!hosts.length || hosts.length > 20) throw new HostError('invalid_request');
  for (const host of hosts) {
    const ip = isIP(host);
    if (!ip && (host.length > 253 || /^\d+(\.\d+)*$/.test(host) || !host.split('.').every(label => /^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/.test(label)))) throw new HostError('invalid_request');
    if (ip === 6 && host.includes('%')) throw new HostError('invalid_request');
  }
  return [...new Set(hosts)];
}
function opensslExecutable(explicit?: string): string {
  const candidates = explicit ? [explicit] : ['openssl', ...(process.platform === 'win32' && process.env.ProgramFiles ? [join(process.env.ProgramFiles, 'Git', 'mingw64', 'bin', 'openssl.exe'), join(process.env.ProgramFiles, 'OpenSSL-Win64', 'bin', 'openssl.exe')] : [])];
  for (const executable of candidates) {
    const result = spawnSync(executable, ['version'], { encoding: 'utf8', windowsHide: true, timeout: 10000 });
    if (result.status === 0 && /^OpenSSL 3\./.test(result.stdout)) return executable;
  }
  throw new HostError('openssl_required');
}
export interface DirectSetupOptions { configPath: string; hosts: readonly string[]; port?: number; bind?: string; hostName?: string; dshVersion?: string; openssl?: string }
/** Creates no listener, firewall rule, trust root, DSH session or profile change. */
export async function setupDirect(options: DirectSetupOptions): Promise<{ baseUrl: string; port: number; dshVersion: string; existing: boolean }> {
  const hosts = directHosts(options.hosts), version = declaredDshVersion(options.dshVersion);
  const port = options.port ?? 19445, bind = options.bind ?? '0.0.0.0';
  if (!Number.isInteger(port) || port < 1 || port > 65535 || !isIP(bind)) throw new HostError('invalid_request');
  const configPath = await privatePath(options.configPath), dir = dirname(configPath);
  const authority = isIP(hosts[0]!) === 6 ? `[${hosts[0]}]` : hosts[0];
  const baseUrl = `https://${authority}:${port}`;
  const result = { baseUrl, port, dshVersion: version };
  let exists = false;
  try { await lstat(configPath); exists = true; } catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  if (exists) {
    const raw = JSON.parse(await privateRead(configPath)) as LocalHostConfiguration;
    if (raw.dshVersion !== version || raw.workspaceSource !== 'dsh-registry' || raw.relay || raw.publicUrl !== baseUrl || raw.bind !== bind || raw.port !== port || !raw.includeCertificatePem || !raw.tls || raw.statePath !== join(dir, 'state', 'host.sqlite') || raw.tls.certPath !== join(dir, 'tls-cert.pem') || raw.tls.keyPath !== join(dir, 'tls-key.pem') || (options.hostName !== undefined && raw.hostName !== options.hostName.trim())) throw new HostError('invalid_config');
    await assertPrivate(raw.tls.keyPath); await assertPrivate(raw.tls.certPath);
    const prepared = await prepareConfiguration(raw), cert = new X509Certificate(prepared.certificatePem!);
    if (cert.ca || !cert.verify(cert.publicKey) || cert.publicKey.asymmetricKeyType !== 'ec' || cert.publicKey.asymmetricKeyDetails?.namedCurve !== 'prime256v1' || !cert.keyUsage?.includes('1.3.6.1.5.5.7.3.1') || hosts.some(host => !(isIP(host) ? cert.checkIP(host) : cert.checkHost(host, { subject: 'never', wildcards: false })))) throw new HostError('invalid_config');
    await assertPrivate(join(dir, 'state')); await assertPrivate(join(dir, 'invitations'));
    return { ...result, existing: true };
  }
  // Never regenerate over partial state; preserve failures for explicit owner recovery.
  for (const name of ['tls-key.pem', 'tls-cert.pem', 'state', 'invitations']) {
    try { await lstat(join(dir, name)); throw new HostError('invalid_config'); }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
  }
  const openssl = opensslExecutable(options.openssl);
  await privateDirectory(dir); await privateDirectory(join(dir, 'state')); await privateDirectory(join(dir, 'invitations'));
  const certPath = join(dir, 'tls-cert.pem'), keyPath = join(dir, 'tls-key.pem');
  // Node 24 X509Certificate is read/verify-only. Use vetted OpenSSL, never custom ASN.1.
  const generated = spawnSync(openssl, ['req', '-x509', '-newkey', 'ec', '-pkeyopt', 'ec_paramgen_curve:P-256', '-sha256', '-nodes', '-days', '825', '-keyout', keyPath, '-out', certPath, '-subj', '/CN=DSH Mobile companion', '-addext', `subjectAltName=${hosts.map(host => `${isIP(host) ? 'IP' : 'DNS'}:${host}`).join(',')}`, '-addext', 'basicConstraints=critical,CA:FALSE', '-addext', 'keyUsage=critical,digitalSignature', '-addext', 'extendedKeyUsage=serverAuth'], { stdio: 'ignore', windowsHide: true, timeout: 30000 });
  if (generated.status !== 0) throw new HostError('invalid_config');
  await protectNewFile(certPath); await protectNewFile(keyPath);
  await assertPrivate(certPath); await assertPrivate(keyPath);
  const raw: LocalHostConfiguration = { dshVersion: version, hostName: options.hostName ?? 'DSH Mobile companion', workspaceSource: 'dsh-registry', bind, port, statePath: join(dir, 'state', 'host.sqlite'), tls: { certPath, keyPath }, publicUrl: baseUrl, includeCertificatePem: true };
  const prepared = await prepareConfiguration(raw), cert = new X509Certificate(await readFile(certPath, 'utf8'));
  if (cert.ca || !cert.verify(cert.publicKey) || !cert.keyUsage?.includes('1.3.6.1.5.5.7.3.1')) throw new HostError('invalid_config');
  await privateWrite(configPath, JSON.stringify({ ...prepared.config, dshVersion: version, workspaces: undefined }, null, 2) + '\n');
  return { ...result, existing: false };
}
