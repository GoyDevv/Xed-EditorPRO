$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Base = Split-Path -Parent $MyInvocation.MyCommand.Path
$State = Join-Path $Base '.orthodox-drive-downloader'
$Bin = Join-Path $State 'rclone'
$Logs = Join-Path $State 'logs'
$Config = Join-Path $State 'rclone.conf'
$Dest = Join-Path ([Environment]::GetFolderPath('Desktop')) 'Orthodox PDF Collection'
$Remote = 'gdrive'
$Folder = 'Orthodox PDF Collection'
$Source = "$Remote,shared_with_me:$Folder"

New-Item -ItemType Directory -Force -Path $State,$Bin,$Logs | Out-Null
$env:RCLONE_CONFIG = $Config

function Step($s){ Write-Host ''; Write-Host "==> $s" -ForegroundColor Cyan }
function Warn($s){ Write-Host "    WARNING: $s" -ForegroundColor Yellow }
function Die($s){ Write-Host ''; Write-Host "ERROR: $s" -ForegroundColor Red; Write-Host "Logs: $Logs"; exit 1 }

function Get-Rclone {
  $p = Get-Command rclone.exe -ErrorAction SilentlyContinue
  if($p){ return $p.Source }
  $local = Join-Path $Bin 'rclone.exe'
  if(Test-Path $local){ return $local }
  Step 'Downloading the current official rclone Windows build'
  $arch = if([Environment]::Is64BitOperatingSystem){
    if($env:PROCESSOR_ARCHITECTURE -match 'ARM64' -or $env:PROCESSOR_IDENTIFIER -match 'ARM'){ 'arm64' } else { 'amd64' }
  } else { '386' }
  $zip = Join-Path $Bin 'rclone.zip'
  $tmp = Join-Path $Bin 'extract'
  $url = "https://downloads.rclone.org/rclone-current-windows-$arch.zip"
  Remove-Item $zip,$tmp -Recurse -Force -ErrorAction SilentlyContinue
  Invoke-WebRequest $url -OutFile $zip -UseBasicParsing
  Expand-Archive $zip -DestinationPath $tmp -Force
  $exe = Get-ChildItem $tmp -Filter rclone.exe -File -Recurse | Select-Object -First 1
  if(-not $exe){ Die 'rclone.exe was not found after extraction.' }
  Copy-Item $exe.FullName $local -Force
  Remove-Item $zip,$tmp -Recurse -Force -ErrorAction SilentlyContinue
  return $local
}

$Rclone = Get-Rclone
Write-Host (& $Rclone version | Select-Object -First 1) -ForegroundColor DarkGray

function Test-Source {
  & $Rclone lsf $Source --max-depth 1 --files-only 1>$null 2> (Join-Path $Logs 'source-test.log')
  return ($LASTEXITCODE -eq 0)
}

function Get-AuthToken([string]$ClientId='', [string]$ClientSecret='') {
  $out = Join-Path $Logs 'oauth.log'
  $err = Join-Path $Logs 'oauth.err'
  Remove-Item $out,$err -Force -ErrorAction SilentlyContinue

  $args = @('authorize','drive','--auth-no-open-browser')
  if($ClientId -and $ClientSecret){ $args = @('authorize','drive',$ClientId,$ClientSecret,'--auth-no-open-browser') }

  $p = Start-Process -FilePath $Rclone -ArgumentList $args -RedirectStandardOutput $out -RedirectStandardError $err -PassThru

  for($i=0;$i -lt 180 -and -not $p.HasExited;$i++){
    Start-Sleep 1
    $t = ''
    if(Test-Path $out){ $t += (Get-Content $out -Raw -ErrorAction SilentlyContinue) }
    if(Test-Path $err){ $t += (Get-Content $err -Raw -ErrorAction SilentlyContinue) }

    if($t){
      $m = [regex]::Match($t,'https?://127\.0\.0\.1:\d+/auth(?:\?state=\S+)?')
      if($m.Success){
        Write-Host '    Opening Google authorization in the default browser...'
        Start-Process $m.Value
        break
      }
    }
  }

  $p.WaitForExit()
  $all = (Get-Content $out -Raw -ErrorAction SilentlyContinue) + (Get-Content $err -Raw -ErrorAction SilentlyContinue)
  $m2 = [regex]::Match($all,'(?s)Paste the following into your remote machine\s*--->\s*(?<token>.*?)\s*<---End paste')
  if(-not $m2.Success){ return $null }
  return (($m2.Groups['token'].Value) -replace '\s','').Trim()
}

