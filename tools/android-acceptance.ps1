# Runs only against an already booted disposable emulator and an already running fixture.
# This script never launches an emulator/server, runs Gradle, or reads production credentials.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$FixtureConfigPath,
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk,
    [string]$AdbPath = 'adb',
    [ValidateSet('emulator-5580')][string]$Serial = 'emulator-5580',
    [switch]$AllowDeviceChanges,
    [switch]$SkipOffline,
    [string]$ArtifactsPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$package = 'dev.dshmobile.app'
$testPackage = 'dev.dshmobile.app.test'
$component = "$testPackage/androidx.test.runner.AndroidJUnitRunner"
$testClass = 'dev.dshmobile.app.acceptance.MobileAcceptanceTest#fixtureJourney'
if (-not $AllowDeviceChanges) { throw 'Parent/operator go signal required: pass -AllowDeviceChanges for disposable emulator-5580 only.' }
foreach ($path in @($FixtureConfigPath, $AppApk, $TestApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Required fixture configuration or APK file is missing.' }
}
if (-not $ArtifactsPath) {
    $ArtifactsPath = Join-Path $root ('artifacts/android-acceptance/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff'))
}
$artifacts = [IO.Path]::GetFullPath($ArtifactsPath)
$allowedArtifacts = [IO.Path]::GetFullPath((Join-Path $root 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (-not $artifacts.StartsWith($allowedArtifacts, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'ArtifactsPath must be inside this project artifacts directory (ignored by Git).'
}
if (Test-Path -LiteralPath $artifacts) { throw 'Use a new artifact directory; never mix previous receipts/screenshots.' }

# Do not print the config, parser errors, source path, or invitation. Only loopback debug fixture is admitted.
try {
    $configInfo = Get-Item -LiteralPath $FixtureConfigPath
    if ($configInfo.Length -gt 65536) { throw 'oversized' }
    $parsed = [IO.File]::ReadAllText($configInfo.FullName) | ConvertFrom-Json
    $config = @{}
    foreach ($property in $parsed.PSObject.Properties) { $config[$property.Name] = $property.Value }
    if ($config.version -ne 1 -or $config.mode -notin @('fixture', 'isolated-dsh-canary')) { throw 'wrong mode' }
    $mode = $config.mode
    $fixtureOnly = $mode -eq 'fixture'
    $isolatedDshCanary = $mode -eq 'isolated-dsh-canary'
    $allowedPrompt = if ($fixtureOnly) { 'DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1' } else { 'DSH_MOBILE_CANARY_ANDROID: synthetic emulator prompt.' }
    if ($config.ContainsKey('prompt') -and $config.prompt -cne $allowedPrompt) { throw 'unapproved prompt' }
    if ($isolatedDshCanary -and ($config.workspaceId -cne 'canary' -or
        $config.expectedAssistantText -cne ('CANARY_SERVE_OK ' + [char]0x2014 + ' deterministic real DSH; no external model.'))) { throw 'wrong canary expectations' }
    foreach ($field in @('workspaceId', 'existingSessionId', 'existingMessage', 'createdSessionTitle', 'expectedAssistantText')) {
        if ($config[$field] -isnot [string] -or [string]::IsNullOrWhiteSpace($config[$field]) -or $config[$field].Length -gt 4096) { throw 'missing expectation' }
    }
    $invitation = $config.invitation
    $uri = [uri]$invitation.baseUrl
    if ($invitation.version -ne 1 -or [string]::IsNullOrWhiteSpace($invitation.pairingToken) -or
        $uri.Scheme -ne 'http' -or $uri.Host -notin @('127.0.0.1', 'localhost') -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -notin @('', '/') -or
        $uri.Port -lt 1024 -or $uri.Port -gt 65535) { throw 'invalid fixture transport' }
    $port = $uri.Port
} catch { throw 'Invalid local fixture acceptance config (details redacted).' }
$expectations = @{}
foreach ($key in $config.Keys) { if ($key -ne 'invitation') { $expectations[$key] = $config[$key] } }
$expectationsJson = ConvertTo-Json -InputObject $expectations -Depth 12 -Compress
$initialJson = ConvertTo-Json -InputObject $config -Depth 12 -Compress

# Windows native argv quoting, also compatible with Windows PowerShell 5.1/.NET Framework.
# Only non-secret command arguments are quoted; invitation bytes travel solely on stdin.
function ConvertTo-NativeArgument([string]$Value) {
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Invoke-Adb {
    param([string[]]$Arguments, [AllowNull()][string]$InputText = $null)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $AdbPath
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.RedirectStandardInput = $true
    $info.Arguments = ((@('-s', $Serial) + $Arguments) | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Could not start adb.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) {
            $bytes = [Text.UTF8Encoding]::new($false).GetBytes($InputText)
            $process.StandardInput.BaseStream.Write($bytes, 0, $bytes.Length)
            $process.StandardInput.BaseStream.Flush()
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(240000)) {
            $process.Kill()
            $process.WaitForExit()
            throw 'adb timed out; operation interrupted, inspect disposable emulator state.'
        }
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "adb failed (exit $($process.ExitCode)); output redacted." }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Push-Config([string]$Json) {
    # No shared /sdcard temporary secret and no secret in shell command arguments.
    $null = Invoke-Adb -Arguments @('shell', '-T', "run-as $package sh -c 'umask 077; mkdir -p cache; cat > cache/acceptance-fixture.json'") -InputText $Json
}
$stages = [Collections.Generic.List[string]]::new()
function Run-Stage([string]$Stage, [string]$Capture = '') {
    Write-Host "Android $mode stage: $Stage $Capture"
    Push-Config $expectationsJson
    $arguments = @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass,
        '-e', 'acceptanceStage', $Stage)
    if ($Capture) { $arguments += @('-e', 'acceptanceCapture', $Capture) }
    $arguments += $component
    $result = Invoke-Adb -Arguments $arguments
    # am instrument can return exit 0 even for failed tests: require runner's success footer.
    if ($result -notmatch '(?m)^OK \(1 test\)\s*$' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        $checkpointLabel = 'not-reported'
        if ($result -match 'checkpoint: ([a-z0-9-]+) \(details redacted\)') { $checkpointLabel = $Matches[1] }
        throw "Instrumentation stage $Stage failed at $checkpointLabel (output intentionally redacted)."
    }
    $stages.Add($(if ($Capture) { "$Stage/$Capture" } else { $Stage }))
}

$receipt = [ordered]@{ mode = $mode; fixtureOnly = $fixtureOnly; isolatedDshCanary = $isolatedDshCanary;
    productionCanary = $false; externalModel = $false; serial = $Serial;
    appPackage = $package; startedAt = [DateTimeOffset]::UtcNow.ToString('o'); passed = $false;
    offlineRequested = -not [bool]$SkipOffline; stages = @(); failure = $null }
$reverseAdded = $false
$settingsChanged = $false
$originalFont = $null
$originalNight = $null
$originalAppLocales = $null
$appLocalesChanged = $false
try {
    if ((Invoke-Adb -Arguments @('get-state')) -ne 'device' -or
        (Invoke-Adb -Arguments @('shell', 'getprop', 'sys.boot_completed')) -ne '1' -or
        (Invoke-Adb -Arguments @('shell', 'getprop', 'ro.kernel.qemu')) -ne '1') {
        throw 'Expected booted disposable emulator not available.'
    }
    $reverseList = Invoke-Adb -Arguments @('reverse', '--list')
    if ($reverseList -match "(?m)\btcp:$port\s") { throw 'Fixture reverse port is already owned; remove/coordinate it explicitly before running.' }
    # Capture prior settings for rollback; do not change language/system locale here.
    $originalFont = Invoke-Adb -Arguments @('shell', 'settings', 'get', 'system', 'font_scale')
    $nightOutput = Invoke-Adb -Arguments @('shell', 'cmd', 'uimode', 'night')
    if ($nightOutput -notmatch 'Night mode:\s*(yes|no|auto|custom)') { throw 'Cannot read original night mode.' }
    $originalNight = $Matches[1]
    $localeOutput = Invoke-Adb -Arguments @('shell', 'cmd', 'locale', 'get-app-locales', $package)
    if ($localeOutput -notmatch 'Locales for dev\.dshmobile\.app for user \d+ are \[([a-zA-Z0-9,-]*)\]') { throw 'Cannot read original per-app locales.' }
    $originalAppLocales = $Matches[1]
    $null = New-Item -ItemType Directory -Path $artifacts
    $receipt.appApkSha256 = (Get-FileHash -LiteralPath $AppApk -Algorithm SHA256).Hash
    $receipt.testApkSha256 = (Get-FileHash -LiteralPath $TestApk -Algorithm SHA256).Hash
    $null = Invoke-Adb -Arguments @('install', '-r', [IO.Path]::GetFullPath($AppApk))
    $null = Invoke-Adb -Arguments @('install', '-r', [IO.Path]::GetFullPath($TestApk))
    $clear = Invoke-Adb -Arguments @('shell', 'pm', 'clear', $package)
    if ($clear -ne 'Success') { throw 'App-only clear failed.' }
    $null = Invoke-Adb -Arguments @('reverse', "tcp:$port", "tcp:$port")
    $reverseAdded = $true
    $settingsChanged = $true
    $null = Invoke-Adb -Arguments @('shell', 'cmd', 'uimode', 'night', 'no')
    $null = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    Write-Host "Android $mode stage: pairLifecycle"
    Push-Config $initialJson
    $result = Invoke-Adb -Arguments @('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', $testClass,
        '-e', 'acceptanceStage', 'pairLifecycle', $component)
    $initialJson = $null; $config = $null; $invitation = $null
    if ($result -notmatch '(?m)^OK \(1 test\)\s*$' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        $checkpointLabel = 'not-reported'
        if ($result -match 'checkpoint: ([a-z0-9-]+) \(details redacted\)') { $checkpointLabel = $Matches[1] }
        throw "Instrumentation pairLifecycle failed at $checkpointLabel (output intentionally redacted)."
    }
    $stages.Add('pairLifecycle')
    # A separate instrumentation process proves restore beyond Activity recreation.
    $null = Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $package)
    Run-Stage 'restored'
    if (-not $SkipOffline) {
        $null = Invoke-Adb -Arguments @('shell', 'am', 'force-stop', $package)
        $null = Invoke-Adb -Arguments @('reverse', '--remove', "tcp:$port")
        $reverseAdded = $false
        Run-Stage 'offline'
        $null = Invoke-Adb -Arguments @('reverse', "tcp:$port", "tcp:$port")
        $reverseAdded = $true
        Run-Stage 'recovered'
    }
    $null = Invoke-Adb -Arguments @('shell', 'cmd', 'uimode', 'night', 'yes')
    Run-Stage 'capture' 'chat-dark'
    $null = Invoke-Adb -Arguments @('shell', 'cmd', 'uimode', 'night', 'no')
    $null = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', '1.3')
    Run-Stage 'capture' 'chat-font130'
    $null = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', '1.0')
    $appLocalesChanged = $true
    $null = Invoke-Adb -Arguments @('shell', 'cmd', 'locale', 'set-app-locales', $package, '--locales', 'en')
    Run-Stage 'capture' 'chat-light-en'
    $null = Invoke-Adb -Arguments @('shell', 'cmd', 'locale', 'set-app-locales', $package, '--locales', 'ru')
    Run-Stage 'capture' 'chat-light-ru'
    $receipt.passed = $true
} catch {
    # Only our safe operation labels survive; no raw adb or JSON errors in logs/receipt.
    $receipt.failure = 'Acceptance interrupted or failed; inspect stage list and safe checkpoint output.'
    throw
} finally {
    $initialJson = $null; $config = $null; $invitation = $null
    if (Test-Path -LiteralPath $artifacts -PathType Container) {
        try {
            $null = Invoke-Adb -Arguments @('pull', "/sdcard/Android/data/$package/files/acceptance", $artifacts)
            $requiredCaptures = @('onboarding-empty', 'chat-initial', 'chat-reopened', 'chat-light', 'chat-dark', 'chat-font130', 'chat-light-en', 'chat-light-ru')
            if (-not $SkipOffline) { $requiredCaptures += @('chat-offline', 'chat-recovered') }
            foreach ($name in $requiredCaptures) {
                $image = Join-Path $artifacts "acceptance/$name.png"
                $metadata = Join-Path $artifacts "acceptance/$name.json"
                if (-not (Test-Path -LiteralPath $image -PathType Leaf) -or -not (Test-Path -LiteralPath $metadata -PathType Leaf)) {
                    throw 'Missing required native capture.'
                }
                $provenance = [IO.File]::ReadAllText($metadata) | ConvertFrom-Json
                if ($provenance.source -ne 'android.app.UiAutomation.takeScreenshot' -or
                    $provenance.width -lt 1 -or $provenance.height -lt 1 -or
                    $provenance.mode -cne $mode -or $provenance.fixtureOnly -ne $fixtureOnly -or
                    $provenance.isolatedDshCanary -ne $isolatedDshCanary -or $provenance.productionCanary) {
                    throw 'Invalid native capture provenance.'
                }
            }
        } catch { $receipt.capturePullFailed = $true; $receipt.passed = $false }
    }
    # Literal app-private path verified here; remove only the ephemeral test input, never other app files.
    try { $null = Invoke-Adb -Arguments @('shell', "run-as $package rm -f cache/acceptance-fixture.json") } catch { $receipt.configCleanupFailed = $true }
    if ($reverseAdded) {
        try { $null = Invoke-Adb -Arguments @('reverse', '--remove', "tcp:$port") } catch { $receipt.reverseCleanupFailed = $true }
    }
    if ($appLocalesChanged) {
        try {
            $localeArguments = @('shell', 'cmd', 'locale', 'set-app-locales', $package)
            if ($originalAppLocales) { $localeArguments += @('--locales', $originalAppLocales) }
            $null = Invoke-Adb -Arguments $localeArguments
        } catch { $receipt.appLocalesRestoreFailed = $true }
    }
    if ($settingsChanged) {
        try {
            if ($originalFont -eq 'null') { $null = Invoke-Adb -Arguments @('shell', 'settings', 'delete', 'system', 'font_scale') }
            else { $null = Invoke-Adb -Arguments @('shell', 'settings', 'put', 'system', 'font_scale', $originalFont) }
            $null = Invoke-Adb -Arguments @('shell', 'cmd', 'uimode', 'night', $originalNight)
        } catch { $receipt.settingsRestoreFailed = $true }
    }
    if ($receipt.Contains('configCleanupFailed') -or $receipt.Contains('reverseCleanupFailed') -or $receipt.Contains('settingsRestoreFailed') -or $receipt.Contains('appLocalesRestoreFailed')) {
        $receipt.passed = $false
    }
    $receipt.stages = @($stages.ToArray())
    $receipt.finishedAt = [DateTimeOffset]::UtcNow.ToString('o')
    if (Test-Path -LiteralPath $artifacts -PathType Container) {
        $receipt.screenshots = @(Get-ChildItem -LiteralPath $artifacts -Filter '*.png' -Recurse | ForEach-Object {
            @{ name = $_.Name; sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash }
        })
        $receipt | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $artifacts 'receipt.json') -Encoding utf8
        Write-Host "Native Android evidence: $artifacts"
    }
}
if (-not $receipt.passed -or $receipt.Contains('configCleanupFailed') -or $receipt.Contains('reverseCleanupFailed') -or $receipt.Contains('settingsRestoreFailed') -or $receipt.Contains('appLocalesRestoreFailed')) {
    throw 'Acceptance evidence or disposable-emulator cleanup incomplete; see redacted receipt.'
}
