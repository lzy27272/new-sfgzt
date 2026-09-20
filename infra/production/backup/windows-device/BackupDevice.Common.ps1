#requires -Version 5.1
Set-StrictMode -Version Latest

function Resolve-BackupDirectory {
    param([Parameter(Mandatory=$true)][string]$Path)
    if ($Path -notmatch '^[A-Za-z]:\\[^\\]' -or $Path -match '[\x00-\x1f"<>|?*]' -or $Path.Substring(2).Contains(':')) {
        throw '请使用本机磁盘的专用子目录，例如 E:\中台备份；不支持磁盘根目录、网络路径或特殊路径。'
    }
    if (@($Path.Substring(3).Split('\') | Where-Object { $_ -in @('.', '..') -or $_ -match '[. ]$' }).Count) {
        throw '备份路径不能包含上级目录、相对目录或以空格、点结尾的名称。'
    }
    $full = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    $cursor = $full
    while ($cursor) {
        if (Test-Path -LiteralPath $cursor) {
            $entry = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
            if (-not $entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint)) {
                throw '备份目录及其父目录必须是真实目录，不能使用链接或目录联接。'
            }
        }
        $parent = [IO.Directory]::GetParent($cursor)
        if (-not $parent) { break }
        $cursor = $parent.FullName
    }
    return $full
}

function Write-NewBackupJson {
    param([string]$Path, [object]$Value)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 5))
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush() }
    finally { $stream.Dispose() }
}

function Read-BackupJson {
    param([string]$Path)
    $entry = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $entry.Length -gt 16384) {
        throw '登记文件不安全或大小异常，请停止操作并联系配置人员。'
    }
    return ([IO.File]::ReadAllText($entry.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json -ErrorAction Stop)
}

function Initialize-BackupDirectory {
    param([string]$Path)
    $root = Resolve-BackupDirectory $Path
    $markerPath = Join-Path $root '.sfg-backup-device.json'
    $ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    if (Test-Path -LiteralPath $markerPath) {
        $marker = Read-BackupJson $markerPath
        $parsedId = [guid]::Empty
        if ($marker.kind -ne 'SFG_BACKUP_DEVICE_V1' -or $marker.schemaVersion -ne 1 -or
            $marker.backupRoot -cne $root -or $marker.ownerSid -ne $ownerSid -or
            -not [guid]::TryParse($marker.deviceId, [ref]$parsedId) -or $parsedId -eq [guid]::Empty) {
            throw '该目录已由其他设备、账号或版本登记，不能覆盖；请联系配置人员。'
        }
        return $marker
    }
    if (Test-Path -LiteralPath $root) {
        if (@(Get-ChildItem -LiteralPath $root -Force -ErrorAction Stop).Count) {
            throw '目标目录已有其他文件，初始化已停止，不会覆盖。请选择新的空目录。'
        }
    } else {
        New-Item -Path $root -ItemType Directory -ErrorAction Stop | Out-Null
    }
    $marker = [pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_DEVICE_V1'; schemaVersion = 1
        deviceId = [guid]::NewGuid().ToString(); backupRoot = $root
        ownerSid = $ownerSid; createdAt = [DateTime]::UtcNow.ToString('o')
    }
    Write-NewBackupJson $markerPath $marker
    return $marker
}

function Protect-BackupIdentityDirectory {
    param([string]$Path)
    $full = Resolve-BackupDirectory $Path
    if (-not (Test-Path -LiteralPath $full)) { New-Item -Path $full -ItemType Directory -ErrorAction Stop | Out-Null }
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    # Preserve owner/group/audit metadata and persist only the modified DACL.
    # PowerShell 5.1 Set-Acl can request audit privileges on a non-elevated process.
    $acl = Get-Acl -LiteralPath $full -ErrorAction Stop
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($existingRule in @($acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier]))) {
        $acl.RemoveAccessRuleSpecific($existingRule)
    }
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit, ObjectInherit'
    foreach ($sid in @($owner.Value, 'S-1-5-18', 'S-1-5-32-544') | Select-Object -Unique) {
        $rule = [Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl,
            $inherit, [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
        $acl.AddAccessRule($rule)
    }
    [IO.Directory]::SetAccessControl($full, $acl)
}

function Invoke-BackupKeyTool {
    param([string]$Executable, [string]$Arguments)
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable; $start.Arguments = $Arguments
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw '密钥工具无法启动。' }
        $process.StandardInput.Close()
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw '密钥工具超时，未继续登记。' }
        $result = $stdout.GetAwaiter().GetResult()
        $errorText = $stderr.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw ('密钥工具执行失败：' + $errorText.Trim()) }
        return $result.Trim()
    } finally { $process.Dispose() }
}

