#requires -Version 5.1
[CmdletBinding()]
param([string]$BackupRoot = 'E:\中台备份')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'BackupPull.Common.ps1')

$root = [IO.Path]::GetFullPath($BackupRoot).TrimEnd('\')
$logPath = Join-Path $root 'logs\同步记录.log'
$resultPath = Join-Path $root '同步结果.json'
$lockStream = $null
function Write-SyncLog([string]$Message) {
    $line = ([DateTimeOffset]::Now.ToString('o') + ' ' + $Message + [Environment]::NewLine)
    [IO.File]::AppendAllText($logPath, $line, [Text.UTF8Encoding]::new($false))
}
function Publish-Failure([string]$Code, [string]$Message) {
    try {
        Write-BackupPullJsonAtomic $resultPath ([pscustomobject][ordered]@{
            kind = 'SFG_BACKUP_SYNC_RESULT_V1'; schemaVersion = 1; status = 'FAILED'
            completedAt = [DateTime]::UtcNow.ToString('o'); errorCode = $Code; message = $Message
        })
        Write-SyncLog ('FAILED ' + $Code + ' ' + $Message)
    } catch { }
}

try {
    Assert-BackupPullPath $root $root | Out-Null
    foreach ($directory in @('control','data','incoming','logs','quarantine')) {
        New-BackupPullDirectory $root (Join-Path $root $directory) | Out-Null
    }
    $lockPath = Join-Path $root 'control\sync.lock'
    try {
        $lockStream = [IO.File]::Open($lockPath, [IO.FileMode]::OpenOrCreate, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
    } catch [IO.IOException] {
        Write-SyncLog 'SKIP another synchronization is already running'
        exit 0
    }
    $marker = Read-BackupPullJson (Join-Path $root '.sfg-backup-device.json') 16384
    $connectionPath = Join-Path $PSScriptRoot 'backup-connection.json'
    $connection = Read-BackupPullJson $connectionPath 32768
    if ($marker.kind -cne 'SFG_BACKUP_DEVICE_V1' -or $connection.kind -cne 'SFG_BACKUP_CONNECTION_V1' -or
        [int]$connection.schemaVersion -ne 1 -or $marker.deviceId -cne $connection.deviceId -or
        $marker.backupRoot -cne $root -or $connection.backupRoot -cne $root -or
        [string]$connection.scope -cne 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY') {
        throw '设备标记与服务器连接配置不一致。'
    }
    if ([string]$connection.host -notmatch '^(?:[0-9]{1,3}[.]){3}[0-9]{1,3}$' -or
        [int]$connection.port -ne 22 -or [string]$connection.username -cne 'sfg-backup-pull') {
        throw '服务器连接目标不符合限定范围。'
    }
    $keyPath = Join-Path $root 'identity\device_ed25519'
    $publicKeyPath = $keyPath + '.pub'
    $keyTool = Get-OpenSshTool 'ssh-keygen.exe'
    if ((Get-BackupKeyFingerprint $keyTool $publicKeyPath) -cne [string]$connection.deviceKeyFingerprint) {
        throw '本机设备密钥与已登记连接不一致。'
    }
    $knownHosts = Join-Path $PSScriptRoot 'known_hosts'
    $hostFingerprint = Get-BackupKeyFingerprint $keyTool $knownHosts
    if ($hostFingerprint -cne [string]$connection.serverHostKeyFingerprint) {
        throw '固定的服务器身份指纹校验失败。'
    }
    Clear-BackupPullIncoming $root

    $manifestIncomingName = 'control/manifest-' + [guid]::NewGuid().ToString('N') + '.incoming.json'
    $manifestIncoming = Join-Path $root ($manifestIncomingName.Replace('/', '\'))
    Invoke-BackupSftpGet $connection $root $keyPath $knownHosts @(
        [pscustomobject]@{ Remote = '/manifest-v1.json'; Local = $manifestIncomingName }
    )
    $manifest = Get-ValidatedBackupManifest $manifestIncoming
    $entries = @($manifest.entries)
    $downloads = New-Object Collections.Generic.List[object]
    $verified = @{}
    $requiredBytes = [long]0
    $downloadOrdinal = 0
    foreach ($entry in $entries) {
        $relative = [string]$entry.path
        $destination = Join-Path (Join-Path $root 'data') ($relative.Replace('/', '\'))
        Assert-BackupPullPath $root $destination | Out-Null
        $isValid = $false
        if (Test-Path -LiteralPath $destination -PathType Leaf) {
            $item = Get-Item -LiteralPath $destination -Force
            if (-not ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -and
                $item.Length -eq [long]$entry.bytes -and
                (Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() -ceq [string]$entry.sha256) {
                $isValid = $true
            }
        }
        if ($isValid) { $verified[$relative] = $true; continue }
        $downloadOrdinal++
        $incomingName = New-BackupIncomingName $downloadOrdinal ([string]$entry.sha256)
        $incomingPath = Join-Path $root ($incomingName.Replace('/', '\'))
        if (Test-Path -LiteralPath $incomingPath) { Remove-Item -LiteralPath $incomingPath -Force }
        $downloads.Add([pscustomobject]@{
            Entry = $entry; Remote = '/data/' + $relative; Local = $incomingName
            IncomingPath = $incomingPath; Destination = $destination
        })
        $requiredBytes += [long]$entry.bytes
    }
    $drive = [IO.DriveInfo]::new([IO.Path]::GetPathRoot($root))
    $reserve = [Math]::Max([long](10GB), [long]($drive.TotalSize * 0.05))
    if ($drive.AvailableFreeSpace -lt ($requiredBytes + $reserve)) {
        throw '备用磁盘剩余空间不足，未开始下载。'
    }
    if ($downloads.Count -gt 0) {
        $transfers = @($downloads | ForEach-Object { [pscustomobject]@{ Remote = $_.Remote; Local = $_.Local } })
        Invoke-BackupSftpGet $connection $root $keyPath $knownHosts $transfers
    }
    $quarantineRun = $null
    foreach ($download in $downloads) {
        $item = Get-Item -LiteralPath $download.IncomingPath -Force -ErrorAction Stop
        if ($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $item.Length -ne [long]$download.Entry.bytes -or
            (Get-FileHash -LiteralPath $download.IncomingPath -Algorithm SHA256).Hash.ToLowerInvariant() -cne [string]$download.Entry.sha256) {
            throw ('下载文件完整性校验失败：' + [string]$download.Entry.path)
        }
        $destinationParent = New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($download.Destination))
        if (Test-Path -LiteralPath $download.Destination) {
            if ($null -eq $quarantineRun) {
                $quarantineRun = New-BackupPullDirectory $root (Join-Path $root ('quarantine\' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N')))
            }
            $badDestination = Join-Path $quarantineRun (([string]$download.Entry.path + '.replaced').Replace('/', '\'))
            New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($badDestination)) | Out-Null
            [IO.File]::Move($download.Destination, $badDestination)
        }
        [IO.File]::Move($download.IncomingPath, $download.Destination)
        $verified[[string]$download.Entry.path] = $true
    }

    $desired = @{}
    foreach ($entry in $entries) { $desired[[string]$entry.path] = $true }
    $dataRoot = Join-Path $root 'data'
    $linkedDataEntry = Get-ChildItem -LiteralPath $dataRoot -Recurse -Force |
        Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
        Select-Object -First 1
    if ($null -ne $linkedDataEntry) { throw '数据目录出现链接或目录联接，拒绝同步与清理。' }
    foreach ($file in Get-ChildItem -LiteralPath $dataRoot -File -Recurse -Force) {
        if ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) { throw '数据目录出现链接文件，拒绝清理。' }
        $relative = $file.FullName.Substring($dataRoot.Length + 1).Replace('\', '/')
        if (-not (Test-BackupManagedRelativePath $relative)) { throw ('数据目录出现非受管文件：' + $relative) }
        if (-not $desired.ContainsKey($relative)) {
            if ($null -eq $quarantineRun) {
                $quarantineRun = New-BackupPullDirectory $root (Join-Path $root ('quarantine\' + [DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ') + '-' + [guid]::NewGuid().ToString('N')))
            }
            $staleDestination = Join-Path $quarantineRun ($relative.Replace('/', '\'))
            New-BackupPullDirectory $root ([IO.Path]::GetDirectoryName($staleDestination)) | Out-Null
            [IO.File]::Move($file.FullName, $staleDestination)
        }
    }
    foreach ($directory in Get-ChildItem -LiteralPath (Join-Path $root 'quarantine') -Directory -Force) {
        if ($directory.Name -notmatch '^[0-9]{8}T[0-9]{6}Z-[0-9a-f]{32}$' -or
            ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint)) { continue }
        if ($directory.CreationTimeUtc -lt [DateTime]::UtcNow.AddDays(-14)) {
            $nestedLink = Get-ChildItem -LiteralPath $directory.FullName -Recurse -Force |
                Where-Object { $_.Attributes -band [IO.FileAttributes]::ReparsePoint } |
                Select-Object -First 1
            if ($null -ne $nestedLink) { continue }
            $safe = Assert-BackupPullPath (Join-Path $root 'quarantine') $directory.FullName
            [IO.Directory]::Delete($safe, $true)
        }
    }

    $receiptName = 'control/receipt-' + [guid]::NewGuid().ToString('N') + '.incoming.json'
    $receiptIncoming = Join-Path $root ($receiptName.Replace('/', '\'))
    Invoke-BackupSftpGet $connection $root $keyPath $knownHosts @(
        [pscustomobject]@{ Remote = '/' + [string]$manifest.receiptPath; Local = $receiptName }
    )
    $receipt = Read-BackupPullJson $receiptIncoming 16384
    if ($receipt.kind -cne 'SFG_BACKUP_RECEIPT_TOKEN_V1' -or $receipt.manifestId -cne $manifest.manifestId) {
        throw '服务器回执令牌与已验证清单不一致。'
    }
    $acceptedManifest = Join-Path $root 'control\accepted-manifest.json'
    [IO.File]::Copy($manifestIncoming, $acceptedManifest + '.new', $true)
    if (Test-Path -LiteralPath $acceptedManifest) { [IO.File]::Replace($acceptedManifest + '.new', $acceptedManifest, $null) }
    else { [IO.File]::Move($acceptedManifest + '.new', $acceptedManifest) }
    [IO.File]::Copy($receiptIncoming, (Join-Path $root 'control\last-receipt.json'), $true)
    Remove-Item -LiteralPath $manifestIncoming, $receiptIncoming -Force

    $categoryCounts = [ordered]@{}
    $categoryBytes = [ordered]@{}
    foreach ($entry in $entries) {
        $category = [string]$entry.category
        if (-not $categoryCounts.Contains($category)) { $categoryCounts[$category] = 0; $categoryBytes[$category] = [long]0 }
        $categoryCounts[$category] = [int]$categoryCounts[$category] + 1
        $categoryBytes[$category] = [long]$categoryBytes[$category] + [long]$entry.bytes
    }
    $result = [pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_SYNC_RESULT_V1'; schemaVersion = 1; status = 'SUCCESS'
        deviceId = [string]$marker.deviceId; manifestId = [string]$manifest.manifestId
        manifestGeneratedAt = [string]$manifest.generatedAt; completedAt = [DateTime]::UtcNow.ToString('o')
        receiptVerified = $true; totalEntries = $entries.Count; downloadedEntries = $downloads.Count
        categoryCounts = $categoryCounts; categoryBytes = $categoryBytes
    }
    Write-BackupPullJsonAtomic $resultPath $result
    Write-SyncLog ('SUCCESS manifest=' + [string]$manifest.manifestId + ' entries=' + $entries.Count + ' downloaded=' + $downloads.Count)
    Write-Host '异地加密备份同步与逐文件校验成功。' -ForegroundColor Green
    Write-Host ('结果文件：' + $resultPath) -ForegroundColor Cyan
    exit 0
} catch {
    $message = $_.Exception.Message
    if ($message.Length -gt 500) { $message = $message.Substring(0, 500) }
    Publish-Failure 'BACKUP_SYNC_FAILED' $message
    Write-Host ('同步失败：' + $message) -ForegroundColor Red
    Write-Host ('请发送结果文件：' + $resultPath) -ForegroundColor Yellow
    exit 1
} finally {
    if ($null -ne $lockStream) { $lockStream.Dispose() }
}
