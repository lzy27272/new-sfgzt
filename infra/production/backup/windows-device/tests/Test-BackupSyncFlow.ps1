#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $sourceRoot 'BackupPull.Common.ps1')
. (Join-Path $sourceRoot 'BackupSystem.Common.ps1')
$script:passed=0
function Assert-True([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw ('FAIL: ' + $Label) }
    $script:passed++; Write-Host ('PASS: ' + $Label)
}
$tempParent=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$root=Join-Path $tempParent ('SfgBackupFlowTests-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $root -ItemType Directory | Out-Null
try {
    $program=New-BackupPullDirectory $root (Join-Path $root 'program')
    $remote=New-BackupPullDirectory $root (Join-Path $program '.fixture-remote')
    $stub=@'

# Test fixture overrides: no real server connection or credentials are used.
$script:FixtureAtomicWriter = ${function:Write-BackupPullJsonAtomic}
function Write-BackupPullJsonAtomic {
    param($Path,$Value)
    $flag=Join-Path $PSScriptRoot '.fixture-interrupt-atomic.txt'
    if (Test-Path -LiteralPath $flag) {
        $mode=[IO.File]::ReadAllText($flag)
        if (($mode -ceq 'BEFORE_BUNDLE' -and $Path.EndsWith('accepted-bundle.json')) -or
            ($mode -ceq 'BETWEEN_COMPATIBILITY' -and $Path.EndsWith('last-receipt.json'))) {
            throw ('Injected interruption: ' + $mode)
        }
    }
    & $script:FixtureAtomicWriter -Path $Path -Value $Value
}
function Get-BackupKeyFingerprint {
    param($KeyTool,$PublicKeyPath)
    if ($PublicKeyPath.EndsWith('known_hosts')) { return ('SHA256:' + ('S'*43)) }
    return ('SHA256:' + ('D'*43))
}
function Invoke-BackupSftpGet {
    param($Connection,$BackupRoot,$KeyPath,$KnownHostsPath,$Transfers)
    $fixtureRemote=Join-Path $PSScriptRoot '.fixture-remote'
    foreach($transfer in $Transfers) {
        $source=Assert-BackupPullPath $fixtureRemote (Join-Path $fixtureRemote $transfer.Remote.TrimStart('/').Replace('/','\'))
        $destination=Assert-BackupPullPath $BackupRoot (Join-Path $BackupRoot $transfer.Local.Replace('/','\'))
        [IO.File]::Copy($source,$destination,$true)
    }
}
'@
    $commonText=[IO.File]::ReadAllText((Join-Path $sourceRoot 'BackupPull.Common.ps1'),[Text.Encoding]::UTF8)
    [IO.File]::WriteAllText((Join-Path $program 'BackupPull.Common.ps1'),$commonText+$stub,[Text.UTF8Encoding]::new($true))
    [IO.File]::Copy((Join-Path $sourceRoot 'Invoke-BackupPull.ps1'),(Join-Path $program 'Invoke-BackupPull.ps1'))
    $device=[guid]::NewGuid().ToString()
    Write-BackupPullJsonAtomic (Join-Path $root '.sfg-backup-device.json') ([pscustomobject]@{
        kind='SFG_BACKUP_DEVICE_V1';deviceId=$device;backupRoot=$root })
    Write-BackupPullJsonAtomic (Join-Path $program 'backup-connection.json') ([pscustomobject]@{
        kind='SFG_BACKUP_CONNECTION_V1';schemaVersion=1;deviceId=$device;backupRoot=$root
        scope='ENCRYPTED_BACKUP_DOWNLOAD_ONLY';host='127.0.0.1';port=22;username='sfg-backup-pull'
        deviceKeyFingerprint=('SHA256:'+('D'*43));serverHostKeyFingerprint=('SHA256:'+('S'*43)) })
    $paths=@('hotel-ai-os/daily/hotel_ai_os-daily-20260922T030000+0800.dump.enc',
        'sifangguan-ota/daily/sifangguan-ota-state-daily-20260922T030000+0800.tar.gz.enc')
    $entries=@()
    foreach($path in $paths) {
        $file=Join-Path (Join-Path $remote 'data') $path.Replace('/','\')
        New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($file)) | Out-Null
        [IO.File]::WriteAllText($file,'identical test-only backup payload',[Text.Encoding]::ASCII)
        $parts=$path.Split('/')
        $entries += [pscustomobject]@{path=$path;category=$parts[0];tier=$parts[1];bytes=(Get-Item -LiteralPath $file).Length
            sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $manifestId='a'*64
    Write-BackupPullJsonAtomic (Join-Path $remote 'manifest-v1.json') ([pscustomobject]@{
        kind='SFG_BACKUP_MANIFEST_V1';schemaVersion=1;manifestId=$manifestId;generatedAt=[DateTime]::UtcNow.ToString('o')
        receiptPath=('receipts/'+$manifestId+'.ok');entries=$entries })
    Write-BackupPullJsonAtomic (Join-Path $remote ('receipts\'+$manifestId+'.ok')) ([pscustomobject]@{
        kind='SFG_BACKUP_RECEIPT_TOKEN_V1';manifestId=$manifestId })
    $historic=Join-Path $root 'data\hotel-ai-os\daily\hotel_ai_os-daily-20260901T030000+0800.dump.enc'
    New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($historic)) | Out-Null
    [IO.File]::WriteAllText($historic,'historic backup to preserve')
    $quarantine=New-BackupPullDirectory $root (Join-Path $root ('quarantine\20260801T000000Z-'+('b'*32)))
    $quarantineFile=Join-Path $quarantine 'keep.txt'
    [IO.File]::WriteAllText($quarantineFile,'old quarantined version to preserve')
    [IO.Directory]::SetCreationTimeUtc($quarantine,[DateTime]::UtcNow.AddDays(-30))

    $powerShell=Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $run=[guid]::NewGuid()
    $arguments=@('-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-File',
        (Join-Path $program 'Invoke-BackupPull.ps1'),'-BackupRoot',$root,'-RunId',$run.ToString(),'-PreserveExistingBackups')
    $first=Invoke-BackupNativeProcess $powerShell $arguments '' 30
    $result=Read-BackupPullJson (Join-Path $root '同步结果.json')
    Assert-True ($first.exitCode -eq 0 -and $result.status -ceq 'SUCCESS' -and $result.receiptVerified) 'Actual sync program completes the fixture download, hashes and receipt'
    Assert-True ($result.downloadedEntries -eq 2 -and $result.totalEntries -eq 2) 'Equal-content files retain distinct paths and both verify'
    Assert-True ($result.runId -ceq $run.ToString() -and $result.executionIdentitySid -ceq [Security.Principal.WindowsIdentity]::GetCurrent().User.Value) 'Report records actual execution identity and caller run ID'
    Assert-True ((Test-Path -LiteralPath $historic) -and (Test-Path -LiteralPath $quarantineFile)) 'Repair verification does not move or delete historical backup versions'
    $second=Invoke-BackupNativeProcess $powerShell $arguments '' 30
    $result=Read-BackupPullJson (Join-Path $root '同步结果.json')
    if ($second.exitCode -ne 0) { Write-Host ($result | ConvertTo-Json -Compress) }
    Assert-True ($second.exitCode -eq 0 -and $result.downloadedEntries -eq 0) 'Repeated sync reuses already hash-verified backups'

    $bundlePath=Join-Path $root 'control\accepted-bundle.json'
    $originalBundle=[IO.File]::ReadAllText($bundlePath)
    $oldBundle=Read-BackupPullJson $bundlePath
    Assert-True ($oldBundle.kind -ceq 'SFG_ACCEPTED_BACKUP_BUNDLE_V1' -and
        $oldBundle.manifest.manifestId -ceq $oldBundle.receipt.manifestId) 'One atomic bundle contains the accepted manifest and its matching receipt'
    $newEntries=@()
    foreach($entry in $entries) {
        $newPath=([string]$entry.path).Replace('20260922','20260923')
        $file=Join-Path (Join-Path $remote 'data') $newPath.Replace('/','\')
        New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($file)) | Out-Null
        [IO.File]::WriteAllText($file,'new immutable backup fixture',[Text.Encoding]::ASCII)
        $newEntries += [pscustomobject]@{path=$newPath;category=$entry.category;tier=$entry.tier;bytes=(Get-Item -LiteralPath $file).Length
            sha256=(Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()}
    }
    $newId='b'*64
    Write-BackupPullJsonAtomic (Join-Path $remote 'manifest-v1.json') ([pscustomobject]@{
        kind='SFG_BACKUP_MANIFEST_V1';schemaVersion=1;manifestId=$newId;generatedAt=[DateTime]::UtcNow.ToString('o')
        receiptPath=('receipts/'+$newId+'.ok');entries=$newEntries })
    Write-BackupPullJsonAtomic (Join-Path $remote ('receipts\'+$newId+'.ok')) ([pscustomobject]@{
        kind='SFG_BACKUP_RECEIPT_TOKEN_V1';manifestId=$newId })
    $flag=Join-Path $program '.fixture-interrupt-atomic.txt'
    [IO.File]::WriteAllText($flag,'BEFORE_BUNDLE')
    $normalArguments=@($arguments | Where-Object { $_ -cne '-PreserveExistingBackups' })
    $beforeCommit=Invoke-BackupNativeProcess $powerShell $normalArguments '' 30
    $beforeResult=Read-BackupPullJson (Join-Path $root '同步结果.json')
    Assert-True ($beforeCommit.exitCode -eq 1 -and $beforeResult.stage -ceq 'COMMIT_ACCEPTED_BUNDLE' -and
        [IO.File]::ReadAllText($bundlePath) -ceq $originalBundle) 'Interruption before bundle replacement preserves the old complete authoritative pair'
    foreach($entry in @($oldBundle.manifest.entries)) {
        $file=Join-Path (Join-Path $root 'data') ([string]$entry.path).Replace('/','\')
        Assert-True ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ceq [string]$entry.sha256) 'Old accepted dependency remains byte-exact before atomic commit'
    }
    [IO.File]::WriteAllText($flag,'BETWEEN_COMPATIBILITY')
    $afterCommit=Invoke-BackupNativeProcess $powerShell $normalArguments '' 30
    $afterResult=Read-BackupPullJson (Join-Path $root '同步结果.json')
    $newBundle=Read-BackupPullJson $bundlePath
    $legacyManifest=Read-BackupPullJson (Join-Path $root 'control\accepted-manifest.json')
    $legacyReceipt=Read-BackupPullJson (Join-Path $root 'control\last-receipt.json')
    Assert-True ($afterCommit.exitCode -eq 1 -and $afterResult.stage -ceq 'WRITE_COMPATIBILITY_FILES' -and
        $newBundle.manifest.manifestId -ceq $newId -and $newBundle.receipt.manifestId -ceq $newId -and
        $legacyManifest.manifestId -ceq $newId -and $legacyReceipt.manifestId -ceq $manifestId) 'Interruption between compatibility files leaves a valid new atomic bundle despite the torn legacy pair'
    foreach($entry in @($newBundle.manifest.entries)) {
        $file=Join-Path (Join-Path $root 'data') ([string]$entry.path).Replace('/','\')
        Assert-True ((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant() -ceq [string]$entry.sha256) 'New committed dependency is byte-exact after atomic commit'
    }
    Assert-True ((Test-Path -LiteralPath $historic) -and (Test-Path -LiteralPath $quarantineFile)) 'Neither interrupted acceptance proceeds to retention cleanup'
    [IO.File]::Delete($flag)
    Write-BackupPullJsonAtomic (Join-Path $remote 'manifest-v1.json') ([pscustomobject]@{
        kind='SFG_BACKUP_MANIFEST_V1';schemaVersion=1;manifestId=$manifestId;generatedAt=[DateTime]::UtcNow.ToString('o')
        receiptPath=('receipts/'+$manifestId+'.ok');entries=$entries })

    # A missing receipt must not prune historical roots or their dependency
    # objects, even without the repair-only PreserveExistingBackups switch.
    $receiptPath=Join-Path $remote ('receipts\'+$manifestId+'.ok')
    [IO.File]::Delete($receiptPath)
    $normalArguments=@($arguments | Where-Object { $_ -cne '-PreserveExistingBackups' })
    $noReceipt=Invoke-BackupNativeProcess $powerShell $normalArguments '' 30
    $failedReceipt=Read-BackupPullJson (Join-Path $root '同步结果.json')
    Assert-True ($noReceipt.exitCode -eq 1 -and $failedReceipt.stage -ceq 'VERIFY_RECEIPT') 'Missing receipt fails before cleanup'
    Assert-True ((Test-Path -LiteralPath $historic) -and (Test-Path -LiteralPath $quarantineFile)) 'Failed receipt preserves old backup and old quarantine history'
    Write-BackupPullJsonAtomic $receiptPath ([pscustomobject]@{kind='SFG_BACKUP_RECEIPT_TOKEN_V1';manifestId=$manifestId})

    $localBad=Join-Path (Join-Path $root 'data') $paths[0].Replace('/','\')
    $remoteBad=Join-Path (Join-Path $remote 'data') $paths[0].Replace('/','\')
    [IO.File]::WriteAllText($localBad,'damaged local fixture')
    [IO.File]::WriteAllText($remoteBad,'invalid downloaded fixture')
    $bad=Invoke-BackupNativeProcess $powerShell $arguments '' 30
    $failed=Read-BackupPullJson (Join-Path $root '同步结果.json')
    Assert-True ($bad.exitCode -eq 1 -and $failed.status -ceq 'FAILED' -and $failed.stage -ceq 'VERIFY_BACKUPS') 'Bad hash cannot generate a successful backup report'
    Assert-True ([IO.File]::ReadAllText($localBad) -ceq 'damaged local fixture') 'Invalid download never replaces the pre-existing local file'

    # Only the native boundary is mocked here; the production SFTP wrapper must
    # persist all error output and remove only its own temporary batch file.
    function Invoke-BackupNativeProcess {
        param($Executable,$Arguments,$WorkingDirectory,$TimeoutSeconds)
        [pscustomobject]@{exitCode=255;timedOut=$false;stdout='fixture stdout';stderr="first error`nlast error"}
    }
    $caught=$false
    try { Invoke-BackupSftpGet ([pscustomobject]@{port=22;username='fixture';host='127.0.0.1'}) $root 'fixture-key' 'fixture-hosts' @(
        [pscustomobject]@{Remote='/manifest-v1.json';Local='control/probe.json'}) } catch { $caught=$true }
    $errorLog=[IO.File]::ReadAllText((Join-Path $root 'logs\connection-error-last.log'))
    Assert-True ($caught -and $errorLog.Contains('first error') -and $errorLog.Contains('last error')) 'SFTP failure retains the full native reason in a bounded diagnostic log'
    Assert-True (@(Get-ChildItem -LiteralPath (Join-Path $root 'control') -Filter 'sftp-*.txt').Count -eq 0) 'Failed SFTP invocation removes its own temporary batch file'
    Write-Host ('TOTAL PASSED: ' + $script:passed)
} finally {
    $resolved=[IO.Path]::GetFullPath($root)
    if ([IO.Path]::GetDirectoryName($resolved) -cne $tempParent -or
        [IO.Path]::GetFileName($resolved) -notmatch '^SfgBackupFlowTests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
