# Private APK release signing. No device, global policy, or Play account changes.
# Passwords use DPAPI CurrentUser at rest and child-process environment only.
[CmdletBinding()]
param(
    [switch]$InitializeKey,
    [switch]$VerifyKeyOnly,
    [string]$SdkPath,
    [string]$JdkPath
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$package = 'dev.dshmobile.app'
$alias = 'dsh-mobile-release'
$secretVariable = 'DSH_MOBILE_SIGNING_PASSWORD'
if (-not $env:LOCALAPPDATA) { throw 'LOCALAPPDATA is required for durable per-user signing state.' }
$privateRoot = [IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'DSHMobile/signing'))
$names = @('release.p12', 'password.dpapi.txt', 'certificate.der', 'identity.json')
$exists = Test-Path -LiteralPath $privateRoot
if ($exists) {
    $item = Get-Item -LiteralPath $privateRoot -Force
    $children = @(Get-ChildItem -LiteralPath $privateRoot -Force)
    if (-not $item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $children.Count -ne $names.Count -or @($children | Where-Object {
            $_.PSIsContainer -or $_.Name -cnotin $names -or ($_.Attributes -band [IO.FileAttributes]::ReparsePoint)
        }).Count -ne 0) {
        throw 'Partial or unexpected signing state. Preserve it and restore the original key; never overwrite or regenerate automatically.'
    }
    if ($InitializeKey) { throw 'Signing key already exists. Omit -InitializeKey; an existing identity must never be regenerated.' }
} elseif (-not $InitializeKey) {
    throw 'Signing state missing. Use -InitializeKey once; never regenerate a lost release key.'
}
# Reject redirected ancestors, especially a signing directory pointing at another app or a cache.
$ancestor = [IO.DirectoryInfo]::new($privateRoot).Parent
while ($ancestor) {
    if ((Test-Path -LiteralPath $ancestor.FullName) -and
        ((Get-Item -LiteralPath $ancestor.FullName -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
        throw 'Signing state must not use redirected/reparse-point directories.'
    }
    $ancestor = $ancestor.Parent
}
if ($privateRoot.StartsWith($root + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Durable signing state must remain outside the project, never in Git or disposable build artifacts.'
}
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
function Set-OwnerOnlyAcl([string]$Path, [bool]$Directory) {
    if ($Directory) {
        $acl = [Security.AccessControl.DirectorySecurity]::new()
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow')
    } else {
        $acl = [Security.AccessControl.FileSecurity]::new()
        $rule = [Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow')
    }
    $acl.SetOwner($sid)
    $acl.SetAccessRuleProtection($true, $false)
    $acl.AddAccessRule($rule)
    Set-Acl -LiteralPath $Path -AclObject $acl
}
function Assert-OwnerOnlyAcl([string]$Path) {
    $acl = Get-Acl -LiteralPath $Path
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    if ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne $sid.Value -or
        -not $acl.AreAccessRulesProtected -or $rules.Count -ne 1 -or
        $rules[0].IdentityReference.Value -cne $sid.Value -or
        $rules[0].AccessControlType -ne [Security.AccessControl.AccessControlType]::Allow -or
        $rules[0].FileSystemRights -ne [Security.AccessControl.FileSystemRights]::FullControl) {
        throw 'Signing ACL is not owner-only. Refusing to read or repair existing private state automatically.'
    }
}
if ($exists) {
    Assert-OwnerOnlyAcl $privateRoot
    foreach ($name in $names) { Assert-OwnerOnlyAcl (Join-Path $privateRoot $name) }
}

# Match the existing build helper: supported JDK 21/17 and pinned SDK 36.0.0.
$sdkCandidates = @($SdkPath, $env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))
if ($SdkPath) { $sdkCandidates = @($SdkPath) }
$sdk = $sdkCandidates | Where-Object {
    $_ -and (Test-Path -LiteralPath (Join-Path $_ 'platforms/android-36/android.jar')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'build-tools/36.0.0/aapt2.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'build-tools/36.0.0/zipalign.exe')) -and
    (Test-Path -LiteralPath (Join-Path $_ 'build-tools/36.0.0/lib/apksigner.jar'))
} | Select-Object -First 1
if (-not $sdk) { throw 'SDK android-36 and complete build-tools 36.0.0 required; pass -SdkPath.' }
$sdk = [IO.Path]::GetFullPath($sdk)
$jdkCandidates = @($JdkPath, $env:JAVA_HOME)
if (-not $JdkPath) {
    foreach ($parent in @($env:ProgramFiles, $env:LOCALAPPDATA)) {
        foreach ($vendor in @('Eclipse Adoptium', 'Java', 'Microsoft')) {
            if ($parent -and (Test-Path -LiteralPath (Join-Path $parent $vendor) -PathType Container)) {
                $jdkCandidates += Get-ChildItem -LiteralPath (Join-Path $parent $vendor) -Directory |
                    Sort-Object Name -Descending | Select-Object -ExpandProperty FullName
            }
        }
    }
}
if ($JdkPath) { $jdkCandidates = @($JdkPath) }
$jdk = $null
foreach ($candidate in ($jdkCandidates | Where-Object { $_ } | Select-Object -Unique)) {
    $release = Join-Path $candidate 'release'
    if ((Test-Path -LiteralPath (Join-Path $candidate 'bin/java.exe')) -and
        (Test-Path -LiteralPath (Join-Path $candidate 'bin/keytool.exe')) -and
        (Test-Path -LiteralPath $release) -and
        (@([IO.File]::ReadAllLines($release) | Where-Object { $_ -match '^JAVA_VERSION="(21|17)\.' }).Count -gt 0)) {
        $jdk = [IO.Path]::GetFullPath($candidate); break
    }
}
if (-not $jdk) { throw 'JDK 21 or 17 required; pass -JdkPath.' }
$java = Join-Path $jdk 'bin/java.exe'
$keytool = Join-Path $jdk 'bin/keytool.exe'
$apksignerJar = Join-Path $sdk 'build-tools/36.0.0/lib/apksigner.jar'
$aapt = Join-Path $sdk 'build-tools/36.0.0/aapt2.exe'
$zipalign = Join-Path $sdk 'build-tools/36.0.0/zipalign.exe'
function Quote-NativeArgument([string]$Value) {
    if ($Value -notmatch '[\s"]' -and $Value.Length -gt 0) { return $Value }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    $escaped = [regex]::Replace($escaped, '(\\+)$', '$1$1')
    return '"' + $escaped + '"'
}
function Invoke-Tool {
    param([string]$File, [string[]]$Arguments, [string]$Stage, [AllowNull()][string]$Password = $null)
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = $File
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $info.Arguments = ($Arguments | ForEach-Object { Quote-NativeArgument $_ }) -join ' '
    if ($null -ne $Password) { $info.EnvironmentVariables[$secretVariable] = $Password }
    $process = [Diagnostics.Process]::new()
    $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw "$Stage failed to start (details redacted)." }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(180000)) { $process.Kill(); $process.WaitForExit(); throw "$Stage timed out; preserve signing state." }
        $output = $stdout.GetAwaiter().GetResult()
        $null = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "$Stage failed (exit $($process.ExitCode); tool output redacted)." }
        return $output
    } finally {
        $info.EnvironmentVariables.Remove($secretVariable)
        $process.Dispose()
    }
}
function Certificate-Hash([string]$PemOutput) {
    $match = [regex]::Match($PemOutput, '-----BEGIN CERTIFICATE-----\s*([A-Za-z0-9+/=\r\n]+)\s*-----END CERTIFICATE-----')
    if (-not $match.Success) { throw 'Cannot verify release key certificate (details redacted).' }
    $der = [Convert]::FromBase64String(($match.Groups[1].Value -replace '\s', ''))
    $sha = [Security.Cryptography.SHA256]::Create()
    try { return ([BitConverter]::ToString($sha.ComputeHash($der))).Replace('-', '') } finally { $sha.Dispose() }
}
$store = Join-Path $privateRoot 'release.p12'
$protectedPassword = Join-Path $privateRoot 'password.dpapi.txt'
$certificate = Join-Path $privateRoot 'certificate.der'
$identityPath = Join-Path $privateRoot 'identity.json'
$password = $null
$secure = $null
try {
    if (-not $exists) {
        $null = New-Item -ItemType Directory -Path $privateRoot
        Set-OwnerOnlyAcl $privateRoot $true
        Assert-OwnerOnlyAcl $privateRoot
        $random = New-Object byte[] 32
        $rng = [Security.Cryptography.RandomNumberGenerator]::Create()
        try { $rng.GetBytes($random); $password = [Convert]::ToBase64String($random) }
        finally { $rng.Dispose(); [Array]::Clear($random, 0, $random.Length) }
        $secure = ConvertTo-SecureString -String $password -AsPlainText -Force
        [IO.File]::WriteAllText($protectedPassword, (ConvertFrom-SecureString -SecureString $secure), [Text.UTF8Encoding]::new($false))
        Set-OwnerOnlyAcl $protectedPassword $false
        $null = Invoke-Tool $keytool @('-genkeypair', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias,
            '-keyalg', 'RSA', '-keysize', '3072', '-sigalg', 'SHA256withRSA', '-validity', '10000',
            '-dname', 'CN=DSH Mobile Private Release', '-storepass:env', $secretVariable, '-keypass:env', $secretVariable) 'Release key initialization' $password
        Set-OwnerOnlyAcl $store $false
        $null = Invoke-Tool $keytool @('-exportcert', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias,
            '-storepass:env', $secretVariable, '-file', $certificate) 'Public certificate export' $password
        Set-OwnerOnlyAcl $certificate $false
        $identity = [ordered]@{ schema = 1; package = $package; alias = $alias; storeType = 'PKCS12';
            keyAlgorithm = 'RSA'; keySize = 3072; certificateSha256 = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash;
            createdAt = [DateTimeOffset]::UtcNow.ToString('o') }
        [IO.File]::WriteAllText($identityPath, ($identity | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
        Set-OwnerOnlyAcl $identityPath $false
        Write-Host 'Created app-specific release key. Back up the keystore AND a recoverable password; DPAPI alone is not a portable backup.'
    } else {
        try { $identity = [IO.File]::ReadAllText($identityPath) | ConvertFrom-Json } catch { throw 'Invalid signing identity metadata (details redacted).' }
        if ($identity.schema -ne 1 -or $identity.package -cne $package -or $identity.alias -cne $alias -or
            $identity.storeType -cne 'PKCS12' -or $identity.keyAlgorithm -cne 'RSA' -or $identity.keySize -ne 3072 -or
            $identity.certificateSha256 -cnotmatch '^[A-F0-9]{64}$') { throw 'Unexpected signing identity. Refusing to reuse or replace it.' }
        try {
            $secure = ConvertTo-SecureString -String ([IO.File]::ReadAllText($protectedPassword))
            $password = [Net.NetworkCredential]::new('', $secure).Password
        } catch { throw 'Cannot decrypt signing password for this Windows user/profile. Restore original key/password; never generate a replacement.' }
    }
    foreach ($name in $names) { Assert-OwnerOnlyAcl (Join-Path $privateRoot $name) }
    $certHash = (Get-FileHash -LiteralPath $certificate -Algorithm SHA256).Hash
    if ($certHash -cne $identity.certificateSha256) { throw 'Stored certificate differs from the pinned release identity.' }
    $pem = Invoke-Tool $keytool @('-exportcert', '-rfc', '-keystore', $store, '-storetype', 'PKCS12', '-alias', $alias,
        '-storepass:env', $secretVariable) 'Release key verification' $password
    if ((Certificate-Hash $pem) -cne $certHash) { throw 'Keystore certificate differs from the pinned release identity.' }
    Write-Host "Release certificate SHA-256: $certHash"
    if ($VerifyKeyOnly) { Write-Host 'Release key identity and owner-only ACL verified; no build or device changes.'; return }

    # Never pass signing secrets to Gradle. Existing release remains unsigned in its build directory.
    & (Join-Path $PSScriptRoot 'android-build.ps1') -SdkPath $sdk -JdkPath $jdk `
        -Tasks ':app:assembleRelease', ':app:testDebugUnitTest', ':app:lintRelease'
    if ($LASTEXITCODE -ne 0) { throw 'Release build/test/lint gate failed.' }
    $unsigned = Join-Path $root 'android/app/build/outputs/apk/release/app-release-unsigned.apk'
    $badging = Invoke-Tool $aapt @('dump', 'badging', $unsigned) 'Release APK metadata verification'
    $manifest = Invoke-Tool $aapt @('dump', 'xmltree', $unsigned, '--file', 'AndroidManifest.xml') 'Release manifest verification'
    $meta = [regex]::Match($badging, "(?m)^package: name='dev\.dshmobile\.app' versionCode='(\d+)' versionName='([A-Za-z0-9._-]+)'")
    if (-not $meta.Success -or $badging -notmatch "(?m)^minSdkVersion:'26'\s*$" -or
        $badging -notmatch "(?m)^targetSdkVersion:'36'\s*$" -or $manifest -match 'android:debuggable[^\r\n]*=true' -or
        $manifest -match 'android:networkSecurityConfig' -or $manifest -notmatch 'android:usesCleartextTraffic[^\r\n]*=false' -or
        $manifest -notmatch 'dev\.dshmobile\.app\.MainActivity' -or $manifest -match 'PreviewActivity') {
        throw 'Release APK package/SDK/debuggable/transport guard failed; do not distribute.'
    }
    $permissions = @([regex]::Matches($badging, "(?m)^uses-permission: name='([^']+)'") | ForEach-Object { $_.Groups[1].Value })
    if ($permissions.Count -ne 2 -or @($permissions | Where-Object { $_ -cnotin @('android.permission.INTERNET', "$package.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION") }).Count -ne 0) {
        throw 'Unexpected release permission surface; review before signing.'
    }
    $version = $meta.Groups[2].Value
    $out = Join-Path $root ('artifacts/deliverables/private-release/' + (Get-Date -Format 'yyyyMMdd-HHmmss-fff') + '-' + [guid]::NewGuid().ToString('N').Substring(0, 8))
    $null = New-Item -ItemType Directory -Path $out
    $aligned = Join-Path $out 'aligned-unsigned.apk'
    $signed = Join-Path $out "dsh-mobile-$version-private-release.apk"
    $null = Invoke-Tool $zipalign @('-P', '16', '4', $unsigned, $aligned) 'APK alignment'
    $null = Invoke-Tool $zipalign @('-c', '-P', '16', '4', $aligned) 'Unsigned alignment verification'
    $null = Invoke-Tool $java @('-jar', $apksignerJar, 'sign', '--ks', $store, '--ks-type', 'PKCS12', '--ks-key-alias', $alias,
        '--ks-pass', "env:$secretVariable", '--key-pass', "env:$secretVariable", '--min-sdk-version', '26',
        '--v1-signing-enabled', 'false', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true', '--v4-signing-enabled', 'false',
        '--out', $signed, $aligned) 'APK signing' $password
    $verified = Invoke-Tool $java @('-jar', $apksignerJar, 'verify', '--verbose', '--print-certs', '--min-sdk-version', '26', $signed) 'Signed APK verification'
    if ($verified -notmatch 'Verified using v2 scheme[^\r\n]*: true' -or $verified -notmatch 'Verified using v3 scheme[^\r\n]*: true' -or
        $verified -notmatch '(?m)^Number of signers: 1\s*$' -or $verified -notmatch '(?m)^Signer #1 key algorithm: RSA\s*$' -or
        $verified -notmatch '(?m)^Signer #1 key size \(bits\): 3072\s*$' -or
        $verified -notmatch "(?im)^Signer #1 certificate SHA-256 digest: $certHash\s*$") {
        throw 'Signed APK scheme or pinned certificate mismatch; do not distribute.'
    }
    $null = Invoke-Tool $zipalign @('-c', '-P', '16', '4', $signed) 'Signed alignment verification'
    $mapping = Join-Path $root 'android/app/build/outputs/mapping/release/mapping.txt'
    if (-not (Test-Path -LiteralPath $mapping -PathType Leaf)) { throw 'R8 mapping missing; release shrinking evidence required.' }
    Copy-Item -LiteralPath $mapping -Destination (Join-Path $out 'mapping.txt')
    $hash = (Get-FileHash -LiteralPath $signed -Algorithm SHA256).Hash
    $receipt = [ordered]@{ schema = 1; package = $package; versionName = $version; versionCode = [int]$meta.Groups[1].Value;
        minSdk = 26; targetSdk = 36; debuggable = $false; cleartext = $false; minified = $true;
        keyAlgorithm = 'RSA'; keySize = 3072; certificateSha256 = $certHash; v2 = $true; v3 = $true; alignment16k = $true;
        apkName = [IO.Path]::GetFileName($signed); apkSha256 = $hash; apkBytes = (Get-Item -LiteralPath $signed).Length;
        unsignedApkSha256 = (Get-FileHash -LiteralPath $unsigned -Algorithm SHA256).Hash;
        mappingSha256 = (Get-FileHash -LiteralPath $mapping -Algorithm SHA256).Hash;
        buildTasks = @('assembleRelease', 'testDebugUnitTest', 'lintRelease'); deviceAcceptance = $false; playProtectAcceptance = $false;
        createdAt = [DateTimeOffset]::UtcNow.ToString('o') }
    [IO.File]::WriteAllText((Join-Path $out 'release-receipt.json'), ($receipt | ConvertTo-Json -Depth 4), [Text.UTF8Encoding]::new($false))
    # Only this freshly created literal intermediate can be removed; never remove key state on failure.
    Remove-Item -LiteralPath $aligned
    Write-Host "Signed release APK: $signed"
    Write-Host "APK SHA-256: $hash"
    Write-Host 'Signature/alignment verified. Device installation and Play Protect behavior require separate acceptance.'
} finally {
    $password = $null
    if ($secure) { $secure.Dispose() }
}
