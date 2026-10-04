# Build without machine-specific paths in tracked Gradle files or local.properties.
# Example: ./tools/android-build.ps1 -Tasks ':app:assembleDebug', ':app:testDebugUnitTest'
[CmdletBinding()]
param(
    [string[]]$Tasks = @(':app:assembleDebug', ':app:testDebugUnitTest'),
    [string]$SdkPath,
    [string]$JdkPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$androidRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../android'))
$wrapper = Join-Path $androidRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $wrapper -PathType Leaf)) {
    throw "Gradle wrapper is missing: $wrapper"
}

# Explicit arguments win; otherwise honor environment and common SDK install locations.
$sdkCandidates = @($SdkPath, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT)
if ($env:LOCALAPPDATA) { $sdkCandidates += Join-Path $env:LOCALAPPDATA 'Android/Sdk' }
if ($env:USERPROFILE) { $sdkCandidates += Join-Path $env:USERPROFILE 'AppData/Local/Android/Sdk' }
if ($SdkPath) { $sdkCandidates = @($SdkPath) }
$sdk = $sdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms/android-36/android.jar')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'build-tools/36.0.0/aapt2.exe'))
} | Select-Object -First 1
if (-not $sdk) {
    throw 'Android SDK with platform android-36 and build-tools 36.0.0 not found. Set ANDROID_HOME or pass -SdkPath.'
}

# Run Gradle with JDK 21 or 17, not an untested newer JAVA_HOME. No global edits.
$jdkCandidates = @($JdkPath, $env:JAVA_HOME)
if (-not $JdkPath) {
    foreach ($parent in @($env:ProgramFiles, $env:LOCALAPPDATA)) {
        if ($parent) {
            foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft')) {
                $vendorRoot = Join-Path $parent $vendor
                if (Test-Path -LiteralPath $vendorRoot -PathType Container) {
                    $jdkCandidates += Get-ChildItem -LiteralPath $vendorRoot -Directory |
                        Sort-Object Name -Descending | Select-Object -ExpandProperty FullName
                }
            }
        }
    }
}
if ($JdkPath) { $jdkCandidates = @($JdkPath) }
$jdk = $null
foreach ($candidate in ($jdkCandidates | Where-Object { $_ } | Select-Object -Unique)) {
    $java = Join-Path $candidate 'bin/java.exe'
    $release = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath $java -PathType Leaf) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'bin/javac.exe') -PathType Leaf) -and
        (Test-Path -LiteralPath $release -PathType Leaf)) {
        $versionLine = Get-Content -LiteralPath $release | Where-Object { $_ -match '^JAVA_VERSION="(21|17)\.' } | Select-Object -First 1
        if ($versionLine) { $jdk = $candidate; break }
    }
}
if (-not $jdk) {
    throw 'JDK 21 or 17 not found. Set JAVA_HOME or pass -JdkPath; only this build process uses it.'
}
if (-not $Tasks -or ($Tasks | Where-Object { [string]::IsNullOrWhiteSpace($_) })) {
    throw 'Pass at least one nonempty Gradle task or option in -Tasks.'
}

$originalJavaHome = $env:JAVA_HOME
$originalAndroidHome = $env:ANDROID_HOME
$originalAndroidSdkRoot = $env:ANDROID_SDK_ROOT
$exitCode = 1
try {
    $env:JAVA_HOME = [IO.Path]::GetFullPath($jdk)
    $env:ANDROID_HOME = [IO.Path]::GetFullPath($sdk)
    $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
    Write-Host "JDK: $env:JAVA_HOME"
    Write-Host "SDK: $env:ANDROID_HOME"
    & $wrapper --project-dir $androidRoot --console=plain @Tasks
    $exitCode = $LASTEXITCODE
} finally {
    $env:JAVA_HOME = $originalJavaHome
    $env:ANDROID_HOME = $originalAndroidHome
    $env:ANDROID_SDK_ROOT = $originalAndroidSdkRoot
}
exit $exitCode
