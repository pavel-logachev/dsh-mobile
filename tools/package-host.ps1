# Reproducible release archive: fixed timestamps, sorted entries, pinned host lockfile.
[CmdletBinding(SupportsShouldProcess)]
param([string]$OutputDirectory)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$hostRoot = Join-Path $root 'host'
if (-not $OutputDirectory) {
    if (-not $env:DSH_HOME) { throw 'Supply -OutputDirectory outside Git, or set DSH_HOME for its sibling cache.' }
    $OutputDirectory = Join-Path $env:DSH_HOME '../cache/dsh-mobile/packages'
}
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
if ($OutputDirectory.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) { throw 'Release output must be outside the repository.' }
if ((& node --version) -notmatch '^v24\.') { throw 'Node 24.x required.' }
$manifest = Get-Content -LiteralPath (Join-Path $hostRoot 'package.json') -Raw | ConvertFrom-Json
if ($manifest.version -notmatch '^\d+\.\d+\.\d+(?:-[A-Za-z0-9.-]+)?$') { throw 'Unsafe package version.' }
$zipPath = Join-Path $OutputDirectory ('dsh-mobile-host-' + $manifest.version + '.zip')
if (-not $PSCmdlet.ShouldProcess($zipPath, 'Build and package immutable host release')) { return }
Push-Location $hostRoot
try { & npm.cmd run build; if ($LASTEXITCODE -ne 0) { throw 'Host build failed.' } } finally { Pop-Location }
# Do not ship source maps, tests, absolute source paths, runtime state, or invitations.
$files = @{}
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $hostRoot 'dist') -File | Where-Object { $_.Extension -in @('.js', '.ts') }) { $files['dist/' + $file.Name] = [IO.File]::ReadAllBytes($file.FullName) }
$files['package.json'] = [Text.UTF8Encoding]::new($false).GetBytes((([ordered]@{name=$manifest.name; version=$manifest.version; type='module'; private=$true; engines=@{node='>=24.0.0 <25'}; dependencies=@{ws='8.22.0'}} | ConvertTo-Json -Depth 6) + "`n"))
$files['LICENSE'] = [IO.File]::ReadAllBytes((Join-Path $root 'LICENSE'))
foreach ($file in Get-ChildItem -LiteralPath (Join-Path $hostRoot 'node_modules/ws') -Recurse -File) {
    $relative = $file.FullName.Substring((Join-Path $hostRoot 'node_modules/ws').Length + 1).Replace('\','/')
    $files['node_modules/ws/' + $relative] = [IO.File]::ReadAllBytes($file.FullName)
}
foreach ($id in @('qrcode','dijkstrajs','pngjs','js-yaml','argparse')) {
    $dir = Join-Path $hostRoot ('node_modules/' + $id)
    $license = Get-ChildItem -LiteralPath $dir -File | Where-Object { $_.Name -match '^(license|LICENSE)(\.txt|\.md)?$' } | Select-Object -First 1
    if (-not $license) { throw 'Bundled dependency license missing.' }
    $files['licenses/' + $id + '.txt'] = [IO.File]::ReadAllBytes($license.FullName)
}
$files['install-host.ps1'] = [IO.File]::ReadAllBytes((Join-Path $PSScriptRoot 'install-host.ps1'))
$sha = [Security.Cryptography.SHA256]::Create()
try {
    $hashes = [ordered]@{}
    foreach ($name in @($files.Keys | Sort-Object -CaseSensitive)) { $hashes[$name] = ([BitConverter]::ToString($sha.ComputeHash($files[$name]))).Replace('-','').ToLowerInvariant() }
} finally { $sha.Dispose() }
$release = [ordered]@{ formatVersion=1; version=$manifest.version; files=$hashes }
$files['dsh-mobile-host-release.json'] = [Text.UTF8Encoding]::new($false).GetBytes(($release | ConvertTo-Json -Depth 6) + "`n")
$null = New-Item -ItemType Directory -Path $OutputDirectory -Force
Add-Type -AssemblyName System.IO.Compression
$stream = [IO.File]::Open($zipPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
try {
    $zip = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        foreach ($name in @($files.Keys | Sort-Object -CaseSensitive)) {
            $entry = $zip.CreateEntry($name, [IO.Compression.CompressionLevel]::Optimal)
            $entry.LastWriteTime = [DateTimeOffset]::new(2000,1,1,0,0,0,[TimeSpan]::Zero)
            $target = $entry.Open()
            try { $bytes=$files[$name]; $target.Write($bytes,0,$bytes.Length) } finally { $target.Dispose() }
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }
$hash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash.ToLowerInvariant()
[IO.File]::WriteAllText(($zipPath + '.sha256'), ($hash + '  ' + [IO.Path]::GetFileName($zipPath) + "`n"), [Text.UTF8Encoding]::new($false))
Write-Host ('Packaged ' + [IO.Path]::GetFileName($zipPath) + '; SHA-256 ' + $hash)
