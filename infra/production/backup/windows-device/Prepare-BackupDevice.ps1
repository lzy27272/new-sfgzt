#requires -Version 5.1
[CmdletBinding()]
param([string]$BackupRoot = 'E:\中台备份')
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'BackupDevice.Common.ps1')

try {
    Write-Host ''
    Write-Host '四方馆中台：备用设备连接登记（第 1 步）' -ForegroundColor Cyan
    Write-Host '请仅在准备长期保存备份的那台设备运行。'
    Write-Host '本步骤不连接云端、不开始备份、不修改电源/防火墙/计划任务，也不格式化磁盘。'
    $root = Resolve-BackupDirectory $BackupRoot
    $deviceDrive = [IO.Path]::GetPathRoot($root).Substring(0, 2)
    $disk = Get-CimInstance Win32_LogicalDisk -Filter ("DeviceID='" + $deviceDrive + "'") -ErrorAction Stop
    if (-not $disk -or $disk.FileSystem -ne 'NTFS' -or $disk.DriveType -notin @(2, 3)) {
        throw '目标磁盘不存在、不是本机磁盘或不是 NTFS。不要格式化磁盘，请先联系配置人员核对。'
    }
    $os = Get-CimInstance Win32_OperatingSystem -ErrorAction Stop
    if ([int]$os.BuildNumber -lt 22000 -or [int]$os.ProductType -ne 1) { throw '此登记包面向 Windows 11 电脑，请联系配置人员提供其他系统版本。' }
    $keyTool = Join-Path $env:WINDIR 'System32\OpenSSH\ssh-keygen.exe'
    if (-not (Test-Path -LiteralPath $keyTool -PathType Leaf)) {
        throw '缺少 OpenSSH 客户端。请在 Windows 设置中搜索“可选功能”，添加“OpenSSH 客户端”后重试；不需要安装服务器组件。'
    }
    Write-Host ('系统：' + $os.Caption + '，版本 ' + $os.BuildNumber)
    Write-Host ('保存目录：' + $root)
    Write-Host ('当前可用空间：{0:N1} GB' -f ($disk.FreeSpace / 1GB))
    Write-Host '将创建专用目录和设备密钥；已有其他文件的目录不会被覆盖。'
    Write-Host '密钥不设置交互口令，供后续无人值守使用；其目录仅允许当前账号、系统及管理员访问。'
    $answer = Read-Host '确认这是备用设备，并同意初始化上述专用目录？请输入 YES，其他输入取消'
    if ($answer -cne 'YES') { Write-Host '已取消，未创建任何文件。'; exit 0 }

    $marker = Initialize-BackupDirectory $root
    $identity = Initialize-BackupIdentity (Join-Path $root 'identity') $marker.deviceId $keyTool
    $request = New-BackupEnrollment $marker $identity $disk $os
    $requestPath = Join-Path $root '连接登记.json'
    if (Test-Path -LiteralPath $requestPath) {
        $existing = Read-BackupJson $requestPath
        if ($existing.kind -ne $request.kind -or $existing.deviceId -ne $request.deviceId -or
            $existing.publicKey -cne $request.publicKey -or $existing.publicKeyFingerprint -cne $request.publicKeyFingerprint) {
            throw '同名登记文件与当前密钥不一致，未覆盖原文件；请联系配置人员。'
        }
        Write-Host '已识别原登记，复用了原密钥；没有重新生成身份。'
    } else { Write-NewBackupJson $requestPath $request }
    Write-Host ''
    Write-Host '连接登记文件已生成，但设备尚未连接云端，自动备份尚未启用。' -ForegroundColor Green
    Write-Host ('请仅发送这个文件：' + $requestPath) -ForegroundColor Cyan
    Write-Host ('设备公钥指纹：' + $identity.fingerprint)
    Write-Host '登记文件不包含私钥、密码、数据库内容或个人文件。'
    Write-Host '请勿发送 identity 文件夹、device_ed25519 文件，或将整个备份目录打包上传。' -ForegroundColor Yellow
    Write-Host '收到登记后，还需绑定服务器、安装自动任务、验证下载校验及恢复，才算备份接通。'
    exit 0
} catch {
    Write-Host ''
    Write-Host ('登记未完成：' + $_.Exception.Message) -ForegroundColor Red
    Write-Host '不会自动清空、格式化磁盘或替换旧密钥。请将此错误文字发给配置人员。'
    exit 1
}
