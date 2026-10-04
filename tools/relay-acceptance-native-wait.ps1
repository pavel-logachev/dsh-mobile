param([ValidateSet('ui','status')][string]$Kind='ui',[string]$Expected,[string]$StatusPath,[ValidateSet('promptCount','connected','background','lostResponse','receiptAccepted')][string]$Condition='connected',[int]$Count=1,[int]$TimeoutSeconds=25)
$ErrorActionPreference='Stop';$deadline=(Get-Date).AddSeconds($TimeoutSeconds)
do {
 if($Kind -eq 'ui') {
  $raw=& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'relay-acceptance-native.ps1') -Action inspect 2>$null
  if($LASTEXITCODE -eq 0 -and $raw) {
   try {$nodes=($raw|Out-String)|ConvertFrom-Json;if(@($nodes|Where-Object {$_.Label -eq $Expected}).Count -gt 0){Write-Output "Native checkpoint observed: $Expected";exit 0}}catch{}
  }
 }else{
  try{
   $s=Get-Content -Raw -LiteralPath $StatusPath|ConvertFrom-Json
   $ok=switch($Condition){
    'promptCount' {$s.calls.prompt.Count -eq $Count}
    'connected' {$s.deviceGrantsPublished -eq 1 -and $s.deviceCapabilityOpens -gt 0 -and $s.bootstrapOpens -eq 1}
    'background' {$s.relay.active -eq 0 -and $s.relay.pending -eq 0 -and $s.calls.prompt.Count -eq $Count}
    'lostResponse' {$s.fault.droppedResponses -eq 1 -and $s.calls.prompt.Count -eq 2}
    'receiptAccepted' {$s.commandReceipts.Count -eq $Count -and @($s.commandReceipts|Where-Object {$_.status -ne 'accepted'}).Count -eq 0}
   }
   if($ok){Write-Output "Fixture checkpoint observed: $Condition";exit 0}
  }catch{}
 }
 Start-Sleep -Milliseconds 200
}while((Get-Date) -lt $deadline)
throw 'Acceptance checkpoint deadline exceeded; details redacted'
