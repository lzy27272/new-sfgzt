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
        try { $stream.Write($bytes, 0, $bytes.Length); $stream.Flush($true) }
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
        '^analytics-raw/daily/analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}(?:-[0-9a-f]{64})?[.]tar[.]gz[.]enc$',
        '^sifangguan-ota/daily/sifangguan-ota-state-daily-[0-9]{8}T[0-9]{6}[+-][0-9]{4}[.]snapshot[.]json[.]enc$',
        '^sifangguan-ota/monthly/sifangguan-ota-state-monthly-[0-9]{6}[.]snapshot[.]json[.]enc$',
        '^sifangguan-ota/yearly/sifangguan-ota-state-yearly-[0-9]{4}[.]snapshot[.]json[.]enc$',
        '^sifangguan-ota/objects/[0-9a-f]{64}[.]pack[.]enc$'
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
    if ($Ordinal -lt 1 -or $Ordinal -gt 200000 -or -not (Test-LowerSha256 $Sha256)) {
        throw '下载临时文件编号或校验值无效。'
    }
    return ('incoming/{0:D4}-{1}.part' -f $Ordinal, $Sha256)
}

function Clear-BackupPullIncoming {
    param([Parameter(Mandatory=$true)][string]$Root)
    $incoming = New-BackupPullDirectory $Root (Join-Path $Root 'incoming')
    foreach ($entry in Get-ChildItem -LiteralPath $incoming -Force -ErrorAction Stop) {
        if ($entry.PSIsContainer -or ($entry.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $entry.Name -cnotmatch '^(?:[0-9]{4,6}-)?[0-9a-f]{64}[.]part$') {
            throw '下载临时目录包含非受管文件，拒绝自动清理。'
        }
        [IO.File]::Delete($entry.FullName)
    }
}

function Get-ValidatedBackupManifest {
    param([Parameter(Mandatory=$true)][string]$Path)
    $manifest = Read-BackupPullJson $Path 134217728
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
    if ($entries.Count -lt 2 -or $entries.Count -gt 200000) { throw '服务器备份清单条目数量异常。' }
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
        if ($parts[1] -ceq 'objects' -and $parts[2].Substring(0,64) -cne [string]$entry.sha256) {
            throw '内容包名称与密文校验值不一致。'
        }
        if ($relative -cmatch '^analytics-raw/daily/analytics-raw-[0-9]{4}-[0-9]{2}-[0-9]{2}-([0-9a-f]{64})[.]tar[.]gz[.]enc$') {
            if ($Matches[1] -cne [string]$entry.sha256) { throw '原始证据修订包名称与密文校验值不一致。' }
        }
        $seen[$relative] = $entry
        $categories[$parts[0]] = $true
    }
    foreach ($required in @('hotel-ai-os', 'sifangguan-ota')) {
        if (-not $categories.ContainsKey($required)) { throw ('服务器清单缺少必要分类：' + $required) }
    }
    $roots = @{}; $objects = @{}
    $points = @()
    if ($manifest.PSObject.Properties.Name -contains 'recoveryPoints') { $points = @($manifest.recoveryPoints) }
    foreach ($point in $points) {
        $statePath = [string]$point.statePath; $databasePath = [string]$point.databasePath
        if (-not $seen.ContainsKey($statePath) -or -not $seen.ContainsKey($databasePath) -or
            -not $statePath.EndsWith('.snapshot.json.enc') -or $roots.ContainsKey($statePath)) {
            throw '增量恢复点缺少唯一的状态索引或数据库配对。'
        }
        $stateEntry = $seen[$statePath]; $databaseEntry = $seen[$databasePath]
        if ([string]$stateEntry.sha256 -cne [string]$point.stateSha256 -or
            [string]$databaseEntry.sha256 -cne [string]$point.databaseSha256 -or
            [string]$stateEntry.tier -cne [string]$databaseEntry.tier -or
            [string]$databaseEntry.category -cne 'hotel-ai-os' -or
            [string]$point.snapshotId -cnotmatch '^[0-9]{8}T[0-9]{6}[+-][0-9]{4}$') {
            throw '增量恢复点配对校验失败。'
        }
        $roots[$statePath] = $true; $pointSeen = @{}
        if (@($point.objects).Count -eq 0) { throw '增量恢复点依赖闭包为空。' }
        foreach ($dependency in @($point.objects)) {
            $objectPath = [string]$dependency
            if (-not $seen.ContainsKey($objectPath) -or [string]$seen[$objectPath].tier -cne 'objects' -or $pointSeen.ContainsKey($objectPath)) {
                throw '增量恢复点内容包缺失或重复，已保留本地历史备份。'
            }
            $pointSeen[$objectPath] = $true; $objects[$objectPath] = $true
        }
    }
    foreach ($entry in $entries) {
        if (([string]$entry.path).EndsWith('.snapshot.json.enc') -and -not $roots.ContainsKey([string]$entry.path)) { throw '增量状态索引未绑定恢复闭包。' }
        if ([string]$entry.tier -ceq 'objects' -and -not $objects.ContainsKey([string]$entry.path)) { throw '内容包未绑定保留恢复点。' }
    }
    return $manifest
}

function Invoke-BackupSftpBatches {
    param([object]$Connection, [string]$BackupRoot, [string]$KeyPath, [string]$KnownHostsPath, [array]$Transfers)
    if ($Transfers.Count -lt 1 -or $Transfers.Count -gt 200000) { throw '下载任务总量异常。' }
    for ($offset = 0; $offset -lt $Transfers.Count; $offset += 500) {
        $last = [Math]::Min($offset + 499, $Transfers.Count - 1)
        Invoke-BackupSftpGet $Connection $BackupRoot $KeyPath $KnownHostsPath @($Transfers[$offset..$last])
    }
}

