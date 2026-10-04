# Synthetic DSH_HOME only. Exercises real archive/CLI/ACL/profile updates; fake dsh reports a verified version.
[CmdletBinding()]
param([string]$CacheDirectory)
$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest
if (-not $CacheDirectory) {
    if (-not $env:DSH_HOME) { throw 'Supply -CacheDirectory, or DSH_HOME for a sibling cache. No live profile is used.' }
    $CacheDirectory=Join-Path $env:DSH_HOME '../cache/dsh-mobile/install-tests'
}
$cache=[IO.Path]::GetFullPath($CacheDirectory)
$root=Join-Path $cache ([guid]::NewGuid().ToString('N'))
$null=New-Item -ItemType Directory -Path $root -Force
$originalHome=$env:DSH_HOME; $originalLocal=$env:LOCALAPPDATA
$installer=Join-Path $PSScriptRoot 'install-host.ps1'; $packager=Join-Path $PSScriptRoot 'package-host.ps1'
$utf8=[Text.UTF8Encoding]::new($false)
$checks=0; $watcher=$null
function Check([bool]$Condition,[string]$Name) { if (-not $Condition) { throw ('FAIL: ' + $Name) }; $script:checks++; Write-Host ('PASS: ' + $Name) }
function Invoke-Installer([hashtable]$Extra=@{}) {
    $args=@{ZipPath=$zipA;Hosts=@('computer.example','127.0.0.1');Port=$port;DshCommand=$fake;WaitSeconds=1}
    foreach ($key in $Extra.Keys) { $args[$key]=$Extra[$key] }
    & $installer @args
}
function Fails([scriptblock]$Operation,[string]$Name) {
    $failed=$false
    try { & $Operation | Out-Null } catch { $failed=$true }
    Check $failed $Name
}
function Patch { if (Test-Path -LiteralPath $patch) { return $utf8.GetString([IO.File]::ReadAllBytes($patch)) }; return '' }
try {
    $env:DSH_HOME=Join-Path $root 'synthetic-dsh-home'; $env:LOCALAPPDATA=Join-Path $root 'synthetic-local'
    $profile=Join-Path $env:DSH_HOME 'profiles/web'; $null=New-Item -ItemType Directory -Path $profile -Force
    [IO.File]::WriteAllText((Join-Path $profile 'package.json'),'{"name":"synthetic-web-profile","private":true}',$utf8)
    $patch=Join-Path $profile 'cordis.patch.yml'
    $unrelated=[string][char]0xfeff + "# Synthetic UTF-8-BOM/CRLF profile, never the user's profile.`r`n- id: unrelated-synthetic-plugin`r`n  disabled: !!js true`r`n"
    [IO.File]::WriteAllText($patch,$unrelated,$utf8)
    $fake=Join-Path $root 'dsh-fake.cmd'; [IO.File]::WriteAllText($fake,"@echo off`r`necho 0.2.1-alpha.1`r`n",[Text.Encoding]::ASCII)
    # Reserve an ephemeral synthetic port, never the live 3080/19445 ports.
    $probe=[Net.Sockets.TcpListener]::new([Net.IPAddress]::Loopback,0); $probe.Start(); $port=$probe.LocalEndpoint.Port; $probe.Stop()
    Check ($port -notin @(3080,3081,19445)) 'synthetic port is not a live reserved port'
    $packagesA=Join-Path $root 'packages-a'; $packagesB=Join-Path $root 'packages-b'
    & $packager -OutputDirectory $packagesA | Out-Host
    & $packager -OutputDirectory $packagesB | Out-Host
    $zipA=(Get-ChildItem -LiteralPath $packagesA -Filter *.zip | Select-Object -First 1).FullName
    $zipB=(Get-ChildItem -LiteralPath $packagesB -Filter *.zip | Select-Object -First 1).FullName
    Check ((Get-FileHash -LiteralPath $zipA).Hash -ceq (Get-FileHash -LiteralPath $zipB).Hash) 'two builds produce byte-identical release zip'
    Invoke-Installer @{WhatIf=$true;Activate=$true} | Out-Host
    Check ((Patch) -ceq $unrelated -and -not (Test-Path -LiteralPath (Join-Path $env:LOCALAPPDATA 'DSHMobile'))) 'WhatIf makes no plugin/config/profile writes'
    Invoke-Installer | Out-Host
    $private=Join-Path $env:LOCALAPPDATA 'DSHMobile/host'; $config=Join-Path $private 'host.json'
    $settings=[IO.File]::ReadAllText($config) | ConvertFrom-Json
    Check ($settings.workspaceSource -ceq 'dsh-registry' -and $settings.dshVersion -ceq '0.2.1-alpha.1' -and $settings.port -eq $port) 'preflight writes explicit verified version and registry direct config'
    Check ((Patch) -ceq $unrelated) 'prepare does not activate profile'
    $installedDirectory=Join-Path $env:LOCALAPPDATA ('DSHMobile/plugin/' + (Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'DSHMobile/plugin') -Directory | Select-Object -First 1).Name)
    $smokeScript=@'
