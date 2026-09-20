#requires -Version 5.1
Set-StrictMode -Version Latest

function Read-BackupPullJson {
    param(
        [Parameter(Mandatory=$true)][string]$Path,
        [long]$MaximumBytes = 2097152
    )
    $entry = Get-Item -LiteralPath $Path -Force -ErrorAction Stop
    if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $entry.Length -le 0 -or $entry.Length -gt $MaximumBytes) {
        throw 'JSON 文件类型或大小异常。'
    }
    return ([IO.File]::ReadAllText($entry.FullName, [Text.Encoding]::UTF8) | ConvertFrom-Json -ErrorAction Stop)
}

function Write-BackupPullJsonAtomic {
    param([Parameter(Mandatory=$true)][string]$Path, [Parameter(Mandatory=$true)][object]$Value)
    $parent = [IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($Path))
    if (-not [IO.Directory]::Exists($parent)) { [IO.Directory]::CreateDirectory($parent) | Out-Null }
    $temporary = Join-Path $parent ('.' + [IO.Path]::GetFileName($Path) + '.' + [guid]::NewGuid().ToString('N') + '.tmp')
    $payload = ($Value | ConvertTo-Json -Depth 8)
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes($payload + [Environment]::NewLine)
    try {
        $stream = [IO.File]::Open($temporary, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush() }
        finally { $stream.Dispose() }
        if ([IO.File]::Exists($Path)) {
            $backup = $Path + '.previous'
            [IO.File]::Replace($temporary, $Path, $backup, $true)
            [IO.File]::Delete($backup)
        } else {
            [IO.File]::Move($temporary, $Path)
        }
    } finally {
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function Assert-BackupPullPath {
    param([Parameter(Mandatory=$true)][string]$Root, [Parameter(Mandatory=$true)][string]$Path)
    $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd('\')
    $pathFull = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if (-not $pathFull.StartsWith($rootFull + '\', [StringComparison]::OrdinalIgnoreCase) -and
        $pathFull -cne $rootFull) {
        throw '路径超出备用设备专用目录。'
    }
    $cursor = $pathFull
    while ($cursor.Length -ge $rootFull.Length) {
        if (Test-Path -LiteralPath $cursor) {
            $entry = Get-Item -LiteralPath $cursor -Force -ErrorAction Stop
            if ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) {
                throw '备用目录包含链接或目录联接，已停止同步。'
            }
        }
        if ($cursor -ceq $rootFull) { break }
        $cursor = [IO.Path]::GetDirectoryName($cursor)
    }
    return $pathFull
}

function New-BackupPullDirectory {
    param([string]$Root, [string]$Path)
    $full = Assert-BackupPullPath $Root $Path
    if (-not [IO.Directory]::Exists($full)) { [IO.Directory]::CreateDirectory($full) | Out-Null }
    Assert-BackupPullPath $Root $full | Out-Null
    return $full
}

function Test-BackupManagedRelativePath {
    param([Parameter(Mandatory=$true)][string]$Path)
    if (-not (Test-AsciiText $Path)) { return $false }
    $patterns = @(
        '^hotel-ai-os/daily/hotel_ai_os-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}(?:-[A-Za-z0-9][A-Za-z0-9_-]{0,80})?[.]dump[.]enc$',
        '^hotel-ai-os/monthly/hotel_ai_os-monthly-[0-9]{6}[.]dump[.]enc$',
        '^hotel-ai-os/yearly/hotel_ai_os-yearly-[0-9]{4}[.]dump[.]enc$',
        '^sifangguan-ota/daily/sifangguan-ota-state-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}[.]tar[.]gz[.]enc$',
        '^sifangguan-ota/monthly/sifangguan-ota-state-monthly-[0-9]{6}[.]tar[.]gz[.]enc$',
        '^sifangguan-ota/yearly/sifangguan-ota-state-yearly-[0-9]{4}[.]tar[.]gz[.]enc$',
        '^analytics-raw/daily/analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}[.]tar[.]gz[.]enc$'
    )
    return @($patterns | Where-Object { $Path -cmatch $_ }).Count -eq 1
}

function Test-AsciiText {
    param([string]$Value)
    if ($null -eq $Value) { return $false }
    foreach ($character in $Value.ToCharArray()) {
        if ([int]$character -gt 127) { return $false }
    }
    return $true
}

function Test-LowerSha256 {
    param([string]$Value)
    return $null -ne $Value -and $Value -cmatch '^[0-9a-f]{64}$'
}

function New-BackupIncomingName {
    param([int]$Ordinal, [string]$Sha256)
    if ($Ordinal -lt 1 -or $Ordinal -gt 500 -or -not (Test-LowerSha256 $Sha256)) {
        throw '下载临时文件编号或校验值无效。'
    }
    return ('incoming/{0:D4}-{1}.part' -f $Ordinal, $Sha256)
}

function Clear-BackupPullIncoming {
    param([Parameter(Mandatory=$true)][string]$Root)
    $incoming = New-BackupPullDirectory $Root (Join-Path $Root 'incoming')
    foreach ($entry in Get-ChildItem -LiteralPath $incoming -Force -ErrorAction Stop) {
        if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $entry.Name -cnotmatch '^(?:[0-9]{4}-)?[0-9a-f]{64}[.]part$') {
            throw '下载临时目录包含非受管文件，拒绝自动清理。'
        }
        [IO.File]::Delete($entry.FullName)
    }
}

function Get-ValidatedBackupManifest {
    param([Parameter(Mandatory=$true)][string]$Path)
    $manifest = Read-BackupPullJson $Path
    if ($manifest.kind -cne 'SFG_BACKUP_MANIFEST_V1' -or [int]$manifest.schemaVersion -ne 1 -or
        -not (Test-LowerSha256 ([string]$manifest.manifestId)) -or
        [string]$manifest.receiptPath -cne ('receipts/' + [string]$manifest.manifestId + '.ok')) {
        throw '服务器备份清单版本或标识无效。'
    }
    $generated = [DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$manifest.generatedAt, [ref]$generated)) {
        throw '服务器备份清单时间无效。'
    }
    $now = [DateTimeOffset]::UtcNow
    if ($generated -gt $now.AddMinutes(15) -or $generated -lt $now.AddHours(-48)) {
        throw '服务器备份清单时间异常或已超过48小时。'
    }
    $entries = @($manifest.entries)
    if ($entries.Count -lt 2 -or $entries.Count -gt 500) { throw '服务器备份清单条目数量异常。' }
    $seen = @{}
    $categories = @{}
    foreach ($entry in $entries) {
        $relative = [string]$entry.path
        if (-not (Test-BackupManagedRelativePath $relative) -or $seen.ContainsKey($relative)) {
            throw '服务器备份清单包含重复或越界路径。'
        }
        $parts = $relative.Split('/')
        if ([string]$entry.category -cne $parts[0] -or [string]$entry.tier -cne $parts[1]) {
            throw '服务器备份清单分类与路径不一致。'
        }
        $length = [long]$entry.bytes
        if ($length -le 0 -or $length -gt 1099511627776 -or -not (Test-LowerSha256 ([string]$entry.sha256))) {
            throw '服务器备份清单包含异常文件大小或校验值。'
        }
        $seen[$relative] = $true
        $categories[$parts[0]] = $true
    }
    foreach ($required in @('hotel-ai-os', 'sifangguan-ota')) {
        if (-not $categories.ContainsKey($required)) { throw ('服务器清单缺少必要分类：' + $required) }
    }
    return $manifest
}

function Get-OpenSshTool {
    param([ValidateSet('sftp.exe','ssh-keygen.exe')][string]$Name)
    $path = Join-Path $env:WINDIR ('System32\OpenSSH\' + $Name)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw ('缺少 Windows OpenSSH 客户端组件：' + $Name)
    }
    return $path
}

function Get-BackupKeyFingerprint {
    param([string]$KeyTool, [string]$PublicKeyPath)
    $output = & $KeyTool -l -E sha256 -f $PublicKeyPath 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $output -notmatch '(?:^|\s)(SHA256:[A-Za-z0-9+/]{43})(?:\s|$)') {
        throw '无法核验设备连接密钥指纹。'
    }
    return $Matches[1]
}