function Get-OpenSshTool {
    param([ValidateSet('sftp.exe','ssh-keygen.exe')][string]$Name)
    $path = Join-Path $env:WINDIR ('System32\OpenSSH\' + $Name)
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw ('缺少 Windows OpenSSH 客户端组件：' + $Name)
    }
    return $path
}

function ConvertTo-BackupNativeArgument {
    param([AllowEmptyString()][string]$Value)
    if ($Value -match '[\x00\r\n]') { throw 'Native argument contains a control character.' }
    $escaped = [regex]::Replace($Value, '(\\*)"', '$1$1\"')
    return ('"' + [regex]::Replace($escaped, '(\\+)$', '$1$1') + '"')
}

function Invoke-BackupNativeProcess {
    param(
        [string]$Executable, [string[]]$Arguments, [string]$WorkingDirectory = '',
        [ValidateRange(1,18000)][int]$TimeoutSeconds = 30
    )
    # Windows PowerShell 5.1 turns redirected native stderr into terminating
    # errors under ErrorActionPreference=Stop. Capture both OS pipes directly.
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = $Executable
    $start.Arguments = (@($Arguments | ForEach-Object { ConvertTo-BackupNativeArgument $_ }) -join ' ')
    if ($WorkingDirectory) { $start.WorkingDirectory = $WorkingDirectory }
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true
    $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    try {
        if (-not $process.Start()) { throw 'Cannot start backup connection process.' }
        $stdout = $process.StandardOutput.ReadToEndAsync()
        $stderr = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.Close()
        $timedOut = -not $process.WaitForExit($TimeoutSeconds * 1000)
        if ($timedOut -and -not $process.HasExited) {
            # Only the process tree started by this invocation may be stopped.
            $stop = [Diagnostics.ProcessStartInfo]::new()
            $stop.FileName = Join-Path $env:WINDIR 'System32\taskkill.exe'
            $stop.Arguments = '/PID ' + [string]$process.Id + ' /T /F'
            $stop.UseShellExecute = $false; $stop.CreateNoWindow = $true
            $stop.RedirectStandardOutput = $true; $stop.RedirectStandardError = $true
            $killer = [Diagnostics.Process]::Start($stop)
            try { $killer.WaitForExit(5000) | Out-Null } finally { $killer.Dispose() }
            $process.WaitForExit(3000) | Out-Null
        }
        $outReady = $stdout.Wait(3000); $errReady = $stderr.Wait(3000)
        return [pscustomobject]@{
            timedOut = $timedOut
            exitCode = $(if ($process.HasExited) { $process.ExitCode } else { -1 })
            stdout = $(if ($outReady) { $stdout.GetAwaiter().GetResult() } else { '[output incomplete]' })
            stderr = $(if ($errReady) { $stderr.GetAwaiter().GetResult() } else { '[error output incomplete]' })
        }
    } finally { $process.Dispose() }
}

function Get-BackupKeyFingerprint {
    param([string]$KeyTool, [string]$PublicKeyPath)
    $processResult = Invoke-BackupNativeProcess $KeyTool @('-l','-E','sha256','-f',$PublicKeyPath)
    $output = $processResult.stdout
    if ($processResult.timedOut -or $processResult.exitCode -ne 0 -or $output -notmatch '(?:^|\s)(SHA256:[A-Za-z0-9+/]{43})(?:\s|$)') {
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
            '-vv', '-b', $batch, '-F', 'NUL', '-P', [string]$Connection.port,
            '-i', $KeyPath,
            '-o', 'BatchMode=yes', '-o', 'IdentitiesOnly=yes',
            '-o', 'PreferredAuthentications=publickey',
            '-o', 'PasswordAuthentication=no', '-o', 'KbdInteractiveAuthentication=no',
            '-o', 'StrictHostKeyChecking=yes', '-o', ('UserKnownHostsFile="' + $KnownHostsPath + '"'),
            '-o', 'ConnectTimeout=30', '-o', 'ConnectionAttempts=2',
            '-o', 'ServerAliveInterval=15', '-o', 'ServerAliveCountMax=3',
            ([string]$Connection.username + '@' + [string]$Connection.host)
        )
        $processResult = Invoke-BackupNativeProcess $sftp $arguments $BackupRoot 18000
        if ($processResult.timedOut -or $processResult.exitCode -ne 0) {
            $logs = New-BackupPullDirectory $BackupRoot (Join-Path $BackupRoot 'logs')
            $errorPath = Assert-BackupPullPath $BackupRoot (Join-Path $logs 'connection-error-last.log')
            $details = ('Time: ' + [DateTimeOffset]::Now.ToString('o') + "`r`nSID: " +
                [Security.Principal.WindowsIdentity]::GetCurrent().User.Value + "`r`nExit: " +
                $processResult.exitCode + "`r`nTimeout: " + $processResult.timedOut +
                "`r`nSTDOUT:`r`n" + $processResult.stdout + "`r`nSTDERR:`r`n" + $processResult.stderr)
            # One bounded diagnostic file; never log private-key contents.
            if ($details.Length -gt 131072) { $details = $details.Substring(0,65536) + "`r`n[truncated]`r`n" + $details.Substring($details.Length-65536) }
            [IO.File]::WriteAllText($errorPath, $details, [Text.UTF8Encoding]::new($true))
            throw ('只读备份连接失败（退出码 ' + $processResult.exitCode + '），完整原因见 logs\connection-error-last.log')
        }
    } finally {
        if (Test-Path -LiteralPath $batch) { Remove-Item -LiteralPath $batch -Force }
    }
}
