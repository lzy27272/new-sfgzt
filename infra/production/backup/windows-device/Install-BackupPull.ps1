#requires -Version 5.1
[CmdletBinding()]
param([string]$ConnectionFile = (Join-Path $PSScriptRoot 'backup-connection.json'))
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'BackupDevice.Common.ps1')
. (Join-Path $PSScriptRoot 'BackupPull.Common.ps1')
. (Join-Path $PSScriptRoot 'BackupSystem.Common.ps1')

function Test-IsAdministrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    return $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
}

try {
    if (-not (Test-IsAdministrator)) {
        $powerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $argument = '-NoProfile -ExecutionPolicy Bypass -File "' + $PSCommandPath.Replace('"','\"') + '" -ConnectionFile "' + $ConnectionFile.Replace('"','\"') + '"'
        $elevated = Start-Process -FilePath $powerShell -ArgumentList $argument -Verb RunAs -Wait -PassThru
        exit $elevated.ExitCode
    }
    Write-Host ''
    Write-Host '四方馆中台：异地加密备份连接（第 2 步）' -ForegroundColor Cyan
    Write-Host '本步骤会固定服务器身份、首次下载校验，并建立每日自动任务。'
    Write-Host '不会开放本机入站端口，不会把数据库解密密钥放到本机。'
    $packageManifest = Read-BackupPullJson (Join-Path $PSScriptRoot 'package-manifest.json') 65536
    $expectedPackageFiles = @(
        '02-Install-BackupPull.cmd', 'Install-BackupPull.ps1', 'Invoke-BackupPull.ps1',
        'BackupPull.Common.ps1', 'BackupSystem.Common.ps1', 'BackupDevice.Common.ps1', 'README.txt', 'backup-connection.json'
    )
    if ($packageManifest.kind -cne 'SFG_BACKUP_CLIENT_PACKAGE_V1' -or
        [int]$packageManifest.schemaVersion -ne 1 -or @($packageManifest.files).Count -ne $expectedPackageFiles.Count) {
        throw '安装包校验清单版本或数量无效。'
    }
    $seenPackageFiles = @{}
    foreach ($record in @($packageManifest.files)) {
        $name = [string]$record.name
        if ($name -notin $expectedPackageFiles -or $seenPackageFiles.ContainsKey($name) -or
            -not (Test-LowerSha256 ([string]$record.sha256)) -or [long]$record.bytes -le 0) {
            throw '安装包校验清单包含异常条目。'
        }
        $source = Join-Path $PSScriptRoot $name
        $item = Get-Item -LiteralPath $source -Force -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $item.Length -ne [long]$record.bytes -or
            (Get-FileHash -LiteralPath $source -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$record.sha256) {
            throw ('安装包文件损坏或被替换：' + $name)
        }
        $seenPackageFiles[$name] = $true
    }
    $connection = Read-BackupPullJson $ConnectionFile 32768
    if ($connection.kind -cne 'SFG_BACKUP_CONNECTION_V1' -or [int]$connection.schemaVersion -ne 1 -or
        [string]$connection.scope -cne 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY') {
        throw '连接配置版本或权限范围无效。'
    }
    if ([string]$packageManifest.deviceId -cne [string]$connection.deviceId) {
        throw '安装包清单与连接配置不属于同一台设备。'
    }
    $root = Resolve-BackupDirectory ([string]$connection.backupRoot)
    $marker = Read-BackupJson (Join-Path $root '.sfg-backup-device.json')
    if ($marker.kind -cne 'SFG_BACKUP_DEVICE_V1' -or $marker.deviceId -cne $connection.deviceId -or
        $marker.backupRoot -cne $root) { throw '这不是连接配置所登记的备用设备。' }
    $keyPath = Join-Path $root 'identity\device_ed25519'
    $keyTool = Get-OpenSshTool 'ssh-keygen.exe'
    if ((Get-BackupKeyFingerprint $keyTool ($keyPath + '.pub')) -cne [string]$connection.deviceKeyFingerprint) {
        throw '设备连接密钥指纹与登记不一致。'
    }
    if ([string]$connection.serverHostKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/]{68}$' -or
        [string]$connection.host -notmatch '^(?:[0-9]{1,3}[.]){3}[0-9]{1,3}$' -or
        [int]$connection.port -ne 22 -or [string]$connection.username -cne 'sfg-backup-pull') {
        throw '服务器身份配置无效。'
    }
    $program = New-BackupPullDirectory $root (Join-Path $root 'program')
    foreach ($name in @('BackupPull.Common.ps1','Invoke-BackupPull.ps1')) {
        $source = Join-Path $PSScriptRoot $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw ('安装包缺少文件：' + $name) }
        [IO.File]::Copy($source, (Join-Path $program $name), $true)
    }
    [IO.File]::Copy($ConnectionFile, (Join-Path $program 'backup-connection.json'), $true)
    $knownHostsPath = Join-Path $program 'known_hosts'
    $knownHostsLine = [string]$connection.host + ' ' + [string]$connection.serverHostKey + [Environment]::NewLine
    [IO.File]::WriteAllText($knownHostsPath, $knownHostsLine, [Text.Encoding]::ASCII)
    if ((Get-BackupKeyFingerprint $keyTool $knownHostsPath) -cne [string]$connection.serverHostKeyFingerprint) {
        throw '安装包内的服务器公钥和指纹不匹配。'
    }
    Protect-BackupIdentityDirectory $program
    Set-BackupSystemKeyPermission $root
    foreach ($directory in @('control','data','incoming','logs','quarantine')) {
        New-BackupPullDirectory $root (Join-Path $root $directory) | Out-Null
    }
    Write-Host '正在以 SYSTEM 后台身份执行首次只读下载和完整性校验；首轮数据较多，请保持联网。' -ForegroundColor Yellow
    $powerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $verification = Invoke-BackupSystemVerification $root
    if ($verification.status -ne 'SUCCESS') {
        throw ('首次 SYSTEM 同步尚未验证通过，暂不创建每日自动任务。状态：' + $verification.status + '。请检查同步结果后重新运行安装。')
    }

    $taskName = 'Sifangguan Encrypted Backup Pull'
    $taskArguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "' + (Join-Path $program 'Invoke-BackupPull.ps1') + '" -BackupRoot "' + $root + '"'
    $action = New-ScheduledTaskAction -Execute $powerShell -Argument $taskArguments
    $daily = New-ScheduledTaskTrigger -Daily -At '04:30'
    $startup = New-ScheduledTaskTrigger -AtStartup
    $settings = New-ScheduledTaskSettingsSet -StartWhenAvailable -MultipleInstances IgnoreNew `
        -RestartCount 6 -RestartInterval (New-TimeSpan -Minutes 30) `
        -ExecutionTimeLimit (New-TimeSpan -Hours 6) -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger @($daily, $startup) `
        -Settings $settings -Principal $principal -Description '四方馆中台加密备份只读拉取与SHA-256校验' -Force | Out-Null
    $registered = Get-ScheduledTask -TaskName $taskName -ErrorAction Stop
    if ($registered.State -eq 'Disabled') { throw '自动备份任务被系统策略禁用。' }
    Write-Host ''
    Write-Host '配置成功：首次备份已下载并校验，自动任务已建立。' -ForegroundColor Green
    Write-Host '电脑锁屏不影响任务；断网或关机错过后，恢复在线会自动补跑。'
    Write-Host ('请把这个文件发回当前对话：' + (Join-Path $root '同步结果.json')) -ForegroundColor Cyan
    exit 0
} catch {
    Write-Host ''
    Write-Host ('配置未完成：' + $_.Exception.Message) -ForegroundColor Red
    Write-Host '不会开放防火墙或删除已有备份。请把窗口错误文字发给配置人员。'
    exit 1
}
