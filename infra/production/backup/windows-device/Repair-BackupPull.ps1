#requires -Version 5.1
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$reportPath = Join-Path $PSScriptRoot 'repair-result.json'
$repair = [ordered]@{
    kind='SFG_BACKUP_REPAIR_RESULT_V1'; schemaVersion=1; status='FAILED'
    startedAt=[DateTime]::UtcNow.ToString('o'); phase='PREFLIGHT'
    configurationUpdated=$false; scheduleUnchanged=$false; privateKeyReplaced=$false
    backupRetentionCleanupEnabled=$false
}
$lockStream = $null
try {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw '请关闭窗口，右键 03-Repair-BackupPull.cmd，选择“以管理员身份运行”。'
    }
    # Verify every package source before importing or copying it.
    $manifestPath = Join-Path $PSScriptRoot 'repair-package-manifest.json'
    $manifestFile = Get-Item -LiteralPath $manifestPath -Force
    if ($manifestFile.PSIsContainer -or $manifestFile.Length -gt 65536 -or
        ($manifestFile.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw '修复包清单异常。' }
    $manifest = [IO.File]::ReadAllText($manifestPath, [Text.Encoding]::UTF8) | ConvertFrom-Json
    $expectedFiles = @('03-Repair-BackupPull.cmd','Repair-BackupPull.ps1','BackupPull.Common.ps1',
        'BackupSystem.Common.ps1','Invoke-BackupPull.ps1','REPAIR-README.txt')
    if ($manifest.kind -cne 'SFG_BACKUP_REPAIR_PACKAGE_V1' -or [int]$manifest.schemaVersion -ne 1 -or
        @($manifest.files).Count -ne $expectedFiles.Count) { throw '修复包版本或文件数量不匹配。' }
    $seen = @{}
    foreach ($file in @($manifest.files)) {
        $name = [string]$file.name
        if ($name -notin $expectedFiles -or $seen.ContainsKey($name)) { throw '修复包包含重复或越界条目。' }
        $path = Join-Path $PSScriptRoot $name
        $item = Get-Item -LiteralPath $path -Force
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $item.Length -ne [long]$file.bytes -or [string]$file.sha256 -cnotmatch '^[0-9a-f]{64}$' -or
            (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$file.sha256) {
            throw ('修复包文件校验失败：' + $name)
        }
        $seen[$name] = $true
    }
    . (Join-Path $PSScriptRoot 'BackupPull.Common.ps1')
    . (Join-Path $PSScriptRoot 'BackupSystem.Common.ps1')
    $root = 'E:\中台备份'
    Assert-BackupPullPath $root $root | Out-Null
    $program = Assert-BackupPullPath $root (Join-Path $root 'program')
    $marker = Read-BackupPullJson (Join-Path $root '.sfg-backup-device.json') 16384
    $connectionPath = Assert-BackupPullPath $root (Join-Path $program 'backup-connection.json')
    $connection = Read-BackupPullJson $connectionPath 32768
    if ($marker.kind -cne 'SFG_BACKUP_DEVICE_V1' -or $connection.kind -cne 'SFG_BACKUP_CONNECTION_V1' -or
        [int]$connection.schemaVersion -ne 1 -or $marker.deviceId -cne $connection.deviceId -or
        $marker.backupRoot -cne $root -or $connection.backupRoot -cne $root -or
        $connection.scope -cne 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY' -or $connection.host -cne '43.136.184.38' -or
        [int]$connection.port -ne 22 -or $connection.username -cne 'sfg-backup-pull' -or
        $connection.deviceKeyFingerprint -cne $manifest.deviceKeyFingerprint -or
        $connection.serverHostKeyFingerprint -cne $manifest.serverHostKeyFingerprint) {
        throw '这不是修复包登记的备用设备或服务器，已停止，不会覆盖。'
    }
    $keyPath = Assert-BackupPullPath $root (Join-Path $root 'identity\device_ed25519')
    $publicKeyPath = Assert-BackupPullPath $root ($keyPath + '.pub')
    $knownHosts = Assert-BackupPullPath $root (Join-Path $program 'known_hosts')
    $keyTool = Get-OpenSshTool 'ssh-keygen.exe'
    if ((Get-BackupKeyFingerprint $keyTool $publicKeyPath) -cne $manifest.deviceKeyFingerprint -or
        (Get-BackupKeyFingerprint $keyTool $knownHosts) -cne $manifest.serverHostKeyFingerprint) {
        throw '设备或服务器公钥指纹不匹配，拒绝继续。'
    }
    $taskName = 'Sifangguan Encrypted Backup Pull'
    $task = Get-ScheduledTask -TaskName $taskName
    if ($task.State -in @('Running','Queued','Disabled')) { throw '原备份任务正在运行、排队或已禁用。请确认任务状态后再修复。' }
    if ([string]$task.Principal.UserId -notin @('SYSTEM','NT AUTHORITY\SYSTEM','S-1-5-18') -or
        [string]$task.Principal.LogonType -ne 'ServiceAccount' -or @($task.Actions).Count -ne 1) {
        throw '原任务运行身份或动作不符合预期，未修改任务。'
    }
    $expectedPowerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $expectedArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' +
        (Join-Path $program 'Invoke-BackupPull.ps1') + '" -BackupRoot "' + $root + '"'
    if ([string]$task.Actions[0].Execute -ine $expectedPowerShell -or
        [string]$task.Actions[0].Arguments -cne $expectedArguments) {
        throw '原任务命令与已知安装配置不同，需要人工核对，未覆盖。'
    }
    $originalTaskXml = Export-ScheduledTask -TaskName $taskName
    $control = New-BackupPullDirectory $root (Join-Path $root 'control')
    $lockPath = Assert-BackupPullPath $root (Join-Path $control 'sync.lock')
    $lockStream = [IO.File]::Open($lockPath,[IO.FileMode]::OpenOrCreate,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
    $snapshot = New-BackupPullDirectory $root (Join-Path $control ('repair-' + [guid]::NewGuid().ToString('N')))
    $keyAcl = Get-Acl -LiteralPath $keyPath
    $sections = [Security.AccessControl.AccessControlSections]'Owner,Group,Access'
    [IO.File]::WriteAllText((Join-Path $snapshot 'private-key-permissions-before.txt'),
        $keyAcl.GetSecurityDescriptorSddlForm($sections), [Text.Encoding]::ASCII)
    [IO.File]::WriteAllText((Join-Path $snapshot 'daily-task-before.xml'),$originalTaskXml,[Text.UTF8Encoding]::new($true))
    # Snapshot only program files and permission metadata; never copy the key.
    foreach ($name in @('BackupPull.Common.ps1','Invoke-BackupPull.ps1')) {
        $destination = Assert-BackupPullPath $root (Join-Path $program $name)
        $entry = Get-Item -LiteralPath $destination -Force
        if ($entry.PSIsContainer -or $entry.Length -gt 1048576) { throw '原同步程序文件异常。' }
        [IO.File]::Copy($destination,(Join-Path $snapshot $name),$false)
    }
    $repair['snapshotDirectory'] = $snapshot
    $repair.phase = 'UPDATE_CLIENT'
    Write-Host '已保存原程序与权限配置。正在修复专用连接密钥权限和错误日志。' -ForegroundColor Cyan
    Set-BackupSystemKeyPermission $root
    foreach ($name in @('BackupPull.Common.ps1','Invoke-BackupPull.ps1')) {
        $destination = Assert-BackupPullPath $root (Join-Path $program $name)
        $stagingFile = Assert-BackupPullPath $root ($destination + '.' + [guid]::NewGuid().ToString('N') + '.new')
        [IO.File]::Copy((Join-Path $PSScriptRoot $name),$stagingFile,$false)
        [IO.File]::Replace($stagingFile,$destination,[System.Management.Automation.Language.NullString]::Value)
        if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -cne
            (Get-FileHash -LiteralPath (Join-Path $PSScriptRoot $name) -Algorithm SHA256).Hash) {
            throw ('安装后的文件校验失败：' + $name)
        }
    }
    $repair.configurationUpdated = $true
    $lockStream.Dispose(); $lockStream = $null
    $repair.phase = 'SYSTEM_VERIFICATION'
    Write-Host '正在使用 SYSTEM 身份实际同步并逐文件校验；本次不执行历史版本清理。请等待。' -ForegroundColor Yellow
    $verification = Invoke-BackupSystemVerification $root
    $repair['verification'] = $verification
    $repair.scheduleUnchanged = ((Export-ScheduledTask -TaskName $taskName) -ceq $originalTaskXml)
    if (-not $repair.scheduleUnchanged) { throw '检测到原计划任务发生变化，未将修复标记为完成。' }
    $repair.status = $verification.status
    $repair['message'] = $verification.message
    if ($verification.status -eq 'SUCCESS') {
        $repair.phase = 'COMPLETE'
        Write-Host '修复验证通过：SYSTEM 身份已完成本轮下载和完整性校验；每日04:30计划未改动。' -ForegroundColor Green
    } elseif ($verification.status -eq 'PENDING') {
        Write-Host '同步仍在后台运行，尚未确认成功。请保持开机联网，稍后发送最新同步结果。' -ForegroundColor Yellow
    } else {
        Write-Host '配置已更新，但后台同步未通过。请发送修复结果和 logs\connection-error-last.log。' -ForegroundColor Red
    }
} catch {
    $repair.status = 'FAILED'
    $repair['message'] = $_.Exception.Message
    Write-Host ('修复未完成：' + $_.Exception.Message) -ForegroundColor Red
} finally {
    if ($null -ne $lockStream) { $lockStream.Dispose() }
    if ($repair.configurationUpdated -and $repair.status -eq 'FAILED') {
        try {
            $diagnosticPath = Assert-BackupPullPath $root (Join-Path $root 'logs\connection-error-last.log')
            if (Test-Path -LiteralPath $diagnosticPath -PathType Leaf) {
                $diagnosticFile = Get-Item -LiteralPath $diagnosticPath
                if ([DateTimeOffset]$diagnosticFile.LastWriteTimeUtc -ge [DateTimeOffset]$repair.startedAt -and
                    $diagnosticFile.Length -le 1048576) {
                    $diagnosticText = [IO.File]::ReadAllText($diagnosticPath,[Text.Encoding]::UTF8)
                    if ($diagnosticText.Length -gt 32768) {
                        $diagnosticText = $diagnosticText.Substring(0,16384) + "`r`n[truncated]`r`n" + $diagnosticText.Substring($diagnosticText.Length-16384)
                    }
                    $repair['connectionDiagnostics'] = $diagnosticText
                }
            }
        } catch { $repair['diagnosticCollectionNote'] = $_.Exception.Message }
    }
    $repair['completedAt'] = [DateTime]::UtcNow.ToString('o')
    [IO.File]::WriteAllText($reportPath,($repair | ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($true))
    Write-Host ('请发送这份结果：' + $reportPath) -ForegroundColor Cyan
}
if ($repair.status -eq 'SUCCESS') { exit 0 }
if ($repair.status -eq 'PENDING') { exit 2 }
exit 1
