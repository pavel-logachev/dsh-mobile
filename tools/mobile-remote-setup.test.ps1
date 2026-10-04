# Isolated public-CLI tests. No active profile, server, relay, or real owner state.
[CmdletBinding()]
param([string]$OpenSslPath)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$helper = Join-Path $PSScriptRoot 'mobile-remote-setup.ps1'
if (-not $env:DSH_HOME) { throw 'DSH_HOME required for private disposable test cache.' }
$cache = [IO.Path]::GetFullPath((Join-Path $env:DSH_HOME '../cache'))
$testRoot = Join-Path $cache ('mobile-remote-setup-tests/' + [guid]::NewGuid().ToString('N'))
$null = New-Item -ItemType Directory -Path $testRoot
$sid = [Security.Principal.WindowsIdentity]::GetCurrent().User
function Protect-TestPath([string]$Path, [bool]$Directory) {
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
Protect-TestPath $testRoot $true
$local = Join-Path $testRoot 'local'
$workspace = Join-Path $testRoot 'workspace'
$null = New-Item -ItemType Directory -Path $local,$workspace
$routePath = Join-Path $testRoot 'route.json'
$scopePath = Join-Path $testRoot 'workspaces.json'
# Non-secret synthetic capability with canonical 32-byte encoding. Never real relay credentials.
$route = @{ routeId = '0123456789abcdef0123456789abcdef'; connectorToken = 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }
[IO.File]::WriteAllText($routePath, ($route | ConvertTo-Json), [Text.UTF8Encoding]::new($false))
Protect-TestPath $routePath $false
[IO.File]::WriteAllText($scopePath, (ConvertTo-Json -InputObject @(@{ id='synthetic'; name='Synthetic workspace'; path=$workspace })), [Text.UTF8Encoding]::new($false))
Protect-TestPath $scopePath $false
function Invoke-Setup([string]$LocalRoot, [switch]$Initialize, [string]$Relay = 'wss://relay.example.invalid/dsh-mobile-relay', [string]$RouteFile = $routePath, [string]$ScopeFile = $scopePath) {
    $info = [Diagnostics.ProcessStartInfo]::new()
    $info.FileName = 'powershell.exe'; $info.UseShellExecute = $false; $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true; $info.RedirectStandardError = $true
    $info.EnvironmentVariables['LOCALAPPDATA'] = $LocalRoot
    $info.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + $helper + '" -RouteCredentialsPath "' + $RouteFile +
        '" -RelayUrl "' + $Relay + '"'
    if ($ScopeFile) { $info.Arguments += ' -WorkspacesPath "' + $ScopeFile + '"' }
    if ($Initialize) { $info.Arguments += ' -Initialize' }
    if ($OpenSslPath) { $info.Arguments += ' -OpenSslPath "' + $OpenSslPath + '"' }
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $info
    try {
        if (-not $process.Start()) { throw 'Setup test process start failed.' }
        $stdout = $process.StandardOutput.ReadToEndAsync(); $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(60000)) { $process.Kill(); $process.WaitForExit(); throw 'Setup test timeout.' }
        $output = $stdout.GetAwaiter().GetResult() + $stderr.GetAwaiter().GetResult()
        if ($output.Contains($route.connectorToken) -or $output.Contains('PRIVATE KEY-----')) { throw 'Secret leaked by setup CLI.' }
        return @{code=$process.ExitCode; output=$output}
    } finally { $process.Dispose() }
}
$result = Invoke-Setup $local
if ($result.code -eq 0 -or $result.output -notmatch 'Host setup state missing') { throw 'Missing state must require explicit initialization.' }
if (Test-Path -LiteralPath (Join-Path $local 'DSHMobile/host')) { throw 'Verification created state unexpectedly.' }
Write-Host 'PASS: missing state fails closed without creating any owner identity.'
$result = Invoke-Setup $local -Initialize -ScopeFile ''
if ($result.code -eq 0 -or $result.output -notmatch 'Explicit -WorkspacesPath required' -or
    (Test-Path -LiteralPath (Join-Path $local 'DSHMobile/host'))) { throw 'Missing explicit scope must fail before private state writes.' }