function Get-BackupPublicIdentity {
    param([string]$KeyTool, [string]$PrivateKeyPath)
    foreach ($path in @($PrivateKeyPath, ($PrivateKeyPath + '.pub'))) {
        $entry = Get-Item -LiteralPath $path -Force -ErrorAction Stop
        if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or $entry.Length -gt 16384) {
            throw '密钥文件异常，拒绝使用；请勿删除或覆盖原密钥。'
        }
    }
    $public = [IO.File]::ReadAllText($PrivateKeyPath + '.pub').Trim()
    if ($public -notmatch '^ssh-ed25519 ([A-Za-z0-9+/]{68})(?: [A-Za-z0-9-]+)?$') {
        throw '不是本程序支持的设备公钥。'
    }
    $canonical = 'ssh-ed25519 ' + $Matches[1]
    $derived = Invoke-BackupKeyTool $KeyTool ('-y -P "" -f "' + $PrivateKeyPath + '"')
    if (($derived.Split(' ')[0..1] -join ' ') -cne $canonical) { throw '公钥与私钥不匹配，登记已停止。' }
    $fingerprintText = Invoke-BackupKeyTool $KeyTool ('-l -E sha256 -f "' + $PrivateKeyPath + '.pub"')
    if ($fingerprintText -notmatch '(?:^|\s)(SHA256:[A-Za-z0-9+/]{43})(?:\s|$)') { throw '无法核验设备公钥指纹。' }
    return [pscustomobject]@{ publicKey = $public; fingerprint = $Matches[1] }
}

function Initialize-BackupIdentity {
    param([string]$Directory, [string]$DeviceId, [string]$KeyTool)
    if ($DeviceId -notmatch '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$') { throw '设备编号异常。' }
    Protect-BackupIdentityDirectory $Directory
    $privatePath = Join-Path $Directory 'device_ed25519'
    $privateExists = Test-Path -LiteralPath $privatePath
    $publicExists = Test-Path -LiteralPath ($privatePath + '.pub')
    if ($privateExists -ne $publicExists) { throw '设备密钥不完整；为避免替换已登记身份，请联系配置人员处理。' }
    if (-not $privateExists) {
        if (@(Get-ChildItem -LiteralPath $Directory -Force).Count) { throw '密钥目录已有其他文件，不会覆盖。' }
        Invoke-BackupKeyTool $KeyTool ('-q -t ed25519 -N "" -C "sfg-backup-' + $DeviceId + '" -f "' + $privatePath + '"') | Out-Null
    }
    return Get-BackupPublicIdentity $KeyTool $privatePath
}

function New-BackupEnrollment {
    param([object]$Marker, [object]$PublicIdentity, [object]$Disk, [object]$OperatingSystem)
    return [pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_ENROLLMENT_V1'; schemaVersion = 1
        status = 'PENDING_SERVER_BINDING'; deviceId = $Marker.deviceId
        createdAt = $Marker.createdAt; backupRoot = $Marker.backupRoot
        publicKey = $PublicIdentity.publicKey; publicKeyFingerprint = $PublicIdentity.fingerprint
        operatingSystem = [string]$OperatingSystem.Caption; osBuild = [string]$OperatingSystem.BuildNumber
        fileSystem = [string]$Disk.FileSystem; capacityBytes = [long]$Disk.Size; availableBytes = [long]$Disk.FreeSpace
        requestedScope = 'ENCRYPTED_BACKUP_DOWNLOAD_ONLY'
    }
}
