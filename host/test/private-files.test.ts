import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { mkdtempSync, realpathSync, rmSync } from 'node:fs';
import { readFile, mkdir, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { test } from 'node:test';
import { runAdminCli } from '../src/cli.ts';
import { assertPrivate } from '../src/private-files.ts';

const consent = ['--read', 'all'];
test('private invitation creation uses the Windows PowerShell security module rather than an inherited incompatible module', { skip: process.platform !== 'win32' }, async t => {
  const root = realpathSync.native(mkdtempSync(join(tmpdir(), 'dsh-mobile-private-module-')));
  const original = process.env.PSModulePath;
  t.after(async () => { if (original === undefined) delete process.env.PSModulePath; else process.env.PSModulePath = original; rmSync(root, { recursive: true, force: true }); });
  // Model a PowerShell 7 parent exporting modules incompatible with powershell.exe.
  // Use only a synthetic module inside this fixture; never modify system modules.
  const modules = join(root, 'modules'), shadow = join(modules, 'Microsoft.PowerShell.Security');
  await mkdir(shadow, { recursive: true });
  await writeFile(join(shadow, 'Microsoft.PowerShell.Security.psd1'), "@{ ModuleVersion='99.0.0'; RootModule='incompatible.psm1'; FunctionsToExport=@('Set-Acl','Get-Acl') }\n");
  await writeFile(join(shadow, 'incompatible.psm1'), "throw 'Synthetic incompatible parent module'\n");
  process.env.PSModulePath = modules + (original ? ';' + original : '');
  const configPath = join(root, 'host.json'), output = join(root, 'invitations', 'synthetic.private.json');
  await writeFile(configPath, JSON.stringify({ hostName: 'Synthetic parent shell', workspaceSource: 'dsh-registry', bind: '127.0.0.1', port: 9443, allowInsecureLoopback: true, statePath: join(root, 'state', 'host.sqlite') }));
  let errors = '';
  assert.equal(await runAdminCli(['pair', '--config', configPath, ...consent, '--output', output], { out() {}, error(text) { errors += text; } }), 0, errors);
  assert.ok(JSON.parse(await readFile(output, 'utf8')).pairingToken);
  await assertPrivate(join(root, 'invitations')); await assertPrivate(output);
  // Independently inspect the saved descriptor: no inherited or other-user ACEs.
  const script = "$ErrorActionPreference='Stop'; Import-Module (Join-Path $PSHOME 'Modules\\Microsoft.PowerShell.Security\\Microsoft.PowerShell.Security.psd1'); $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value; $a=Get-Acl -LiteralPath $env:DSHM_TEST_PATH; $rules=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier])); @{ current=$sid; owner=$a.GetOwner([Security.Principal.SecurityIdentifier]).Value; protected=$a.AreAccessRulesProtected; rules=@($rules | ForEach-Object { @{ sid=$_.IdentityReference.Value; rights=[string]$_.FileSystemRights; inherited=$_.IsInherited; type=[string]$_.AccessControlType } }) } | ConvertTo-Json -Depth 5 -Compress";
  const descriptor = JSON.parse(execFileSync('powershell.exe', ['-NoProfile', '-NonInteractive', '-EncodedCommand', Buffer.from(script, 'utf16le').toString('base64')], { encoding: 'utf8', env: { ...process.env, DSHM_TEST_PATH: output } }));
  assert.equal(descriptor.owner, descriptor.current); assert.equal(descriptor.protected, true);
  assert.deepEqual(descriptor.rules, [{ sid: descriptor.current, rights: 'FullControl', inherited: false, type: 'Allow' }]);
  // An existing weak invitation directory must still fail closed, not be repaired.
  execFileSync('icacls.exe', [join(root, 'invitations'), '/grant', '*S-1-1-0:(RX)'], { stdio: 'ignore' });
  await assert.rejects(assertPrivate(join(root, 'invitations')), { code: 'unsafe_private_path' });
  const denied = join(root, 'invitations', 'denied.private.json');
  errors = '';
  assert.equal(await runAdminCli(['pair', '--config', configPath, ...consent, '--output', denied], { out() {}, error(text) { errors += text; } }), 1);
  assert.match(errors, /^unsafe_private_path:/);
  await assert.rejects(readFile(denied), { code: 'ENOENT' });
  await assert.rejects(assertPrivate(join(root, 'invitations')), { code: 'unsafe_private_path' });
});