function Invoke-BackupSftpGet {
    param(
        [object]$Connection,
        [string]$BackupRoot,
        [string]$KeyPath,
        [string]$KnownHostsPath,
        [array]$Transfers
    )
    if ($Transfers.Count -lt 1 -or $Transfers.Count -gt 500) { throw '下载批次大小异常。' }
    $control = New-BackupPullDirectory $BackupRoot (Join-Path $BackupRoot 'control')
    $batch = Join-Path $control ('sftp-' + [guid]::NewGuid().ToString('N') + '.txt')
    try {
        $lines = New-Object Collections.Generic.List[string]
        foreach ($transfer in $Transfers) {
            $remote = [string]$transfer.Remote
            $local = [string]$transfer.Local
            if ($remote -notmatch '^/[A-Za-z0-9._/+\-]+$' -or $remote.Contains('..') -or
                $local -notmatch '^[A-Za-z0-9._/+\-]+$' -or $local.Contains('..')) {
                throw 'SFTP 下载路径无效。'
            }
            $lines.Add(('get "' + $remote + '" "' + $local + '"'))
        }
        [IO.File]::WriteAllLines($batch, $lines, [Text.Encoding]::ASCII)
        $sftp = Get-OpenSshTool 'sftp.exe'
        $arguments = @(
            '-q', '-b', $batch, '-F', 'NUL', '-P', [string]$Connection.port,
            '-i', $KeyPath,
            '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'PreferredAuthentications=publickey',
            '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
            '-o', 'StrictHostKeyChecking=yes', '-o', ('UserKnownHostsFile=' + $KnownHostsPath),
            '-o', 'ConnectTimeout=30', '-o', 'ConnectionAttempts=2', '-o', 'LogLevel=ERROR',
            ([string]$Connection.username + '@' + [string]$Connection.host)
        )
        Push-Location -LiteralPath $BackupRoot
        try { $output = & $sftp @arguments 2>&1 | Out-String; $exitCode = $LASTEXITCODE }
        finally { Pop-Location }
        if ($exitCode -ne 0) {
            $safeOutput = ($output -replace '[\r\n]+', ' ').Trim()
            if ($safeOutput.Length -gt 300) { $safeOutput = $safeOutput.Substring(0, 300) }
            throw ('只读备份连接失败：' + $safeOutput)
        }
    } finally {
        if (Test-Path -LiteralPath $batch) { Remove-Item -LiteralPath $batch -Force }
    }
}