const {pathToFileURL}=require('node:url'),fs=require('node:fs/promises'),path=require('node:path'),assert=require('node:assert/strict'),zlib=require('node:zlib');
(async()=>{const [dir,config]=process.argv.slice(1);for(const name of ['plugin','relay-connector','profile-patch','qr'])await import(pathToFileURL(path.join(dir,'dist',name+'.js')));
 const {runAdminCli}=await import(pathToFileURL(path.join(dir,'dist/cli.js'))),{encodeInvitationQr}=await import(pathToFileURL(path.join(dir,'dist/qr.js')));
 const output=path.join(path.dirname(config),'invitations','bundle-smoke.json');let display='',error='';const code=await runAdminCli(['pair','--config',config,'--read','all','--ttl','30','--qr','--output',output],{out:t=>display+=t,error:t=>error+=t});assert.equal(code,0,error);
 const invite=JSON.parse(await fs.readFile(output,'utf8'));assert.equal(display.includes(invite.pairingToken),false);const encoded=encodeInvitationQr(invite);assert.deepEqual(JSON.parse(zlib.inflateSync(Buffer.from(encoded.slice(6),'base64url'))),invite);console.log('Standalone bundle import and QR envelope verified; secrets not logged.')})().catch(()=>{console.error('Standalone bundle smoke failed (details redacted)');process.exitCode=1});
