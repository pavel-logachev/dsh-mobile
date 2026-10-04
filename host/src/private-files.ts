import { execFileSync } from 'node:child_process';
import { chmod, lstat, mkdir, readFile, realpath, writeFile } from 'node:fs/promises';
import { dirname, isAbsolute, parse, relative, resolve } from 'node:path';
import { HostError } from './errors.ts';

/** Reject redirected ancestors, network paths/ADS and Git before writing secrets. */
export async function privatePath(path: string): Promise<string> {
  const full = resolve(path);
  if (!isAbsolute(path) || (process.platform === 'win32' && (!/^[A-Za-z]:[\\/]/.test(path) || path.slice(2).includes(':')))) throw new HostError('unsafe_private_path');
  let existing: string | undefined;
  for (let walk = full; ; walk = dirname(walk)) {
    try {
      if ((await lstat(walk)).isSymbolicLink()) throw new HostError('unsafe_private_path');
      existing ??= walk;
    }
    catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; }
    try { await lstat(resolve(walk, '.git')); throw new HostError('unsafe_private_path'); }
    catch (error) { if (!['ENOENT', 'ENOTDIR'].includes((error as NodeJS.ErrnoException).code ?? '')) throw error; }
    if (walk === parse(walk).root) break;
  }
  if (!existing) throw new HostError('unsafe_private_path');
  // Only canonicalize after checking every supplied ancestor: following a
  // junction first would hide the redirect. Preserve exact realpath case and
  // append the not-yet-created suffix to the nearest existing ancestor.
  return resolve(await realpath(existing), relative(existing, full));
}
function windowsAcl(path: string, create: boolean, directory: boolean): void {
  // Native ACLs, not chmod: only the current user's SID gets FullControl.
  // Paths are environment data, never PowerShell source interpolation.
  // A pwsh parent can export PSModulePath entries for PowerShell 7, which
  // Windows PowerShell 5.1 cannot load. Pin its own built-in security module;
  // never resolve ACL commands through inherited or user-provided modules.
  const script = `$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1') -ErrorAction Stop; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User; $p=$env:DSHM_PRIVATE_PATH;
if ($env:DSHM_ACL_CREATE -eq '1') {
 if ($env:DSHM_ACL_DIRECTORY -eq '1') { $a=[Security.AccessControl.DirectorySecurity]::new(); $r=[Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow') }
 else { $a=[Security.AccessControl.FileSecurity]::new(); $r=[Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','Allow') }
 $a.SetOwner($sid); $a.SetAccessRuleProtection($true,$false); $a.AddAccessRule($r); Set-Acl -LiteralPath $p -AclObject $a
}
$walk=$p; while ($walk) { if ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint) { throw 'Redirected path' }; $walk=Split-Path -Path $walk -Parent }; 
$a=Get-Acl -LiteralPath $p; $rules=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]));
if ($a.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -cne $sid.Value -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl') { throw 'Unsafe ACL' }`;
  try { execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { env: { ...process.env, DSHM_PRIVATE_PATH: path, DSHM_ACL_CREATE: create ? '1' : '0', DSHM_ACL_DIRECTORY: directory ? '1' : '0' }, stdio: 'ignore', windowsHide: true, timeout: 15000 }); }
  catch { throw new HostError('unsafe_private_path'); }
}
export async function assertPrivate(path: string): Promise<void> {
  const full = await privatePath(path), info = await lstat(full);
  if (process.platform === 'win32') windowsAcl(full, false, info.isDirectory());
  else if ((info.mode & 0o077) !== 0 || (process.getuid && info.uid !== process.getuid())) throw new HostError('unsafe_private_path');
}
export async function privateDirectory(path: string): Promise<void> {
  const full = await privatePath(path);
  let exists = true;
  try { if (!(await lstat(full)).isDirectory()) throw new HostError('unsafe_private_path'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code !== 'ENOENT') throw error; exists = false; }
  if (!exists) {
    await mkdir(full, { recursive: true, mode: 0o700 });
    if (process.platform === 'win32') windowsAcl(full, true, true); else await chmod(full, 0o700);
  }
  await assertPrivate(full);
}
export async function protectNewFile(path: string): Promise<void> {
  if (process.platform === 'win32') windowsAcl(path, true, false); else await chmod(path, 0o600);
}
export async function privateWrite(path: string, text: string): Promise<void> {
  const full = await privatePath(path);
  await assertPrivate(dirname(full));
  await writeFile(full, text, { flag: 'wx', mode: 0o600 });
  if (process.platform === 'win32') windowsAcl(full, true, false);
}
export async function privateRead(path: string): Promise<string> {
  await assertPrivate(dirname(path)); await assertPrivate(path);
  if ((await lstat(path)).size > 65536) throw new HostError('invalid_config');
  return readFile(path, 'utf8');
}
