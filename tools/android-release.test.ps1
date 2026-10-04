# Public CLI guard checks. Never initialize a real key, build, or touch a device.
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$helper = Join-Path $PSScriptRoot 'android-release.ps1'
$testRoot = Join-Path $root ('artifacts/signing-guard-tests/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
function Invoke-Guard([string]$LocalRoot, [switch]$Initialize) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = 'powershell.exe'
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.EnvironmentVariables['LOCALAPPDATA'] = $LocalRoot
    $info.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $helper + '" -VerifyKeyOnly'
    if ($Initialize) { $info.Arguments += ' -InitializeKey' }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Cannot start CLI guard test.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Guard test timed out.' }
        return @{ code = $process.ExitCode; output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult() }
    } finally { $process.Dispose() }
}
$missing = Join-Path $testRoot 'missing'
$null = New-Item -ItemType Directory -Path $missing
$result = Invoke-Guard $missing
if ($result.code -eq 0 -or $result.output -notmatch 'Signing state missing') {
    throw 'Missing state must fail explicitly rather than generate a new signing identity.'
}
if (Test-Path -LiteralPath (Join-Path $missing 'DSHMobile/signing')) {
    throw 'A read-only key verification must not create signing state.'
}
Write-Host 'PASS: missing state requires explicit initialization and makes no signing directory.'
$partialLocal = Join-Path $testRoot 'partial'
$partialState = Join-Path $partialLocal 'DSHMobile/signing'
$null = New-Item -ItemType Directory -Path $partialState -Force
$sentinel = Join-Path $partialState 'release.p12'
[IO.File]::WriteAllText($sentinel, 'not-a-keystore-do-not-overwrite')
$before = (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash
$result = Invoke-Guard $partialLocal -Initialize
if ($result.code -eq 0 -or $result.output -notmatch 'Partial or unexpected signing state') {
    throw 'Explicit initialization must still reject partial state, without overwriting it.'
}
if ((Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash -cne $before -or
    @(Get-ChildItem -LiteralPath $partialState -Force).Count -ne 1) {
    throw 'Partial state was changed.'
}
Write-Host 'PASS: partial state is rejected without overwriting or regenerating it.'
