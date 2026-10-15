#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$EnrollmentFile,
    [Parameter(Mandatory=$true)][string]$ServerHostKeyFile,
    [Parameter(Mandatory=$true)][string]$OutputDirectory,
    [string]$ServerHost = '43.136.184.38',
    [int]$ServerPort = 22
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'BackupDevice.Common.ps1')
. (Join-Path $PSScriptRoot 'BackupPull.Common.ps1')

$outputRoot = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
if (-not [IO.Directory]::Exists($outputRoot)) { [IO.Directory]::CreateDirectory($outputRoot) | Out-Null }
$staging = Join-Path $outputRoot ('.backup-pull-package-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($staging) | Out-Null
try {
    $enrollment = Read-BackupPullJson $EnrollmentFile 32768
    if ($enrollment.kind -cne 'SFG_BACKUP_ENROLLMENT_V1' -or [int]$enrollment.schemaVersion -ne 1 -or
        $enrollment.status -cne 'PENDING_SERVER_BINDING' -or
        [string]$enrollment.requestedScope -cne 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY') {
        throw '登记文件版本、状态或权限范围无效。'
    }
    $parsedDevice = [guid]::Empty
    if (-not [guid]::TryParse([string]$enrollment.deviceId, [ref]$parsedDevice) -or $parsedDevice -eq [guid]::Empty -or
        [string]$enrollment.backupRoot -cne 'E:\中台备份' -or
        [string]$enrollment.fileSystem -cne 'NTFS' -or [long]$enrollment.availableBytes -lt 500GB) {
        throw '登记设备编号、目录、磁盘格式或剩余空间无效。'
    }
    if ([string]$enrollment.publicKey -notmatch '^ssh-ed25519 [A-Za-z0-9+/]{68}(?: [A-Za-z0-9._@+-]+)?$' -or
        [string]$enrollment.publicKeyFingerprint -notmatch '^SHA256:[A-Za-z0-9+/]{43}$') {
        throw '登记公钥格式无效。'
    }
    $keyTool = Get-OpenSshTool 'ssh-keygen.exe'
    $enrollmentKey = Join-Path $staging 'enrollment-key.pub'
    [IO.File]::WriteAllText($enrollmentKey, ([string]$enrollment.publicKey + [Environment]::NewLine), [Text.Encoding]::ASCII)
    if ((Get-BackupKeyFingerprint $keyTool $enrollmentKey) -cne [string]$enrollment.publicKeyFingerprint) {
        throw '登记公钥与指纹不匹配。'
    }
    [IO.File]::Delete($enrollmentKey)

    $serverKeyLine = [IO.File]::ReadAllText($ServerHostKeyFile, [Text.Encoding]::ASCII).Trim()
    if ($serverKeyLine -notmatch '^ssh-ed25519 ([A-Za-z0-9+/]{68})(?: [A-Za-z0-9._@+-]+)?$') {
        throw '服务器主机公钥格式无效。'
    }
    $canonicalServerKey = 'ssh-ed25519 ' + $Matches[1]
    $serverKeyProbe = Join-Path $staging 'server-key.pub'
    [IO.File]::WriteAllText($serverKeyProbe, ($canonicalServerKey + [Environment]::NewLine), [Text.Encoding]::ASCII)
    $serverFingerprint = Get-BackupKeyFingerprint $keyTool $serverKeyProbe
    [IO.File]::Delete($serverKeyProbe)
    if ($ServerHost -notmatch '^(?:[0-9]{1,3}[.]){3}[0-9]{1,3}$' -or $ServerPort -ne 22) {
        throw '服务器地址或端口不符合当前固定配置。'
    }

    foreach ($name in @(
        '02-Install-BackupPull.cmd', 'Install-BackupPull.ps1', 'Invoke-BackupPull.ps1',
        'BackupPull.Common.ps1', 'BackupSystem.Common.ps1', 'BackupDevice.Common.ps1', 'README.txt')) {
        $source = Join-Path $PSScriptRoot $name
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) { throw ('源包缺少文件：' + $name) }
        [IO.File]::Copy($source, (Join-Path $staging $name), $false)
        if ($name.EndsWith('.ps1')) {
            $scriptText = [IO.File]::ReadAllText($source,[Text.Encoding]::UTF8)
            [IO.File]::WriteAllText((Join-Path $staging $name),$scriptText,[Text.UTF8Encoding]::new($true))
        }
    }
    # cmd.exe decodes batch files with the active legacy code page before a
    # script-level chcp command can safely affect every parsed block. Keep the
    # launcher ASCII-only and normalize it to Windows line endings so it works
    # independently of the device language and the packaging checkout format.
    $launcherPath = Join-Path $staging '02-Install-BackupPull.cmd'
    $launcherBytes = [IO.File]::ReadAllBytes($launcherPath)
    if (@($launcherBytes | Where-Object { $_ -gt 127 }).Count) {
        throw '连接包启动文件必须只包含 ASCII 字符。'
    }
    $launcherText = [Text.Encoding]::ASCII.GetString($launcherBytes) -replace "`r?`n", "`r`n"
    [IO.File]::WriteAllText($launcherPath, $launcherText, [Text.Encoding]::ASCII)
    $connection = [pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_CONNECTION_V1'; schemaVersion = 1
        issuedAt = [DateTime]::UtcNow.ToString('o')
        deviceId = [string]$enrollment.deviceId; backupRoot = [string]$enrollment.backupRoot
        deviceKeyFingerprint = [string]$enrollment.publicKeyFingerprint
        scope = 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY'
        host = $ServerHost; port = $ServerPort; username = 'sfg-backup-pull'
        serverHostKey = $canonicalServerKey; serverHostKeyFingerprint = $serverFingerprint
    }
    Write-BackupPullJsonAtomic (Join-Path $staging 'backup-connection.json') $connection
    $packageFiles = New-Object Collections.Generic.List[object]
    foreach ($file in Get-ChildItem -LiteralPath $staging -File | Sort-Object Name) {
        $packageFiles.Add([pscustomobject][ordered]@{
            name = $file.Name; bytes = $file.Length
            sha256 = (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        })
    }
    Write-BackupPullJsonAtomic (Join-Path $staging 'package-manifest.json') ([pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_CLIENT_PACKAGE_V1'; schemaVersion = 1
        deviceId = [string]$enrollment.deviceId; createdAt = [DateTime]::UtcNow.ToString('o')
        files = $packageFiles
    })

    $stamp = [DateTime]::Now.ToString('yyyyMMdd-HHmmss')
    $zipPath = Join-Path $outputRoot ('SFG-BackupDevice-Windows11-Connect-' + $stamp + '.zip')
    if (Test-Path -LiteralPath $zipPath) { throw '同名连接包已经存在，拒绝覆盖。' }
    Compress-Archive -Path (Join-Path $staging '*') -DestinationPath $zipPath -CompressionLevel Optimal
    $zip = Get-Item -LiteralPath $zipPath
    if ($zip.Length -le 0) { throw '连接包生成失败。' }
    $zipHash = (Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash
    $hashPath = $zipPath + '.sha256.txt'
    [IO.File]::WriteAllText($hashPath, ($zipHash + '  ' + $zip.Name + [Environment]::NewLine), [Text.Encoding]::ASCII)
    Write-Host ('PACKAGE_PATH=' + $zipPath)
    Write-Host ('PACKAGE_BYTES=' + $zip.Length)
    Write-Host ('PACKAGE_SHA256=' + $zipHash)
} finally {
    $resolvedStage = [IO.Path]::GetFullPath($staging)
    if ([IO.Path]::GetDirectoryName($resolvedStage) -cne $outputRoot -or
        [IO.Path]::GetFileName($resolvedStage) -notmatch '^[.]backup-pull-package-[0-9a-f]{32}$') {
        throw 'Refusing package staging cleanup outside the output directory'
    }
    if ([IO.Directory]::Exists($resolvedStage)) { Remove-Item -LiteralPath $resolvedStage -Recurse -Force }
}