Write-Host 'PASS: explicit workspace input is required; no project directory is guessed.'
$result = Invoke-Setup $local -Initialize
if ($result.code -ne 0) { throw 'Explicit setup initialization failed (output intentionally omitted).' }
$private = Join-Path $local 'DSHMobile/host'
$files = @('tls-cert.pem','tls-key.pem','identity.json','host.json','cordis.additive.patch.yml','UNDO.md','setup-receipt.json')
$before = @{}
foreach ($name in $files) { $before[$name] = (Get-FileHash -LiteralPath (Join-Path $private $name) -Algorithm SHA256).Hash }
$result = Invoke-Setup $local
if ($result.code -ne 0) { throw 'Repeat setup verification failed (output intentionally omitted).' }
foreach ($name in $files) {
    if ((Get-FileHash -LiteralPath (Join-Path $private $name) -Algorithm SHA256).Hash -cne $before[$name]) {
        throw 'Repeat setup changed persistent identity/configuration bytes.'
    }
}
$config = [IO.File]::ReadAllText((Join-Path $private 'host.json')) | ConvertFrom-Json
if ($config.bind -cne '127.0.0.1' -or $config.port -ne 19445 -or
    $config.publicUrl -cne 'https://h-0123456789abcdef0123456789abcdef.dsh.invalid' -or
    $config.workspaces.Count -ne 1 -or $config.workspaces[0].id -cne 'synthetic') { throw 'Generated config broadens approved scope.' }
$patch = [IO.File]::ReadAllText((Join-Path $private 'cordis.additive.patch.yml'))
if ($patch -notmatch 'configPath' -or $patch.Contains($route.connectorToken) -or $patch -match 'connectorToken|tls-key|relay:') {
    throw 'Plugin snippet contains inline secrets rather than private configPath.'
}
Write-Host 'PASS: real OpenSSL initialization, exact TLS authority, configPath-only snippet, and byte-stable repeat verification.'
# Runtime SQLite/invitation files legitimately inherit the owner-only protected directory ACL.
[IO.File]::WriteAllText((Join-Path $private 'state/mobile.sqlite'), 'synthetic-state')
[IO.File]::WriteAllText((Join-Path $private 'invitations/synthetic.json'), '{}')
$result = Invoke-Setup $local
if ($result.code -ne 0) { throw 'Safe inherited runtime files must not break status verification.' }
Write-Host 'PASS: runtime files inherit owner-only protection without weakening the persistent setup files.'
$partialLocal = Join-Path $testRoot 'partial'
$partial = Join-Path $partialLocal 'DSHMobile/host'
$null = New-Item -ItemType Directory -Path $partial -Force
Protect-TestPath $partial $true
$sentinel = Join-Path $partial 'tls-key.pem'
[IO.File]::WriteAllText($sentinel, 'do-not-overwrite')
Protect-TestPath $sentinel $false
$sentinelHash = (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash
$result = Invoke-Setup $partialLocal -Initialize
if ($result.code -eq 0 -or $result.output -notmatch 'Partial or unexpected host setup state' -or
    (Get-FileHash -LiteralPath $sentinel -Algorithm SHA256).Hash -cne $sentinelHash -or
    @(Get-ChildItem -LiteralPath $partial -Force).Count -ne 1) { throw 'Partial TLS state was not preserved fail-closed.' }
Write-Host 'PASS: partial state refuses initialization without modifying existing bytes.'
$configPath = Join-Path $private 'host.json'
$configOriginal = [IO.File]::ReadAllText($configPath)
[IO.File]::WriteAllText($configPath, $configOriginal.Replace('"port":  19445', '"port":  19446'))
# JSON formatting varies; ensure a mutation was actually introduced.
if ((Get-FileHash -LiteralPath $configPath -Algorithm SHA256).Hash -ceq $before['host.json']) {
    [IO.File]::AppendAllText($configPath, ' ')
}
$result = Invoke-Setup $local
if ($result.code -eq 0 -or $result.output -notmatch 'Existing host identity or configuration differs') { throw 'Modified host config must fail closed.' }
[IO.File]::WriteAllText($configPath, $configOriginal, [Text.UTF8Encoding]::new($false))
Write-Host 'PASS: modified configuration is detected, never silently regenerated.'
$certPath = Join-Path $private 'tls-cert.pem'
$originalCert = [IO.File]::ReadAllText($certPath)
[IO.File]::WriteAllText($certPath, 'invalid synthetic certificate')
$result = Invoke-Setup $local
if ($result.code -eq 0 -or $result.output -notmatch 'TLS identity verification failed') { throw 'Changed certificate must fail closed.' }
[IO.File]::WriteAllText($certPath, $originalCert, [Text.UTF8Encoding]::new($false))
Write-Host 'PASS: invalid/changed TLS identity is detected without generating a key.'
# Weak input permissions are rejected before any local identity is generated.
$weakAcl = [Security.AccessControl.FileSecurity]::new()
$everyone = [Security.Principal.SecurityIdentifier]::new('S-1-1-0')
$weakAcl.SetOwner($sid); $weakAcl.SetAccessRuleProtection($true, $false)
$weakAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
$weakAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($everyone, 'Read', 'Allow'))
# Set-Acl requests SACL privileges for this multi-ACE test descriptor on this host;
# persist only the explicitly changed DACL/owner through the supported .NET API.
[IO.File]::SetAccessControl($routePath, $weakAcl)
$weakLocal = Join-Path $testRoot 'weak'
$null = New-Item -ItemType Directory -Path $weakLocal
$result = Invoke-Setup $weakLocal -Initialize
if ($result.code -eq 0 -or $result.output -notmatch 'Private owner-only ACL required' -or
    (Test-Path -LiteralPath (Join-Path $weakLocal 'DSHMobile/host'))) { throw 'Weak secret input ACL must be rejected before writes.' }