function Configure-Drive {
  Step 'Google Drive authorization'
  $token = Get-AuthToken

  if(-not $token){
    Warn 'The first OAuth attempt did not complete.'
    Warn 'rclone says its shared Google client is being retired during 2026.'
    Write-Host ''
    $id = Read-Host 'Google OAuth Client ID (press Enter to stop)'
    if(-not $id){ Die 'OAuth authorization failed. See oauth.log and oauth.err.' }
    $secret = Read-Host 'Google OAuth Client Secret'
    if(-not $secret){ Die 'OAuth client secret was empty.' }
    $token = Get-AuthToken $id $secret
    if(-not $token){
      Get-Content (Join-Path $Logs 'oauth.log') -ErrorAction SilentlyContinue
      Get-Content (Join-Path $Logs 'oauth.err') -ErrorAction SilentlyContinue
      Die 'OAuth authorization failed.'
    }
    & $Rclone config create $Remote drive scope drive token $token shared_with_me true client_id $id client_secret $secret | Out-File (Join-Path $Logs 'config-create.log') -Encoding utf8
  } else {
    & $Rclone config create $Remote drive scope drive token $token shared_with_me true | Out-File (Join-Path $Logs 'config-create.log') -Encoding utf8
  }

  if($LASTEXITCODE -ne 0){ Die 'Could not create the rclone Google Drive remote.' }
  Remove-Item (Join-Path $Logs 'oauth.log'),(Join-Path $Logs 'oauth.err') -Force -ErrorAction SilentlyContinue
}

if(-not (Test-Path $Config) -or -not (Test-Source)){
  Configure-Drive
}
if(-not (Test-Source)){
  Write-Host ''
  Write-Host 'Top-level Shared with me entries visible to rclone:' -ForegroundColor Yellow
  & $Rclone lsd "$Remote,shared_with_me:" --max-depth 1
  Die "Cannot access '$Folder' in Shared with me."
}

New-Item -ItemType Directory -Force -Path $Dest | Out-Null

Step 'Building the complete source inventory'
$SourceList = Join-Path $State 'source_files.txt'
& $Rclone lsf $Source -R --files-only | Set-Content $SourceList -Encoding utf8
if($LASTEXITCODE -ne 0){ Die 'Could not list the source folder.' }

$src = @(Get-Content $SourceList)
$pdfs = @($src | Where-Object { $_ -match '(?i)\.pdf$' })
Write-Host "    Source files: $($src.Count)"
Write-Host "    PDFs:         $($pdfs.Count)"

for($pass=1;$pass -le 3;$pass++){
  Step "Download + verification pass $pass/3"
  $copyLog = Join-Path $Logs "copy-$pass.log"
  & $Rclone copy $Source $Dest --transfers 4 --checkers 8 --retries 10 --low-level-retries 20 --retries-sleep 10s --stats 15s --stats-one-line --create-empty-src-dirs --drive-stop-on-download-limit --log-file $copyLog --log-level INFO -P
  if($LASTEXITCODE -ne 0){ Warn "Copy returned $LASTEXITCODE. Verification will decide what remains." }

  $combined = Join-Path $State 'check_combined.txt'
  $checkLog = Join-Path $Logs "check-$pass.log"
  Remove-Item $combined -Force -ErrorAction SilentlyContinue
  & $Rclone check $Source $Dest --one-way --combined $combined --checkers 8 --stats 15s *> $checkLog

  if($LASTEXITCODE -eq 0){
    Write-Host '    VERIFIED: source and destination match.' -ForegroundColor Green
    break
  }
  if($pass -lt 3){ Warn 'Verification found unresolved files. Re-running the copy.' }
}

Step 'Final inventory'
$DestList = Join-Path $State 'destination_files.txt'
& $Rclone lsf $Dest -R --files-only | Set-Content $DestList -Encoding utf8
$dst = @(Get-Content $DestList)
$dstPdfs = @($dst | Where-Object { $_ -match '(?i)\.pdf$' })

Write-Host ''
Write-Host "Source files: $($src.Count)"
Write-Host "Local files:  $($dst.Count)"
Write-Host "Source PDFs:  $($pdfs.Count)"
Write-Host "Local PDFs:   $($dstPdfs.Count)"
Write-Host ''
Write-Host 'Downloaded to:' -ForegroundColor Cyan
Write-Host $Dest
Write-Host ''
Write-Host "Reports: $State" -ForegroundColor Cyan

$check = Join-Path $State 'check_combined.txt'
if(Test-Path $check){
  $bad = @(Get-Content $check | Where-Object { $_ -match '^[+*!] ' })
  if($bad.Count){
    Write-Host ''
    Write-Host "UNRESOLVED FILES: $($bad.Count)" -ForegroundColor Yellow
    Write-Host "See $check"
    exit 2
  }
}

Write-Host ''
Write-Host 'DONE: no unresolved files were reported by rclone.' -ForegroundColor Green
exit 0
