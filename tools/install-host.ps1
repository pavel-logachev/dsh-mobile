# Owner-reviewed companion installer. Never restarts DSH or changes firewall/OS trust.
[CmdletBinding(SupportsShouldProcess, ConfirmImpact='Medium')]
param(
    [string]$ZipPath,
    [string[]]$Hosts,
    [ValidateRange(1,65535)][int]$Port = 19445,
    [string]$Bind = '0.0.0.0',
    [string]$ConfigPath,
    [string]$DshHome = $env:DSH_HOME,
    [string]$DshCommand = 'dsh',
    [string]$OpenSslPath,
    [switch]$Activate,
    [switch]$Upgrade,
    [switch]$Uninstall,
    [switch]$Rollback,
    [ValidateRange(1,60)][int]$WaitSeconds = 60
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$begin = '# DSH-MOBILE-COMPANION-BEGIN v1 id=dsh-mobile-companion'
$end = '# DSH-MOBILE-COMPANION-END v1 id=dsh-mobile-companion'
$pattern = '(?m)^' + [regex]::Escape($begin) + '\r?$[\s\S]*?^' + [regex]::Escape($end) + '\r?$'
$utf8 = [Text.UTF8Encoding]::new($false,$true)
function Assert-LocalPath([string]$Path) {
    if (-not $Path -or $Path -notmatch '^[A-Za-z]:[\\/]' -or $Path.Substring(2).Contains(':')) { throw 'Use an absolute local path, not UNC, ADS or a relative path.' }
    $full=[IO.Path]::GetFullPath($Path); $walk=$full
    while ($walk) {
        if ((Test-Path -LiteralPath $walk) -and ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Redirected/reparse ancestors are not allowed.' }
        $parent=[IO.DirectoryInfo]::new($walk).Parent; $walk=if ($parent) { $parent.FullName } else { $null }
    }
    return $full
}
function Assert-OutsideGit([string]$Path) {
    $walk=$Path
    while ($walk) {
        if (Test-Path -LiteralPath (Join-Path $walk '.git')) { throw 'Private companion files must stay outside every Git worktree.' }
        $parent=[IO.DirectoryInfo]::new($walk).Parent; $walk=if ($parent) { $parent.FullName } else { $null }
    }
}
function Private-Directory([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        $null=New-Item -ItemType Directory -Path $Path -Force
        $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl=[Security.AccessControl.DirectorySecurity]::new()
        $acl.SetOwner($sid); $acl.SetAccessRuleProtection($true,$false)
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid,'FullControl','ContainerInherit,ObjectInherit','None','Allow'))
        Set-Acl -LiteralPath $Path -AclObject $acl
    }
    $a=Get-Acl -LiteralPath $Path; $rules=@($a.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    $sid=[Security.Principal.WindowsIdentity]::GetCurrent().User
    if ($a.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value -or $rules.Count -ne 1 -or $rules[0].IdentityReference.Value -cne $sid.Value -or $rules[0].AccessControlType -ne 'Allow' -or $rules[0].FileSystemRights -ne 'FullControl') { throw 'Existing directory needs owner-only ACL; permissions are never repaired silently.' }
}
function Read-Patch {
    if (-not (Test-Path -LiteralPath $patchPath)) { return '' }
    if ((Get-Item -LiteralPath $patchPath).Length -gt 1048576) { throw 'Profile patch too large.' }
    # Strict UTF-8, retaining a UTF-8 BOM as a character so unrelated bytes/backups stay exact.
    # Other encodings fail closed rather than re-encoding the owner's whole profile.
    return $utf8.GetString([IO.File]::ReadAllBytes($patchPath))
}
function Marked-Block([string]$Text,[string]$PluginDirectory) {
    $matches=[regex]::Matches($Text,$pattern)
    if ($matches.Count -gt 1 -or ([regex]::Matches($Text,[regex]::Escape($begin))).Count -ne $matches.Count -or ([regex]::Matches($Text,[regex]::Escape($end))).Count -ne $matches.Count) { throw 'Duplicate or malformed companion markers; no profile changes made.' }
    $outside=[regex]::Replace($Text,$pattern,'')
    # Parse the unmarked region inertly; flow YAML, quoted keys and aliases are semantic IDs.
    if ($PluginDirectory) { Validate-Patch $outside $PluginDirectory -RejectCompanion }
    if ($matches.Count -eq 1) { return $matches[0].Value }
    return $null
}
function Candidate-Patch([string]$Current,[string]$Block,[string]$PluginDirectory) {
    $existing=Marked-Block $Current $PluginDirectory
    if ($existing) { return [regex]::Replace($Current,$pattern,[Text.RegularExpressions.MatchEvaluator]{ param($m) $Block }) }
    # Empty YAML sequence can be safely changed to an additive sequence. Never reinterpret a mapping/JSON profile.
    $document=$Current.TrimStart([char]0xfeff)
    if ($document.Trim() -eq '[]') { $Current=if ($Current.StartsWith([string][char]0xfeff)) { [string][char]0xfeff } else { '' }; $document='' }
    if ($document.Trim() -and $document.TrimStart() -notmatch '^(#|-)') { throw 'Profile patch must be a YAML sequence. Convert JSON/mapping manually first.' }
    $separator=if ($Current -and -not $Current.EndsWith("`n")) { "`n" } else { '' }
    return $Current + $separator + $Block + "`n"
}
function Validate-Patch([string]$Text,[string]$PluginDirectory,[switch]$RejectCompanion) {
    # .NET Framework/PS5.1 stdin writer prepends a transport BOM. Send canonical ASCII base64
    # so it cannot corrupt the profile's own UTF-8 BOM/Unicode; contents never enter arguments.
    $info=[Diagnostics.ProcessStartInfo]::new()
    $info.FileName=(Get-Command node).Source; $info.UseShellExecute=$false; $info.CreateNoWindow=$true
    $info.RedirectStandardInput=$true; $info.Arguments='"' + (Join-Path $PluginDirectory 'dist/profile-patch.js') + '" --base64'
    if ($RejectCompanion) { $info.Arguments += ' --reject-companion' }
    $process=[Diagnostics.Process]::new(); $process.StartInfo=$info
    try {
        if (-not $process.Start()) { throw 'Private YAML validation could not start.' }
        $bytes=[Text.Encoding]::ASCII.GetBytes([Convert]::ToBase64String($utf8.GetBytes($Text))); $process.StandardInput.BaseStream.Write($bytes,0,$bytes.Length); $process.StandardInput.Close()
        if (-not $process.WaitForExit(15000)) { $process.Kill(); $process.WaitForExit(); throw 'Private YAML validation timed out; unchanged.' }
        if ($process.ExitCode -eq 2 -and $RejectCompanion) { throw 'Unmarked companion insertion found; review/migrate it manually.' }
        if ($process.ExitCode -ne 0) { throw 'Proposed profile patch is not a valid additive patch sequence; unchanged.' }
    } finally { $process.Dispose() }
}
function Change-Patch([string]$Expected,[string]$Block,[string]$PluginDirectory) {
    $current=Read-Patch
    if ($current -cne $Expected) { throw 'Concurrent profile change detected; no overwrite.' }
    $new=Candidate-Patch $current $Block $PluginDirectory
    $null=Assert-LocalPath $patchPath
    Validate-Patch $new $PluginDirectory
    if ($new -ceq $current) { return $current }
    Private-Directory $backupDir
    $backup=Join-Path $backupDir ('cordis.patch-' + [guid]::NewGuid().ToString('N') + '.yml')
    [IO.File]::WriteAllText($backup,$current,$utf8)
    if ((Read-Patch) -cne $current) { throw 'Concurrent profile change detected after backup; no overwrite.' }
    # Replace only the reviewed bytes under an exclusive write lock. Concurrent writers cannot interleave.
    $mode=if (Test-Path -LiteralPath $patchPath) { [IO.FileMode]::Open } else { [IO.FileMode]::CreateNew }
    $locked=[IO.File]::Open($patchPath,$mode,[IO.FileAccess]::ReadWrite,[IO.FileShare]::Read)
    try {
        if ($mode -eq [IO.FileMode]::Open) {
            $buffer=[IO.MemoryStream]::new()
            try { $locked.CopyTo($buffer); $lockedText=$utf8.GetString($buffer.ToArray()) } finally { $buffer.Dispose() }
            if ($lockedText -cne $current) { throw 'Concurrent profile change detected under lock; no overwrite.' }
        }
        $bytes=$utf8.GetBytes($new); $locked.Position=0; $locked.Write($bytes,0,$bytes.Length); $locked.SetLength($bytes.Length); $locked.Flush($true)
    } finally { $locked.Dispose() }
    Write-Host 'Profile backed up; only the marked companion block changed.'
    return $new
}
function Verify-ManagedDirectory([string]$Directory) {
    $null=Assert-LocalPath $Directory
    $inventoryPath=Assert-LocalPath (Join-Path $Directory 'dsh-mobile-host-release.json')
    if (-not (Test-Path -LiteralPath $inventoryPath) -or (Get-Item -LiteralPath $inventoryPath).Length -gt 1048576) { throw 'Managed release inventory missing or too large.' }
    $saved=[IO.File]::ReadAllText($inventoryPath) | ConvertFrom-Json
    if ($saved.formatVersion -ne 1 -or $saved.version -cne [IO.Path]::GetFileName($Directory)) { throw 'Invalid managed release inventory.' }
    foreach ($row in @($saved.files.PSObject.Properties)) {
        if ($row.Name -notmatch '^[A-Za-z0-9_.\-/]+$' -or $row.Name -match '(^/|\\|//|(^|/)\.\.?(/|$)|:)' -or $row.Value -notmatch '^[a-f0-9]{64}$') { throw 'Unsafe managed inventory.' }
        $file=Assert-LocalPath (Join-Path $Directory $row.Name)
        if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.Value) { throw 'Managed immutable release was changed; no profile mutation.' }
    }
}
function Listening {
    return @((Get-NetTCPConnection -State Listen -LocalPort $Port -ErrorAction SilentlyContinue)).Count -gt 0
}
function Wait-Port([bool]$Open) {
    $deadline=[DateTime]::UtcNow.AddSeconds($WaitSeconds)
    do {
        if ((Listening) -eq $Open) { return $true }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    return $false
}
function Block-For([string]$Directory) {
    $url=[Uri]::new((Join-Path $Directory 'dist/plugin.js')).AbsoluteUri
    $urlQuoted=ConvertTo-Json -InputObject $url -Compress
    $configQuoted=ConvertTo-Json -InputObject $ConfigPath -Compress
    return "$begin`n- insert:`n    - id: dsh-mobile-companion`n      name: $urlQuoted`n      config:`n        dshVersion: '$version'`n        configPath: $configQuoted`n$end"
}
function Save-Receipt($Value) { [IO.File]::WriteAllText($receiptPath,($Value | ConvertTo-Json -Depth 5),$utf8) }
if (@($Upgrade,$Uninstall,$Rollback | Where-Object { $_ }).Count -gt 1) { throw 'Choose one of install/upgrade/uninstall/rollback.' }
if (($Upgrade -or $Uninstall -or $Rollback) -and -not $Activate -and -not $WhatIfPreference) { throw 'Profile mutation requires the selected action plus -Activate after review.' }
if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is required.' }
$base=Assert-LocalPath (Join-Path $env:LOCALAPPDATA 'DSHMobile')
$pluginBase=Join-Path $base 'plugin'; $backupDir=Join-Path $base 'backups'; $receiptPath=Join-Path $base 'install-receipt.json'
if (-not $ConfigPath) { $ConfigPath=Join-Path $base 'host/host.json' }
$ConfigPath=Assert-LocalPath $ConfigPath
Assert-OutsideGit $base; Assert-OutsideGit ([IO.Path]::GetDirectoryName($ConfigPath))
$DshHome=Assert-LocalPath $DshHome
$profile=Join-Path $DshHome 'profiles/web'
if (-not (Test-Path -LiteralPath $profile -PathType Container) -or -not (Test-Path -LiteralPath (Join-Path $profile 'package.json') -PathType Leaf)) { throw 'DSH_HOME must contain the installed profiles/web/package.json. Nothing will be created there.' }
$patchPath=Assert-LocalPath (Join-Path $profile 'cordis.patch.yml')
if ((& node --version) -notmatch '^v24\.') { throw 'Node 24.x required.' }
$global:LASTEXITCODE=0
$version=((& $DshCommand --version) | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $version -cnotin @('0.2.0-rc.2','0.2.1-alpha.1')) { throw 'Supported DSH versions: 0.2.0-rc.2, 0.2.1-alpha.1. Check dsh --version.' }
$current=Read-Patch; $oldBlock=Marked-Block $current
$placeholder="$begin`n# Unmounted companion; immutable plugin/private credentials retained.`n$end"
$receipt=$null
if (Test-Path -LiteralPath $receiptPath) { $receipt=[IO.File]::ReadAllText($receiptPath) | ConvertFrom-Json }
if ($Uninstall -or $Rollback) {
    if (-not $receipt) { if (-not $oldBlock) { Write-Host 'Companion is not installed; nothing changed.'; return }; throw 'Managed receipt missing; remove the marked block manually after review.' }
    if (-not $PSBoundParameters.ContainsKey('ConfigPath')) { $ConfigPath=Assert-LocalPath $receipt.configPath }
    if (-not $PSBoundParameters.ContainsKey('Port')) { $Port=[int]$receipt.port }
    $activeDir=Assert-LocalPath $receipt.activeDirectory
    if (-not $activeDir.StartsWith($pluginBase + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)) { throw 'Receipt directory is outside the managed immutable plugin root.' }
    Verify-ManagedDirectory $activeDir
    $oldBlock=Marked-Block $current $activeDir
    $targetBlock=$placeholder
    if ($Rollback) {
        if (-not $receipt.previousDirectory) { throw 'No previous managed version to roll back to.' }
        $previous=Assert-LocalPath $receipt.previousDirectory
        if (-not $previous.StartsWith($pluginBase + [IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase) -or -not (Test-Path -LiteralPath (Join-Path $previous 'dist/plugin.js'))) { throw 'Previous version is not available inside the managed root.' }
        Verify-ManagedDirectory $previous
        $targetBlock=Block-For $previous
    }
    if (-not $PSCmdlet.ShouldProcess($patchPath, $(if ($Rollback) {'Unmount, wait for close, and remount previous version'} else {'Unmount only the marked companion insertion; retain private state'}))) { return }
    $current=Change-Patch $current $placeholder $activeDir
    if (-not (Wait-Port $false)) { throw 'Port still listening. Stop here: a DSH restart may be needed, but nothing is restarted.' }
    if ($Rollback) {
        $null=Change-Patch $current $targetBlock $previous
        Save-Receipt ([ordered]@{activeDirectory=$previous;previousDirectory=$activeDir;configPath=$ConfigPath;port=$Port;dshVersion=$version})
        if (-not (Wait-Port $true)) { Write-Warning 'Not listening after the wait. A DSH restart may be needed; no restart was attempted.' }
    } else { Write-Host 'Companion unmounted. Plugin builds, credentials and backups retained; firewall rollback is a separate consented action.' }
    return
}
if (-not $ZipPath) { throw '-ZipPath is required. Verify the release SHA-256 before running this script.' }
$ZipPath=Assert-LocalPath $ZipPath
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip=[IO.Compression.ZipFile]::OpenRead($ZipPath)
try {
    $catalog=@{}; $total=0L
    foreach ($entry in $zip.Entries) {
        $name=$entry.FullName
        if ($name -notmatch '^[A-Za-z0-9_.\-/]+$' -or $name -match '(^/|\\|//|(^|/)\.\.?(/|$)|:|\.(?:/|$)|(?:^|/)(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\.|/|$))' -or $entry.Length -gt 8388608) { throw 'Unsafe archive entry.' }
        if ($name.EndsWith('/')) { continue }
        if ($catalog.ContainsKey($name)) { throw 'Duplicate archive path.' }
        $total += $entry.Length; if ($total -gt 33554432) { throw 'Archive exceeds bounded unpacked size.' }
        $catalog[$name]=$entry
    }
    if (-not $catalog.ContainsKey('dsh-mobile-host-release.json')) { throw 'Release inventory missing.' }
    $reader=[IO.StreamReader]::new($catalog['dsh-mobile-host-release.json'].Open())
    try { $inventory=$reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($inventory.formatVersion -ne 1 -or $inventory.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Invalid release inventory.' }
    $names=@($inventory.files.PSObject.Properties)
    if ($catalog.Count -ne $names.Count + 1) { throw 'Archive has unlisted files.' }
    foreach ($row in $names) {
        if (-not $catalog.ContainsKey($row.Name) -or $row.Value -notmatch '^[a-f0-9]{64}$') { throw 'Archive inventory mismatch.' }
        $entryStream=$catalog[$row.Name].Open(); $sha=[Security.Cryptography.SHA256]::Create()
        try { $hash=([BitConverter]::ToString($sha.ComputeHash($entryStream))).Replace('-','').ToLowerInvariant() } finally { $entryStream.Dispose(); $sha.Dispose() }
        if ($hash -cne $row.Value) { throw 'Archive content hash mismatch.' }
    }
    foreach ($required in @('dist/plugin.js','dist/cli.js','dist/qr.js','dist/profile-patch.js','dist/compatibility.js','package.json','LICENSE','node_modules/ws/package.json')) { if (-not $catalog.ContainsKey($required)) { throw 'Incomplete host release.' } }
    $directory=Join-Path $pluginBase $inventory.version
    $block=Block-For $directory
    Write-Host 'Review the exact additive patch (no credentials):'
    Write-Host $block
    if ($oldBlock -and $oldBlock -cne $placeholder -and $oldBlock -cne $block -and -not $Upgrade) { throw 'Different active companion block exists. Use -Upgrade after review.' }
    if (-not (Test-Path -LiteralPath $ConfigPath) -and -not $Hosts) { throw 'Initial install requires -Hosts with the chosen LAN/Tailscale URL host and additional SANs.' }
    if ($Activate -and (-not $oldBlock -or $oldBlock -ceq $placeholder) -and (Listening)) { throw 'Chosen port already listens; never replace another production service.' }
    if (-not $PSCmdlet.ShouldProcess($directory, 'Verify/unpack immutable plugin and prepare private registry TLS config')) { return }
    Private-Directory $base; Private-Directory $pluginBase
    $exists=Test-Path -LiteralPath $directory
    if ($exists) {
        $existingFiles=@(Get-ChildItem -LiteralPath $directory -Recurse -File)
        if ($existingFiles.Count -ne $catalog.Count) { throw 'Existing versioned directory differs; immutable builds are never overwritten.' }
        $oldInventory=Assert-LocalPath (Join-Path $directory 'dsh-mobile-host-release.json')
        $existingInventoryBytes=[IO.File]::ReadAllBytes($oldInventory)
        $inventorySource=$catalog['dsh-mobile-host-release.json'].Open(); $inventoryBuffer=[IO.MemoryStream]::new()
        try { $inventorySource.CopyTo($inventoryBuffer); $incomingInventoryBytes=$inventoryBuffer.ToArray() } finally { $inventorySource.Dispose(); $inventoryBuffer.Dispose() }
        if ([Convert]::ToBase64String($existingInventoryBytes) -cne [Convert]::ToBase64String($incomingInventoryBytes)) { throw 'Existing release inventory differs; immutable directory is not overwritten.' }
        foreach ($row in $names) {
            $file=Assert-LocalPath (Join-Path $directory $row.Name)
            if (-not (Test-Path -LiteralPath $file -PathType Leaf) -or (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -cne $row.Value) { throw 'Existing versioned directory differs; immutable builds are never overwritten.' }
        }
    } else {
        Private-Directory $directory
        foreach ($name in @($catalog.Keys | Sort-Object)) {
            $target=Assert-LocalPath (Join-Path $directory $name)
            $null=New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName($target)) -Force
            $input=$catalog[$name].Open(); $output=[IO.File]::Open($target,[IO.FileMode]::CreateNew)
            try { $input.CopyTo($output) } finally { $input.Dispose(); $output.Dispose() }
        }
    }
} finally { $zip.Dispose() }
# The verified immutable parser is available now; reject unmarked entries before
# TLS setup or any profile mutation. Marked-block checks above handled malformed markers.
$oldBlock=Marked-Block $current $directory
if (-not (Test-Path -LiteralPath $ConfigPath)) {
    $arguments=@((Join-Path $directory 'dist/cli.js'),'setup-direct','--config',$ConfigPath,'--dsh-version',$version,'--bind',$Bind,'--port',"$Port")
    foreach ($hostValue in $Hosts) { $arguments += @('--host',$hostValue) }
    if ($OpenSslPath) { $arguments += @('--openssl',$OpenSslPath) }
    & node @arguments
    if ($LASTEXITCODE -ne 0) { throw 'Private setup failed. Preserve partial state; never auto-regenerate TLS identity.' }
} else {
    # Existing scope/identity is immutable here. No host.json rewrite during plugin upgrades.
    & node (Join-Path $directory 'dist/cli.js') verify-config --config $ConfigPath
    if ($LASTEXITCODE -ne 0) { throw 'Existing private config failed validation; unchanged.' }
    $config=[IO.File]::ReadAllText($ConfigPath) | ConvertFrom-Json
    if ($config.port -ne $Port -or $config.workspaceSource -cne 'dsh-registry') { throw 'Existing port/scope differs. Review a separate config migration.' }
}
if (-not $Activate -and -not $Upgrade) { Write-Host 'Prepared only. No profile activation; rerun with -Activate after consent.'; return }
if (-not $PSCmdlet.ShouldProcess($patchPath, 'Activate reviewed companion via existing profile HMR, without restart')) { return }
$previousDirectory=if ($receipt) { $receipt.activeDirectory } else { $null }
if ($Upgrade -and $oldBlock -and $oldBlock -cne $placeholder -and $oldBlock -cne $block) {
    $current=Change-Patch $current $placeholder $directory
    if (-not (Wait-Port $false)) { throw 'Port did not close. Leave placeholder and coordinate an approved restart; nothing was restarted or remounted.' }
}
$null=Change-Patch $current $block $directory
Save-Receipt ([ordered]@{activeDirectory=$directory;previousDirectory=$(if ($previousDirectory -and $previousDirectory -cne $directory) {$previousDirectory} elseif ($receipt) {$receipt.previousDirectory} else {$null});configPath=$ConfigPath;port=$Port;dshVersion=$version})
if (Wait-Port $true) { Write-Host 'Companion port is listening. Verify remote-status/devices and then scan a fresh pairing QR.' }
else { Write-Warning 'Not listening after 60 seconds (or the selected shorter wait). A DSH restart may be needed. No restart or process replacement attempted.' }
