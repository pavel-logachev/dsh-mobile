param([ValidateSet('inspect','click','type','key','capture','gates')][string]$Action='inspect', [string]$Selector, [string]$Text, [int]$Key, [string]$CapturePath, [string]$AdbPath)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$candidates=@($AdbPath)
if(-not $AdbPath){
 foreach($sdk in @($env:ANDROID_HOME,$env:ANDROID_SDK_ROOT)){if($sdk){$candidates+=Join-Path $sdk 'platform-tools/adb.exe'}}
 if($env:LOCALAPPDATA){$candidates+=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'}
}
$adb=$candidates | Where-Object {$_ -and (Test-Path -LiteralPath $_ -PathType Leaf)} | Select-Object -First 1
if(-not $adb){throw 'Installed adb required; supply -AdbPath or ANDROID_HOME'}
$serial='emulator-5580'
function Adb([string[]]$Arguments) { $saved=$ErrorActionPreference; $ErrorActionPreference='Continue'; $result=& $adb -s $serial @Arguments 2>&1; $code=$LASTEXITCODE; $ErrorActionPreference=$saved; if($code -ne 0){throw 'Native command failed (redacted)'}; return $result }
if((Adb @('shell','getprop','ro.kernel.qemu')) -ne '1'){throw 'Disposable QEMU only'}
if($Action -eq 'key'){Adb @('shell','input','keyevent',"$Key") | Out-Null; exit}
if($Action -eq 'capture'){
 $owned=[IO.Path]::GetFullPath((Join-Path $root 'artifacts/relay-acceptance'))+[IO.Path]::DirectorySeparatorChar
 if(-not $CapturePath -or -not [IO.Path]::GetFullPath($CapturePath).StartsWith($owned,[StringComparison]::OrdinalIgnoreCase)){throw 'Owned artifact path required'}
 $walk=[IO.Path]::GetFullPath($CapturePath)
 while($walk){
  if((Test-Path -LiteralPath $walk) -and ((Get-Item -LiteralPath $walk -Force).Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Redirected capture path refused'}
  $parent=[IO.DirectoryInfo]::new($walk).Parent; $walk=if($parent){$parent.FullName}else{$null}
 }
 Adb @('shell','screencap','-p','/sdcard/dsh-relay-capture.png')|Out-Null
 Adb @('pull','/sdcard/dsh-relay-capture.png',$CapturePath)|Out-Null
 Adb @('shell','rm','-f','/sdcard/dsh-relay-capture.png')|Out-Null
 Get-FileHash $CapturePath -Algorithm SHA256 | Select-Object Hash
 exit
}
Adb @('shell','uiautomator','dump','/sdcard/dsh-relay-ui.xml') | Out-Null
try { [xml]$xml=(Adb @('exec-out','cat','/sdcard/dsh-relay-ui.xml') | Out-String) }
finally { Adb @('shell','rm','-f','/sdcard/dsh-relay-ui.xml') | Out-Null }
$allowed=@('Open chats and settings','Message your computer','Send message','Check delivery','Access revoked','Syncing with computer…','Connecting…','DSH_MOBILE_RELAY_ACCEPTANCE: synthetic opaque relay prompt.','DSH_MOBILE_RELAY_LOST_RESPONSE: synthetic receipt reconciliation prompt.','Connect to computer','Connect securely','Trust and connect','Back','Import invitation file','Downloads','Download','dsh-relay-invitation.json','Review computer identity','Connect','Chats','New chat','Create chat','Demo workspace','Synthetic example','Demo conversation','Connected','Offline','Reconnect','Settings','Send','Message','Check delivery status','Try reconnecting','Phone name','Synthetic demo answer. No model was called.','This is a synthetic conversation. No model or DSH runtime is connected.')
$nodes=@($xml.SelectNodes('//node') | Where-Object { $allowed -contains $_.text -or $allowed -contains $_.'content-desc' -or $_.'resource-id' -match '^(pairing_import|pairing_preview|pairing_connect|chat_drawer|new_chat|workspace_picker|workspace_demo|session_demo-session|create_chat|message_input|send_message|reconnect|resolve_pending|settings|connection_state)$' -or $_.class -eq 'android.widget.EditText' })
if($Action -eq 'gates'){
 $nodes | Where-Object { $_.text -in @('New chat','Send message','Synthetic example') -or $_.'content-desc' -eq 'Send message' } | ForEach-Object {
  $node=$_; $label=if($node.text){$node.text}else{$node.'content-desc'}
  $chain=@(); $current=$node
  while($current -and $current.Name -eq 'node'){$chain+=([pscustomobject]@{Class=$current.class;Enabled=$current.enabled;Clickable=$current.clickable;Bounds=$current.bounds});$current=$current.ParentNode}
  [pscustomobject]@{Label=$label;ControlAncestors=$chain}
 } | ConvertTo-Json -Depth 5
 exit
}
if($Action -eq 'inspect'){
 $nodes | ForEach-Object { $label=if($allowed -contains $_.text){$_.text}elseif($allowed -contains $_.'content-desc'){$_.'content-desc'}else{''}; [pscustomobject]@{Label=$label;Tag=$_.'resource-id';Class=$_.class;Bounds=$_.bounds;Enabled=$_.enabled;Clickable=$_.clickable} } | ConvertTo-Json -Depth 3
 exit
}
$matches=@($nodes | Where-Object {$_.text -eq $Selector -or $_.'content-desc' -eq $Selector -or $_.'resource-id' -eq $Selector -or ($Selector -eq 'editable' -and $_.class -eq 'android.widget.EditText')})
if($matches.Count -ne 1){throw "Expected one allowlisted selector; found $($matches.Count)"}
$node=$matches[0]
if($node.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$'){throw 'Missing native bounds'}
$x=[int]( ([int]$Matches[1]+[int]$Matches[3])/2 ); $y=[int]( ([int]$Matches[2]+[int]$Matches[4])/2 )
Adb @('shell','input','tap',"$x","$y")|Out-Null
if($Action -eq 'type'){
 if($Text -notin @('DSH_MOBILE_RELAY_ACCEPTANCE: synthetic opaque relay prompt.','DSH_MOBILE_RELAY_LOST_RESPONSE: synthetic receipt reconciliation prompt.','DSH_MOBILE_UNSENT_DRAFT_V1')){throw 'Exact synthetic text only'}
 # Ctrl+A and replacement through native input; no invitation ever travels here.
 Adb @('shell','input','keycombination','113','29')|Out-Null
 Adb @('shell','input','text',($Text.Replace(' ','%s')))|Out-Null
}
