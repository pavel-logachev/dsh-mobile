[CmdletBinding()]
param(
    [ValidateSet('Record', 'Verify')][string]$Action = 'Record',
    [string]$Image = 'dsh-mobile-relay:0.2.0',
    [string]$Receipt = (Join-Path $PSScriptRoot 'image-receipt.json')
)
$ErrorActionPreference = 'Stop'
$context = docker context show
if ($LASTEXITCODE -ne 0) { throw 'docker context query failed' }
if ($context -ne 'desktop-linux') { throw "Expected local context desktop-linux, got '$context'; refusing remote/implicit daemon." }
$raw = docker image inspect $Image
if ($LASTEXITCODE -ne 0) { throw 'image inspect failed' }
$imageData = $raw | ConvertFrom-Json
if ($imageData.Count -ne 1) { throw 'Expected exactly one image result' }
$img = $imageData[0]
$expectedBase = 'node:24.21.0-bookworm-slim@sha256:0e0ff40c39bc087845bfb27465a0df4ea419520094bc35842ff83dd8cbe6f9b6'
$df = Get-Content (Join-Path $PSScriptRoot 'Dockerfile') -Raw
if (($df | Select-String -AllMatches ([regex]::Escape($expectedBase))).Matches.Count -lt 3) { throw 'All three stages must use the verified base digest' }
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path.TrimEnd('\') + '\'
$src = Resolve-Path (Join-Path $PSScriptRoot '..\..\relay\src')
$files = Get-ChildItem $src -File -Recurse | Sort-Object FullName | ForEach-Object {
    $hash = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $relative = $_.FullName.Substring($projectRoot.Length).Replace('\','/')
    [ordered]@{ path = $relative; sha256 = $hash }
}
$payload = [ordered]@{
    schema = 1
    action = 'local-build-candidate'
    dockerContext = $context
    image = $Image
    imageId = $img.Id
    repoDigests = @($img.RepoDigests)
    baseImage = $expectedBase
    user = $img.Config.User
    entrypoint = @($img.Config.Entrypoint)
    cmd = @($img.Config.Cmd)
    relaySource = @($files)
}
if ($Action -eq 'Record') {
    $json = $payload | ConvertTo-Json -Depth 8
    Set-Content -Path $Receipt -Value $json -Encoding utf8
    Write-Output "Recorded local relay image/source receipt at $Receipt (no credentials)."
} else {
    if (-not (Test-Path $Receipt)) { throw 'Receipt file missing' }
    $saved = Get-Content $Receipt -Raw | ConvertFrom-Json
    if ($saved.dockerContext -ne $context -or $saved.imageId -ne $img.Id -or $saved.baseImage -ne $expectedBase) { throw 'Image ID, Docker context or pinned base differs from receipt.' }
    $expectedFiles = @($saved.relaySource | ForEach-Object { "$($_.path)=$($_.sha256)" } | Sort-Object)
    $actualFiles = @($files | ForEach-Object { "$($_.path)=$($_.sha256)" } | Sort-Object)
    if (Compare-Object $expectedFiles $actualFiles) { throw 'Relay source hash set differs from receipt.' }
    Write-Output "Receipt verified for local image $($img.Id); source hashes match."
}