$restoreAcl = [Security.AccessControl.FileSecurity]::new()
$restoreAcl.SetOwner($sid); $restoreAcl.SetAccessRuleProtection($true, $false)
$restoreAcl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new($sid, 'FullControl', 'Allow'))
[IO.File]::SetAccessControl($routePath, $restoreAcl)
Write-Host 'PASS: weak route credential ACL is rejected without private state writes.'
foreach ($invalidUrl in @('ws://relay.example.invalid/dsh-mobile-relay','wss://relay.example.invalid/a/../b','wss://relay.example.invalid/a?token=x','wss://user@relay.example.invalid/a','wss://relay.example.invalid/%2e%2e')) {
    $result = Invoke-Setup $weakLocal -Initialize -Relay $invalidUrl
    if ($result.code -eq 0 -or $result.output -notmatch 'Relay URL must be canonical production WSS' -or
        (Test-Path -LiteralPath (Join-Path $weakLocal 'DSHMobile/host'))) { throw 'Unsafe relay URL accepted.' }
}
Write-Host 'PASS: WS, userinfo, query, normalized dot and escaped relay URL variants rejected before writes.'
$result = Invoke-Setup $weakLocal -Initialize -RouteFile 'relative-route.json'
if ($result.code -eq 0 -or $result.output -notmatch 'Only absolute local paths' -or
    (Test-Path -LiteralPath (Join-Path $weakLocal 'DSHMobile/host'))) { throw 'Relative route path accepted.' }
Write-Host 'PASS: relative private credential path rejected before writes.'
$redirectLocal = Join-Path $testRoot 'redirect'
$redirectParent = Join-Path $redirectLocal 'DSHMobile'
$redirectTarget = Join-Path $testRoot 'redirect-target'
$null = New-Item -ItemType Directory -Path $redirectLocal,$redirectTarget
$null = New-Item -ItemType Junction -Path $redirectParent -Target $redirectTarget
$result = Invoke-Setup $redirectLocal -Initialize
if ($result.code -eq 0 -or $result.output -notmatch 'Reparse or redirected paths' -or
    @(Get-ChildItem -LiteralPath $redirectTarget -Force).Count -ne 0) { throw 'Redirected private directory accepted.' }
Write-Host 'PASS: reparse-point host ancestor rejected without writing through junction.'
$result = Invoke-Setup $local -Initialize
if ($result.code -eq 0 -or $result.output -notmatch 'Host setup already exists') { throw 'Repeat explicit initialization must fail.' }
Write-Host 'PASS: repeat initialization refuses to regenerate existing TLS identity.'
$result = Invoke-Setup $local
if ($result.code -ne 0) { throw 'Final identity/config verification failed.' }
Write-Host 'All isolated remote-setup checks passed; no active profile or production state was changed.'
