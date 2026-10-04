# Synthetic redesign captures on an already booted disposable emulator only.
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$FixtureConfigPath,
    [Parameter(Mandatory)][string]$AppApk,
    [Parameter(Mandatory)][string]$TestApk,
    [string]$AdbPath = 'adb',
    [ValidateSet('emulator-5580')][string]$Serial = 'emulator-5580',
    [switch]$AllowDeviceChanges,
    [string]$ArtifactsPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$package = 'dev.dshmobile.app'
$component = 'dev.dshmobile.app.test/androidx.test.runner.AndroidJUnitRunner'
$testClass = 'dev.dshmobile.app.acceptance.MobileAcceptanceTest#fixtureJourney'
if (-not $AllowDeviceChanges) { throw 'Operator go signal required for disposable emulator-5580 only.' }
foreach ($path in @($FixtureConfigPath, $AppApk, $TestApk)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw 'Required private config or APK missing.' }
}
if (-not $ArtifactsPath) { $ArtifactsPath = Join-Path $root ('artifacts/redesign-capture/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff')) }
$artifacts = [IO.Path]::GetFullPath($ArtifactsPath)
$allowedArtifacts = [IO.Path]::GetFullPath((Join-Path $root 'artifacts')) + [IO.Path]::DirectorySeparatorChar
if (-not $artifacts.StartsWith($allowedArtifacts, [StringComparison]::OrdinalIgnoreCase) -or (Test-Path -LiteralPath $artifacts)) { throw 'Use a new ignored project artifacts directory.' }
try {
    $item = Get-Item -LiteralPath $FixtureConfigPath
    if ($item.Length -gt 65536) { throw 'oversized' }
    $parsed = [IO.File]::ReadAllText($item.FullName) | ConvertFrom-Json
    $config = @{}
    foreach ($property in $parsed.PSObject.Properties) { $config[$property.Name] = $property.Value }
    if ($config.version -ne 1 -or $config.mode -cne 'fixture') { throw 'fixture only' }
    if ($config.ContainsKey('prompt') -and $config.prompt -cne 'DSH_MOBILE_ACCEPTANCE_SYNTHETIC_PROMPT_V1') { throw 'prompt' }
    foreach ($field in @('workspaceId','existingSessionId','existingMessage','createdSessionTitle','expectedAssistantText')) {
        if ($config[$field] -isnot [string] -or [string]::IsNullOrWhiteSpace($config[$field]) -or $config[$field].Length -gt 4096) { throw 'expectation' }
    }
    $invitation = $config.invitation
    $uri = [uri]$invitation.baseUrl
    if ($invitation.version -ne 1 -or [string]::IsNullOrWhiteSpace($invitation.pairingToken) -or
        $uri.Scheme -ne 'http' -or $uri.Host -notin @('127.0.0.1','localhost') -or
        $uri.UserInfo -or $uri.Query -or $uri.Fragment -or $uri.AbsolutePath -notin @('','/') -or
        $uri.Port -lt 1024 -or $uri.Port -gt 65535) { throw 'transport' }
    $port = $uri.Port
    # Carry nonsecret fixture metadata into subsequent envelopes before removing invitation.
    if (-not $config.ContainsKey('demoFixture') -and $invitation.PSObject.Properties['demoFixture']) { $config.demoFixture = $invitation.demoFixture }
    if (-not $config.ContainsKey('demoFixture')) { throw 'multi-project metadata required' }
    foreach ($field in @('markdownSessionId','markdownAnchor','filterWorkspaceId')) {
        if ([string]::IsNullOrWhiteSpace($config.demoFixture.$field)) { throw 'capture metadata' }
    }
    $initialJson = ConvertTo-Json -InputObject $config -Depth 12 -Compress
    $expectations = @{}
    foreach ($key in $config.Keys) { if ($key -ne 'invitation') { $expectations[$key] = $config[$key] } }
    $expectationsJson = ConvertTo-Json -InputObject $expectations -Depth 12 -Compress
} catch { throw 'Invalid private synthetic capture configuration (details redacted).' }
function ConvertTo-NativeArgument([string]$Value) {
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Invoke-Adb {
    param([string[]]$Arguments, [AllowNull()][string]$InputText = $null)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $AdbPath; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true; $info.RedirectStandardInput = $true
    $info.Arguments = ((@('-s',$Serial) + $Arguments) | ForEach-Object { ConvertTo-NativeArgument $_ }) -join ' '
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Cannot start adb.' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if ($null -ne $InputText) {
            $bytes = [Text.UTF8Encoding]::new($false).GetBytes($InputText)
            $process.StandardInput.BaseStream.Write($bytes,0,$bytes.Length); $process.StandardInput.BaseStream.Flush()
        }
        $process.StandardInput.Close()
        if (-not $process.WaitForExit(240000)) { $process.Kill(); $process.WaitForExit(); throw 'adb timed out; interrupted.' }
        $output = $stdout.GetAwaiter().GetResult(); $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "adb failed (exit $($process.ExitCode)); output redacted." }
        return $output.Trim()
    } finally { $process.Dispose() }
}
function Push-Config([string]$Json) {
    $null = Invoke-Adb -Arguments @('shell','-T',"run-as $package sh -c 'umask 077; mkdir -p cache; cat > cache/acceptance-fixture.json'") -InputText $Json
}
$stages = [Collections.Generic.List[string]]::new()
function Run-Stage([string]$Stage, [string]$Capture = '', [string]$Json = $expectationsJson) {
    Write-Host "Synthetic Android stage: $Stage $Capture"
    Push-Config $Json
    $arguments = @('shell','am','instrument','-w','-r','-e','class',$testClass,'-e','acceptanceStage',$Stage)
    if ($Capture) { $arguments += @('-e','acceptanceCapture',$Capture) }
    $result = Invoke-Adb -Arguments ($arguments + $component)
    if ($result -notmatch '(?m)^OK \(1 test\)\s*$' -or $result -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed') {
        $label = 'not-reported'
        if ($result -match 'checkpoint: ([a-z0-9-]+) \(details redacted\)') { $label = $Matches[1] }
        throw "Stage $Stage/$Capture failed at $label (details redacted)."
    }
    $stages.Add($(if ($Capture) { "$Stage/$Capture" } else { $Stage }))
}
$captures = @('pairing-dark','home-dark','home-project','chat-markdown-running','new-chat-sheet','settings-dark','home-light','home-font130','home-dark-en')
$receipt = [ordered]@{mode='fixture';fixtureOnly=$true;isolatedDshCanary=$false;productionCanary=$false;externalModel=$false;serial=$Serial;startedAt=[DateTimeOffset]::UtcNow.ToString('o');passed=$false;stages=@();failure=$null}
$reverseAdded=$false; $settingsChanged=$false; $appLocalesChanged=$false
$originalFont=$null; $originalNight=$null; $originalAppLocales=$null
try {
    if ((Invoke-Adb @('get-state')) -ne 'device' -or (Invoke-Adb @('shell','getprop','sys.boot_completed')) -ne '1' -or (Invoke-Adb @('shell','getprop','ro.kernel.qemu')) -ne '1') { throw 'Expected booted disposable emulator unavailable.' }
    if ((Invoke-Adb @('reverse','--list')) -match "(?m)\btcp:$port\s") { throw 'Fixture reverse already owned.' }
    $originalFont = Invoke-Adb @('shell','settings','get','system','font_scale')
    if ((Invoke-Adb @('shell','cmd','uimode','night')) -notmatch 'Night mode:\s*(yes|no|auto|custom)') { throw 'Cannot read night mode.' }
    $originalNight=$Matches[1]
    $localeOutput=Invoke-Adb @('shell','cmd','locale','get-app-locales',$package)
    if ($localeOutput -match 'Locales for dev\.dshmobile\.app for user \d+ are \[([a-zA-Z0-9,-]*)\]') { $originalAppLocales=$Matches[1] }
    elseif ($localeOutput -eq 'Unknown package dev.dshmobile.app for userId 0') { $originalAppLocales='' }
    else { throw 'Cannot read per-app locales.' }
    $null=New-Item -ItemType Directory -Path $artifacts
    $receipt.appApkSha256=(Get-FileHash $AppApk -Algorithm SHA256).Hash; $receipt.testApkSha256=(Get-FileHash $TestApk -Algorithm SHA256).Hash
    $null=Invoke-Adb @('install','-r',[IO.Path]::GetFullPath($AppApk)); $null=Invoke-Adb @('install','-r',[IO.Path]::GetFullPath($TestApk))
    if ((Invoke-Adb @('shell','pm','clear',$package)) -ne 'Success') { throw 'App clear failed.' }
    $null=Invoke-Adb @('reverse',"tcp:$port","tcp:$port"); $reverseAdded=$true
    $settingsChanged=$true; $appLocalesChanged=$true
    $null=Invoke-Adb @('shell','settings','put','system','font_scale','1.0')
    $null=Invoke-Adb @('shell','cmd','uimode','night','yes')
    $null=Invoke-Adb @('shell','cmd','locale','set-app-locales',$package,'--locales','ru')
    Run-Stage 'redesignCapture' 'pairing-dark'
    Run-Stage 'pairOnly' '' $initialJson
    $initialJson=$null; $config=$null; $invitation=$null
    foreach ($name in @('home-dark','home-project','chat-markdown-running','new-chat-sheet','settings-dark','home-light')) { Run-Stage 'redesignCapture' $name }
    $null=Invoke-Adb @('shell','settings','put','system','font_scale','1.3')
    Run-Stage 'redesignCapture' 'home-font130'
    $null=Invoke-Adb @('shell','settings','put','system','font_scale','1.0')
    $null=Invoke-Adb @('shell','cmd','locale','set-app-locales',$package,'--locales','en')
    Run-Stage 'redesignCapture' 'home-dark-en'
    $receipt.passed=$true
} catch { $receipt.failure='Capture interrupted or failed; safe checkpoint output only.'; throw }
finally {
    $initialJson=$null; $config=$null; $invitation=$null
    if (Test-Path -LiteralPath $artifacts -PathType Container) {
        try {
            $null=Invoke-Adb @('pull',"/sdcard/Android/data/$package/files/acceptance",$artifacts)
            foreach ($name in $captures) {
                $image=Join-Path $artifacts "acceptance/$name.png"; $metadata=Join-Path $artifacts "acceptance/$name.json"
                if (-not (Test-Path $image -PathType Leaf) -or -not (Test-Path $metadata -PathType Leaf)) { throw 'Missing capture.' }
                $p=[IO.File]::ReadAllText($metadata) | ConvertFrom-Json
                $scale=if ($name -eq 'home-font130') {1.3} else {1.0}
                $theme=if ($name -eq 'home-light') {'LIGHT'} else {'DARK'}
                $locale=if ($name -eq 'home-dark-en') {'en'} else {'ru'}
                if ($p.source -ne 'android.app.UiAutomation.takeScreenshot' -or $p.width -lt 1 -or $p.height -lt 1 -or $p.mode -cne 'fixture' -or -not $p.fixtureOnly -or $p.isolatedDshCanary -or $p.productionCanary -or $p.externalModel -or [Math]::Abs($p.fontScale-$scale) -gt 0.02 -or $p.themePreference -cne $theme -or $p.locale -notmatch "^$locale(-|$)") { throw 'Capture provenance invalid.' }
            }
        } catch { $receipt.capturePullFailed=$true; $receipt.passed=$false }
    }
    try { $null=Invoke-Adb @('shell',"run-as $package rm -f cache/acceptance-fixture.json") } catch { $receipt.configCleanupFailed=$true }
    if ($reverseAdded) { try { $null=Invoke-Adb @('reverse','--remove',"tcp:$port") } catch { $receipt.reverseCleanupFailed=$true } }
    if ($appLocalesChanged) { try { $args=@('shell','cmd','locale','set-app-locales',$package); if ($originalAppLocales) { $args+=@('--locales',$originalAppLocales) }; $null=Invoke-Adb $args } catch { $receipt.appLocalesRestoreFailed=$true } }
    if ($settingsChanged) { try {
        if ($originalFont -eq 'null') { $null=Invoke-Adb @('shell','settings','delete','system','font_scale') } else { $null=Invoke-Adb @('shell','settings','put','system','font_scale',$originalFont) }
        $null=Invoke-Adb @('shell','cmd','uimode','night',$originalNight)
    } catch { $receipt.settingsRestoreFailed=$true } }
    foreach ($key in @('configCleanupFailed','reverseCleanupFailed','appLocalesRestoreFailed','settingsRestoreFailed')) { if ($receipt.Contains($key)) { $receipt.passed=$false } }
    $receipt.stages=@($stages.ToArray()); $receipt.finishedAt=[DateTimeOffset]::UtcNow.ToString('o')
    if (Test-Path -LiteralPath $artifacts -PathType Container) {
        $receipt.files=@(Get-ChildItem $artifacts -Recurse -File | Where-Object { $_.Extension -in @('.png','.json') } | ForEach-Object { @{name=$_.Name;sha256=(Get-FileHash $_.FullName -Algorithm SHA256).Hash} })
        $receipt | ConvertTo-Json -Depth 8 | Set-Content (Join-Path $artifacts 'receipt.json') -Encoding utf8
        Write-Host "Native redesign evidence: $artifacts"
    }
}
if (-not $receipt.passed) { throw 'Capture evidence or disposable-emulator cleanup incomplete; see redacted receipt.' }
