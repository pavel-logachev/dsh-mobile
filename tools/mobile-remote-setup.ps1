# Owner-local remote companion preparation only: no active profile, servers, network or OS trust changes.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$RouteCredentialsPath,
    [Parameter(Mandatory)][string]$RelayUrl,
    [string]$WorkspacesPath,
    [ValidateSet('0.2.0-rc.2','0.2.1-alpha.1')][string]$DshVersion = '0.2.1-alpha.1',
    [string]$HostName = 'DSH Mobile private host',
    [string]$OpenSslPath,
    [switch]$Initialize,
    [switch]$VerifyOnly
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA required for persistent owner-local state.' }
$private = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'DSHMobile/host'))
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
function Assert-LocalPath([string]$Path) {
    if (-not $Path -or $Path -notmatch '^[A-Za-z]:[\\/]' -or $Path.Substring(2).Contains(':')) {
        throw 'Only absolute local paths are permitted; no relative, network, or alternate-stream paths.'
    }
    $full = [IO.Path]::GetFullPath($Path)
    $walk = $full
    while ($walk) {
        if ((Test-Path -LiteralPath $walk) -and
            ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Reparse or redirected paths are not permitted.'
        }
        $parent = [IO.DirectoryInfo]::new($walk).Parent
        $walk = if ($parent) { $parent.FullName } else { $null }
    }
    return $full
}
function Set-PrivateAcl([string]$Path, [bool]$Directory) {
    if ($Directory) {
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    } else {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
    }
    $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true, $false); $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Assert-PrivateAcl([string]$Path, [bool]$RequireProtected = $true) {
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value -or
        ($RequireProtected -and -not $acl.AreAccessRulesProtected) -or $rules.Count -ne 1 -or
        $rules[0].IdentityReference.Value -cne $sid.Value -or
        $rules[0].AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
        $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
        throw 'Private owner-only ACL required. Existing permissions are never repaired automatically.'
    }
}
function Read-PrivateJson([string]$Path, [int]$Limit) {
    $full = Assert-LocalPath $Path
    if (-not (Test-Path -LiteralPath $full -PathType Leaf)) { throw 'Required private input file missing.' }
    Assert-PrivateAcl $full
    Assert-PrivateAcl ([IO.DirectoryInfo]::new($full).Parent.FullName)
    if ((Get-Item -LiteralPath $full).Length -gt $Limit) { throw 'Private input exceeds bounded size.' }
    try { return ([IO.File]::ReadAllText($full) | ConvertFrom-Json) }
    catch { throw 'Invalid private JSON input (details redacted).' }
}
function Assert-Fields($Value, [string[]]$Names) {
    if ($null -eq $Value -or @($Value.PSObject.Properties).Count -ne $Names.Count -or
        @($Value.PSObject.Properties | Where-Object { $_.Name -cnotin $Names }).Count -ne 0) {
        throw 'Unexpected private configuration fields (details redacted).'
    }
}
function Private-Write([string]$Path, [string]$Text) {
    $stream = [IO.FileStream]::new($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try {
        $bytes = [Text.UTF8Encoding]::new($false).GetBytes($Text)
        $stream.Write($bytes, 0, $bytes.Length)
    } finally { $stream.Dispose() }
    Set-PrivateAcl $Path $false
}
function Hash-Text([string]$Text) {
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash([Text.UTF8Encoding]::new($false).GetBytes($Text)))).Replace('-', '') }
    finally { $sha.Dispose() }
}
$private = Assert-LocalPath $private
if ($private.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Persistent private host state must be outside the project/Git.'
}
$files = @('tls-cert.pem','tls-key.pem','identity.json','host.json','cordis.additive.patch.yml','UNDO.md','setup-receipt.json')
$directories = @('state','invitations')
$exists = Test-Path -LiteralPath $private
if ($exists) {
    if (-not (Test-Path -LiteralPath $private -PathType Container)) { throw 'Partial or unexpected host setup state.' }
    Assert-PrivateAcl $private
    $children = @(Get-ChildItem -LiteralPath $private -Force)
    if ($children.Count -ne ($files.Count + $directories.Count)) { throw 'Partial or unexpected host setup state. Preserve it; never generate a replacement identity.' }
    foreach ($child in $children) {
        $null = Assert-LocalPath $child.FullName
        if (($child.PSIsContainer -and $child.Name -cnotin $directories) -or
            (-not $child.PSIsContainer -and $child.Name -cnotin $files)) { throw 'Partial or unexpected host setup state.' }
        Assert-PrivateAcl $child.FullName
    }
    foreach ($directory in $directories) {
        foreach ($child in @(Get-ChildItem -LiteralPath (Join-Path $private $directory) -Force)) {
            $null = Assert-LocalPath $child.FullName
            if ($child.PSIsContainer -or
                ($directory -eq 'state' -and $child.Name -cnotin @('mobile.sqlite','mobile.sqlite-wal','mobile.sqlite-shm','mobile.sqlite-journal')) -or
                ($directory -eq 'invitations' -and $child.Name -notmatch '^[A-Za-z0-9_-]+\.json$')) {
                throw 'Unexpected or redirected runtime state file.'
            }
            Assert-PrivateAcl $child.FullName $false
        }
    }
    if ($Initialize) { throw 'Host setup already exists. Omit -Initialize; never regenerate its TLS identity.' }
} elseif (-not $Initialize) {
    throw 'Host setup state missing. Use -Initialize once; never replace an existing TLS identity automatically.'
}
if ($Initialize -and $VerifyOnly) { throw 'VerifyOnly cannot initialize state.' }