'@
    & node -e $smokeScript $installedDirectory $config | Out-Host
    Check ($LASTEXITCODE -eq 0) 'standalone release imports plugin/ws/YAML/QR and pairs without npm/source dependencies'
    Fails { Invoke-Installer @{Upgrade=$true} } 'upgrade without explicit Activate never mutates profile'
    $keyPath=Join-Path $private 'tls-key.pem'; $keyHash=(Get-FileHash -LiteralPath $keyPath).Hash
    $acl=Get-Acl -LiteralPath $keyPath; $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    $rules=@($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    Check ($acl.AreAccessRulesProtected -and $rules.Count -eq 1 -and $rules[0].IdentityReference.Value -ceq $sid.Value) 'TLS key has protected owner-only Windows ACL'
    Invoke-Installer | Out-Host
    Check ((Get-FileHash -LiteralPath $keyPath).Hash -ceq $keyHash) 'repeated install preserves identity and immutable release'
    # Semantic duplicate detection must work for every valid YAML spelling, not text matching.
    foreach ($case in @(
        @{Name='flow style';Text="- insert: [{id: dsh-mobile-companion, name: other-plugin}]`n"},
        @{Name='quoted key';Text="- insert:`n    - 'id': dsh-mobile-companion`n      name: other-plugin`n"},
        @{Name='anchors and aliases';Text="- insert: [&existing {'id': dsh-mobile-companion, name: other-plugin}, *existing]`n"}
    )) {
        $originalPatch=$unrelated + $case.Text
        [IO.File]::WriteAllText($patch,$originalPatch,$utf8)
        $failure=''
        try { Invoke-Installer @{Activate=$true} | Out-Null } catch { $failure=$_.Exception.Message }
        Check ($failure -like '*Unmarked companion insertion found*') ('unmarked companion rejected semantically: ' + $case.Name)
        Check ((Patch) -ceq $originalPatch) ('unmarked profile bytes unchanged: ' + $case.Name)
    }
    $commentPatch=$unrelated + "# 'id': dsh-mobile-companion`n# - insert: [{id: dsh-mobile-companion, name: comment-only}]`n"
    [IO.File]::WriteAllText($patch,$commentPatch,$utf8)
    Invoke-Installer | Out-Host
    Check ((Patch) -ceq $commentPatch) 'comment resembling companion id is inert and permitted'
    [IO.File]::WriteAllText($patch,$unrelated,$utf8)
    # The synthetic watcher only toggles a synthetic numeric-loopback TCP listener based on markers.
    $watchFile=Join-Path $root 'watcher.cjs'
    [IO.File]::WriteAllText($watchFile,@'
const fs=require('node:fs'), net=require('node:net');
const [patch,port,ready]=process.argv.slice(2); let server, busy=false;
setInterval(()=>{ if(busy)return; const text=fs.readFileSync(patch,'utf8'); const active=text.includes('id: dsh-mobile-companion') && text.includes('name: "file:');
 if(active&&!server){busy=true;server=net.createServer(s=>s.destroy());server.listen(Number(port),'127.0.0.1',()=>{busy=false;fs.writeFileSync(ready,'ready')});}
 if(!active&&server){busy=true;server.close(()=>{server=undefined;busy=false})}
},50);
'@,$utf8)
    $ready=Join-Path $root 'ready.txt'; $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command node).Source; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.Arguments='"' + $watchFile + '" "' + $patch + '" ' + $port + ' "' + $ready + '"'
    $watcher=[Diagnostics.Process]::new(); $watcher.StartInfo=$info; $null=$watcher.Start()
    Invoke-Installer @{Activate=$true;WaitSeconds=5} | Out-Host
    $activePatch=Patch
    Check ($activePatch.StartsWith($unrelated) -and $activePatch.Contains('# DSH-MOBILE-COMPANION-BEGIN v1 id=dsh-mobile-companion') -and (Test-Path -LiteralPath $ready)) 'activation preserves unrelated patch and waits for synthetic HMR listener'
    $backups=@(Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'DSHMobile/backups') -Filter *.yml)
    Check ($backups.Count -gt 0 -and $utf8.GetString([IO.File]::ReadAllBytes($backups[0].FullName)) -ceq $unrelated) 'profile backup and unrelated UTF-8-BOM/CRLF bytes are exact'
    Invoke-Installer @{Activate=$true;WaitSeconds=5} | Out-Host
    Check ((Patch) -ceq $activePatch) 'repeat activation is byte-stable, no duplicate insertion'
    # Make a second synthetic release from the real payload with a bumped test version and recomputed inventory.
    $staging=Join-Path $root 'synthetic-upgrade'; [IO.Compression.ZipFile]::ExtractToDirectory($zipA,$staging)
    $manifestPath=Join-Path $staging 'package.json'; $manifest=[IO.File]::ReadAllText($manifestPath) | ConvertFrom-Json
    $manifest.version='0.1.1-test'; [IO.File]::WriteAllText($manifestPath,($manifest | ConvertTo-Json -Depth 5),$utf8)
    $inventoryPath=Join-Path $staging 'dsh-mobile-host-release.json'; $inventory=[IO.File]::ReadAllText($inventoryPath) | ConvertFrom-Json
    $inventory.version='0.1.1-test'
    foreach ($row in @($inventory.files.PSObject.Properties)) { $inventory.files.($row.Name)=(Get-FileHash -LiteralPath (Join-Path $staging $row.Name)).Hash.ToLowerInvariant() }
    [IO.File]::WriteAllText($inventoryPath,($inventory | ConvertTo-Json -Depth 5),$utf8)
    $upgradeZip=Join-Path $root 'dsh-mobile-host-0.1.1-test.zip'
    $archiveStream=[IO.File]::Open($upgradeZip,[IO.FileMode]::CreateNew)
    $archive=[IO.Compression.ZipArchive]::new($archiveStream,[IO.Compression.ZipArchiveMode]::Create)
    try {
        foreach ($file in Get-ChildItem -LiteralPath $staging -Recurse -File) {
            $name=$file.FullName.Substring($staging.Length+1).Replace('\','/')
            $entry=$archive.CreateEntry($name); $target=$entry.Open(); $source=[IO.File]::OpenRead($file.FullName)
            try { $source.CopyTo($target) } finally { $source.Dispose(); $target.Dispose() }
        }
    } finally { $archive.Dispose(); $archiveStream.Dispose() }
    Invoke-Installer @{ZipPath=$upgradeZip;Upgrade=$true;Activate=$true;WaitSeconds=5} | Out-Host
    Check ((Patch).Contains('/0.1.1-test/dist/plugin.js') -and (Get-FileHash -LiteralPath $keyPath).Hash -ceq $keyHash) 'upgrade unmounts/closes/remounts new URL without TLS/config rotation'
    Invoke-Installer @{Rollback=$true;Activate=$true;WaitSeconds=5} | Out-Host
    Check (-not (Patch).Contains('/0.1.1-test/dist/plugin.js')) 'rollback remounts previous immutable directory'
    Invoke-Installer @{Uninstall=$true;Activate=$true;WaitSeconds=5} | Out-Host
    Check ((Patch).StartsWith($unrelated) -and -not (Patch).Contains('name: "file:') -and (Test-Path -LiteralPath $config)) 'uninstall preserves unrelated profile and private credentials'
    Invoke-Installer @{Uninstall=$true;Activate=$true;WaitSeconds=5} | Out-Host
    Check ((Get-FileHash -LiteralPath $keyPath).Hash -ceq $keyHash) 'uninstall is idempotent and does not erase credentials'
    $badFake=Join-Path $root 'bad-dsh.cmd'; [IO.File]::WriteAllText($badFake,"@echo off`r`necho 9.0.0`r`n",[Text.Encoding]::ASCII)
    Fails { Invoke-Installer @{DshCommand=$badFake;WhatIf=$true} } 'unsupported fake DSH version fails preflight'
    [IO.File]::WriteAllText($patch,$unrelated + "# DSH-MOBILE-COMPANION-BEGIN v1 id=dsh-mobile-companion`n",$utf8)
    Fails { Invoke-Installer @{Activate=$true} } 'partial markers fail closed before profile mutation'
    [IO.File]::WriteAllText($patch,$unrelated + "- broken: [`n",$utf8)
    $badPatch=Patch
    Fails { Invoke-Installer @{Activate=$true} } 'invalid YAML is rejected before activation'
    Check ((Patch) -ceq $badPatch) 'invalid YAML preserves original profile bytes'
    $tampered=Join-Path $env:LOCALAPPDATA ('DSHMobile/plugin/' + (Get-ChildItem -LiteralPath (Join-Path $env:LOCALAPPDATA 'DSHMobile/plugin') -Directory | Where-Object { $_.Name -ne '0.1.1-test' } | Select-Object -First 1).Name + '/dist/plugin.js')
    [IO.File]::AppendAllText($tampered,"`n// synthetic corruption",$utf8)
    Fails { Invoke-Installer } 'immutable directory corruption is not overwritten'
    Write-Host ("Installer synthetic tests: $checks passed. No real DSH profile, runtime, port or state accessed.")
} finally {
    if ($watcher) { if (-not $watcher.HasExited) { $watcher.Kill(); $watcher.WaitForExit() }; $watcher.Dispose() }
    $env:DSH_HOME=$originalHome; $env:LOCALAPPDATA=$originalLocal
    # Preserve private synthetic evidence in the cache for inspection, never Git.
}