# Route secrets are read only from an owner-private file, never accepted as argument values.
$route = Read-PrivateJson $RouteCredentialsPath 4096
Assert-Fields $route @('routeId','connectorToken')
if ($route.routeId -isnot [string] -or $route.routeId -cnotmatch '^[0-9a-f]{32}$' -or
    $route.connectorToken -isnot [string] -or $route.connectorToken -cnotmatch '^[A-Za-z0-9_-]{43}$') {
    throw 'Invalid route credentials (details redacted).'
}
try {
    $tokenBytes = [Convert]::FromBase64String($route.connectorToken.Replace('-', '+').Replace('_', '/') + '=')
    $canonicalToken = [Convert]::ToBase64String($tokenBytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    if ($tokenBytes.Length -ne 32 -or $canonicalToken -cne $route.connectorToken) { throw 'invalid' }
} catch { throw 'Invalid route credentials (details redacted).' }
finally { if ($tokenBytes) { [Array]::Clear($tokenBytes, 0, $tokenBytes.Length) } }
# Match host validateRelayUrl limits: <=1024 chars, at most four <=64-char opaque path segments.
$relaySyntax = [regex]::Match($RelayUrl, '^wss://([a-z0-9](?:[a-z0-9.-]{0,251}[a-z0-9])?)(?::([1-9][0-9]{0,4}))?((?:/[A-Za-z0-9_-]{1,64}){0,4})$')
if ($RelayUrl.Length -gt 1024 -or -not $relaySyntax.Success -or ($relaySyntax.Groups[2].Success -and [int]$relaySyntax.Groups[2].Value -gt 65535) -or
    @($relaySyntax.Groups[1].Value.Split('.') | Where-Object { $_ -notmatch '^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$' }).Count -gt 0) {
    throw 'Relay URL must be canonical production WSS without credentials/query/fragment/escaped or dot segments.'
}
if (-not $HostName.Trim() -or [Text.Encoding]::UTF8.GetByteCount($HostName) -gt 128 -or $HostName -match '[\x00-\x1f\x7f]') {
    throw 'Invalid host display name.'
}
# Registry is the product path. A supplied file is the deliberate advanced subset mode.
$registryMode = -not $WorkspacesPath
$workspaceInput = @()
if ($WorkspacesPath) {
    $workspaceInput = @(Read-PrivateJson $WorkspacesPath 65536)
    if ($workspaceInput.Count -lt 1 -or $workspaceInput.Count -gt 100) { throw 'Choose one to 100 explicit workspaces.' }
}
$workspaces = @()
$ids = @(); $paths = @()
foreach ($entry in $workspaceInput) {
    Assert-Fields $entry @('id','name','path')
    if ($entry.id -isnot [string] -or $entry.id -cnotmatch '^[A-Za-z0-9_-]{1,64}$' -or $entry.id -cin $ids -or
        $entry.name -isnot [string] -or -not $entry.name.Trim() -or [Text.Encoding]::UTF8.GetByteCount($entry.name) -gt 128 -or $entry.name -match '[\x00-\x1f\x7f]') {
        throw 'Invalid or duplicate selected workspace.'
    }
    $path = Assert-LocalPath $entry.path
    if (-not (Test-Path -LiteralPath $path -PathType Container) -or $path -in $paths) { throw 'Workspace must be a unique existing local directory.' }
    $ids += $entry.id; $paths += $path
    $workspaces += [ordered]@{id=$entry.id;name=$entry.name.Trim();path=$path}
}
$authority = 'h-' + $route.routeId + '.dsh.invalid'
$certPath = Join-Path $private 'tls-cert.pem'
$keyPath = Join-Path $private 'tls-key.pem'
$configPath = Join-Path $private 'host.json'
$statePath = Join-Path $private 'state/mobile.sqlite'
$candidate = [ordered]@{dshVersion=$DshVersion;hostName=$HostName.Trim();bind='127.0.0.1';port=19445;statePath=$statePath;
    workspaceSource=$(if ($registryMode) {'dsh-registry'} else {'explicit'});workspaces=@($workspaces);tls=[ordered]@{certPath=$certPath;keyPath=$keyPath};publicUrl="https://$authority";includeCertificatePem=$true;
    relay=[ordered]@{url=$RelayUrl;routeId=$route.routeId;connectorToken=$route.connectorToken}}
$configText = ($candidate | ConvertTo-Json -Depth 8) + "`n"
$pluginPath = Join-Path $root 'host/dist/plugin.js'
$pluginQuoted = ConvertTo-Json -InputObject $pluginPath -Compress
$configQuoted = ConvertTo-Json -InputObject $configPath -Compress
$patchText = "# Prepared only. Owner must review before enabling one additive insertion.`n- insert:`n    - id: dsh-mobile-companion`n      name: $pluginQuoted`n      disabled: true`n      config:`n        dshVersion: '$DshVersion'`n        configPath: $configQuoted`n"
$undoText = @"
# Owner-reviewed activation and undo

This preparation changes no active DSH profile. Do not start a replacement DSH or copy this snippet automatically.

Before activation: build/test the host and review its strict configPath support; verify the declared DSH version, selected workspace scope and that loopback port 19445 is free. Back up the current active web cordis.patch.yml privately before editing. Enable only the new dsh-mobile-companion insertion from cordis.additive.patch.yml; never replace other inserts/plugins. No connector credential belongs in the active patch.

Undo: remove or disable only the inserted dsh-mobile-companion block. Verify its disposer closes the companion listener/connector without cancelling any DSH agent/session. If live removal is not supported, coordinate with the owner; do not restart DSH without consent. Keep this private directory and mobile.sqlite for explicit later restoration/revocation. Do not delete state, regenerate TLS keys or revert unrelated concurrent profile edits. Use the private pre-activation backup for comparison, not blind full-file restoration.

No firewall, VPN, public raw DSH port, OS trust or APK signing change is required by this snippet.
"@
function Quote-Native([string]$Value) {
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Invoke-PrivateTool([string]$File, [string[]]$Arguments, [string]$Stage) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName=$File; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardOutput=$true; $info.RedirectStandardError=$true
    $info.Arguments = ($Arguments | ForEach-Object { Quote-Native $_ }) -join ' '
    $process = [Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        if (-not $process.Start()) { throw "$Stage failed to start (details redacted)." }
        $stdout=$process.StandardOutput.ReadToEndAsync(); $stderr=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) { $process.Kill(); $process.WaitForExit(); throw "$Stage timed out; preserve partial state." }
        $output=$stdout.GetAwaiter().GetResult(); $null=$stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "$Stage failed (exit $($process.ExitCode); details redacted)." }
        return $output
    } finally { $process.Dispose() }
}
$node = (Get-Command node.exe -ErrorAction Stop).Source
$verifyScript = @'
const fs=require('node:fs'), crypto=require('node:crypto');
try {
  const [certPath,keyPath,host]=process.argv.slice(1);
  const pem=fs.readFileSync(certPath,'utf8'), keyPem=fs.readFileSync(keyPath,'utf8');
  if(pem.length>16384 || keyPem.length>16384 || (pem.match(/-----BEGIN CERTIFICATE-----/g)||[]).length!==1) throw Error();
  const cert=new crypto.X509Certificate(pem), key=crypto.createPrivateKey(keyPem), now=Date.now();
  if(cert.ca || cert.subjectAltName!==`DNS:${host}` || cert.checkHost(host,{subject:'never',wildcards:false})!==host ||
     Date.parse(cert.validFrom)>now || Date.parse(cert.validTo)<=now || !cert.checkPrivateKey(key) || !cert.verify(cert.publicKey) ||
     key.asymmetricKeyType!=='rsa' || key.asymmetricKeyDetails.modulusLength!==3072 ||
     !cert.keyUsage || cert.keyUsage.length!==1 || cert.keyUsage[0]!=='1.3.6.1.5.5.7.3.1') throw Error();
  console.log(JSON.stringify({certificateSha256:crypto.createHash('sha256').update(cert.raw).digest('hex').toUpperCase(),
    pinSha256:'sha256/'+crypto.createHash('sha256').update(cert.publicKey.export({format:'der',type:'spki'})).digest('base64'),validTo:cert.validTo}));
} catch {process.exitCode=1;}
'@
if (-not $exists) {
    $sslCandidates = @($OpenSslPath)
    if (-not $OpenSslPath) {
        $found = Get-Command openssl.exe -ErrorAction SilentlyContinue
        if ($found) { $sslCandidates += $found.Source }
        if ($env:ProgramFiles) {
            $sslCandidates += Join-Path $env:ProgramFiles 'Git/mingw64/bin/openssl.exe'
            $sslCandidates += Join-Path $env:ProgramFiles 'Git/usr/bin/openssl.exe'
            $sslCandidates += Join-Path $env:ProgramFiles 'OpenSSL-Win64/bin/openssl.exe'
        }
    }
    $ssl = $sslCandidates | Where-Object { $_ -and (Test-Path -LiteralPath $_ -PathType Leaf) } | Select-Object -First 1
    if (-not $ssl) { throw 'Installed vetted OpenSSL 3 required; no tool installation is performed.' }
    $ssl = Assert-LocalPath $ssl
    $version = Invoke-PrivateTool $ssl @('version') 'OpenSSL version check'
    if ($version -notmatch '^OpenSSL 3\.') { throw 'Use an installed vetted OpenSSL 3 binary.' }
    $null = New-Item -ItemType Directory -Path $private
    Set-PrivateAcl $private $true
    foreach ($directory in $directories) {
        $path = Join-Path $private $directory
        $null = New-Item -ItemType Directory -Path $path
        Set-PrivateAcl $path $true
    }
    $requestConfig = Join-Path $private 'certificate-request.cnf'
    Private-Write $requestConfig ''
    $null = Invoke-PrivateTool $ssl @('req','-x509','-newkey','rsa:3072','-sha256','-noenc','-days','825',
        '-subj',"/CN=$authority",'-addext',"subjectAltName=DNS:$authority",'-addext','basicConstraints=critical,CA:FALSE',
        '-addext','keyUsage=critical,digitalSignature,keyEncipherment','-addext','extendedKeyUsage=serverAuth',
        '-keyout',$keyPath,'-out',$certPath,'-config',$requestConfig) 'Inner TLS identity generation'
    Set-PrivateAcl $keyPath $false; Set-PrivateAcl $certPath $false
    $certInfo = Invoke-PrivateTool $node @('-e',$verifyScript,$certPath,$keyPath,$authority) 'TLS identity verification' | ConvertFrom-Json
    # Exact fresh temporary request file only; private identity is never removed on failure.
    Remove-Item -LiteralPath $requestConfig
    Private-Write $configPath $configText
    Private-Write (Join-Path $private 'cordis.additive.patch.yml') $patchText
    Private-Write (Join-Path $private 'UNDO.md') $undoText
    $identity = [ordered]@{schema=1;routeId=$route.routeId;authority=$authority;certificateSha256=$certInfo.certificateSha256;
        pinSha256=$certInfo.pinSha256;validTo=$certInfo.validTo;hostConfigSha256=(Hash-Text $configText);
        patchSha256=(Hash-Text $patchText);undoSha256=(Hash-Text $undoText);createdAt=[DateTimeOffset]::UtcNow.ToString('o')}
    Private-Write (Join-Path $private 'identity.json') ($identity | ConvertTo-Json -Depth 4)
    $receipt = [ordered]@{schema=1;configPath=$configPath;certificateSha256=$certInfo.certificateSha256;pinSha256=$certInfo.pinSha256;
        bind='127.0.0.1';port=19445;workspaceIds=@($ids);prepared=$true;activated=$false;connectorReady=$false;invitationIssued=$false}
    Private-Write (Join-Path $private 'setup-receipt.json') ($receipt | ConvertTo-Json -Depth 4)
} else {
    try { $identity = [IO.File]::ReadAllText((Join-Path $private 'identity.json')) | ConvertFrom-Json }
    catch { throw 'Invalid stored host identity (details redacted).' }
    if ($identity.schema -ne 1 -or $identity.routeId -cne $route.routeId -or $identity.authority -cne $authority -or
        $identity.certificateSha256 -cnotmatch '^[A-F0-9]{64}$' -or
        $identity.hostConfigSha256 -cne (Hash-Text $configText) -or
        (Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash -cne $identity.hostConfigSha256 -or
        $identity.patchSha256 -cne (Hash-Text $patchText) -or $identity.undoSha256 -cne (Hash-Text $undoText) -or
        (Get-FileHash -LiteralPath (Join-Path $private 'cordis.additive.patch.yml') -Algorithm SHA256).Hash -cne $identity.patchSha256 -or
        (Get-FileHash -LiteralPath (Join-Path $private 'UNDO.md') -Algorithm SHA256).Hash -cne $identity.undoSha256) {
        throw 'Existing host identity or configuration differs. Refusing automatic replacement, credential rotation or scope expansion.'
    }
    $certInfo = Invoke-PrivateTool $node @('-e',$verifyScript,$certPath,$keyPath,$authority) 'TLS identity verification' | ConvertFrom-Json
    if ($certInfo.certificateSha256 -cne $identity.certificateSha256 -or $certInfo.pinSha256 -cne $identity.pinSha256) {
        throw 'Existing TLS identity differs from the pinned certificate. Restore original identity; never regenerate automatically.'
    }
}
Assert-PrivateAcl $private
foreach ($name in ($files + $directories)) { Assert-PrivateAcl (Join-Path $private $name) }
Write-Host 'Selected workspace scope (filtering, not a filesystem sandbox):'
if ($registryMode) { Write-Host '  All registered DSH projects, including future registrations. Read/execute grants remain separate approvals.' }
foreach ($workspace in $workspaces) { Write-Host ("  {0}: {1} [{2}]" -f $workspace.id,$workspace.name,$workspace.path) }
Write-Host "Private config prepared/verified: $configPath"
Write-Host "Inner TLS certificate SHA-256: $($certInfo.certificateSha256)"
Write-Host 'No active profile/server/network was changed. Activation requires owner review; remote-pair is separate and requires connector ready.'
$route = $null; $candidate = $null; $configText = $null; $canonicalToken = $null
