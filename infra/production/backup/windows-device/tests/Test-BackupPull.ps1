#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$packageRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $packageRoot 'BackupPull.Common.ps1')

$script:passed = 0
function Assert-True([bool]$Condition, [string]$Label) {
    if (-not $Condition) { throw ('FAIL: ' + $Label) }
    $script:passed++; Write-Host ('PASS: ' + $Label)
}
function Assert-Throws([scriptblock]$Action, [string]$Label) {
    $didThrow = $false
    try { & $Action | Out-Null } catch { $didThrow = $true }
    Assert-True $didThrow $Label
}
function Write-FixtureJson([string]$Path, [object]$Value) {
    $bytes = [Text.UTF8Encoding]::new($false).GetBytes(($Value | ConvertTo-Json -Depth 8))
    [IO.File]::WriteAllBytes($Path, $bytes)
}
function New-ValidManifest {
    return [pscustomobject][ordered]@{
        kind = 'SFG_BACKUP_MANIFEST_V1'; schemaVersion = 1
        generatedAt = [DateTime]::UtcNow.ToString('o'); manifestId = ('a' * 64)
        receiptPath = ('receipts/' + ('a' * 64) + '.ok')
        entries = @(
            [pscustomobject]@{ path='hotel-ai-os/daily/hotel_ai_os-daily-20260920T030000+0800.dump.enc'; category='hotel-ai-os'; tier='daily'; bytes=123; sha256=('b' * 64) },
            [pscustomobject]@{ path='sifangguan-ota/daily/sifangguan-ota-state-daily-20260920T030000+0800.tar.gz.enc'; category='sifangguan-ota'; tier='daily'; bytes=456; sha256=('c' * 64) },
            [pscustomobject]@{ path='analytics-raw/daily/analytics-raw-2026-09-19.tar.gz.enc'; category='analytics-raw'; tier='daily'; bytes=789; sha256=('d' * 64) }
        )
    }
}

$testParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = Join-Path $testParent ('SfgBackupPullTests-' + [guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testRoot) | Out-Null
try {
    foreach ($safe in @(
        'hotel-ai-os/daily/hotel_ai_os-daily-20260920T030000+0800.dump.enc',
        'hotel-ai-os/daily/hotel_ai_os-daily-20260920T030000+0800-release-6beb21b.dump.enc',
        'hotel-ai-os/monthly/hotel_ai_os-monthly-202609.dump.enc',
        'sifangguan-ota/yearly/sifangguan-ota-state-yearly-2026.tar.gz.enc',
        'analytics-raw/daily/analytics-raw-2026-09-19.tar.gz.enc')) {
        Assert-True (Test-BackupManagedRelativePath $safe) ('Allow managed path ' + $safe)
    }
    foreach ($unsafe in @(
        '../secret', '/absolute', 'hotel-ai-os/daily/file.txt',
        'hotel-ai-os/daily/../../secret.enc', '酒店/daily/file.enc',
        'analytics-raw/monthly/analytics-raw-2026-09-19.tar.gz.enc')) {
        Assert-True (-not (Test-BackupManagedRelativePath $unsafe)) ('Reject unmanaged path ' + $unsafe)
    }
    $manifestPath = Join-Path $testRoot 'manifest.json'
    Write-FixtureJson $manifestPath (New-ValidManifest)
    $parsed = Get-ValidatedBackupManifest $manifestPath
    Assert-True (@($parsed.entries).Count -eq 3) 'Valid manifest accepted'

    $rawRevision = New-ValidManifest
    $rawRevision.entries[2].path = 'analytics-raw/daily/analytics-raw-2026-09-19-' + $rawRevision.entries[2].sha256 + '.tar.gz.enc'
    Write-FixtureJson $manifestPath $rawRevision
    Assert-True (@((Get-ValidatedBackupManifest $manifestPath).entries).Count -eq 3) 'Immutable raw ciphertext revision accepted alongside legacy paths'
    $rawRevision.entries[2].sha256 = 'e'*64
    Write-FixtureJson $manifestPath $rawRevision
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Raw revision name and ciphertext digest mismatch rejected'

    $duplicate = New-ValidManifest
    $duplicate.entries = @($duplicate.entries[0], $duplicate.entries[0], $duplicate.entries[1])
    Write-FixtureJson $manifestPath $duplicate
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Duplicate manifest path rejected'

    $traversal = New-ValidManifest
    $traversal.entries[0].path = 'hotel-ai-os/daily/../../etc/shadow.enc'
    Write-FixtureJson $manifestPath $traversal
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Manifest traversal rejected'

    $mismatch = New-ValidManifest
    $mismatch.entries[0].category = 'sifangguan-ota'
    Write-FixtureJson $manifestPath $mismatch
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Manifest category mismatch rejected'

    $stale = New-ValidManifest
    $stale.generatedAt = [DateTime]::UtcNow.AddHours(-49).ToString('o')
    Write-FixtureJson $manifestPath $stale
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Stale manifest rejected'

    $incremental = New-ValidManifest
    $incremental.entries[1].path = $incremental.entries[1].path.Replace('.tar.gz.enc','.snapshot.json.enc')
    $objectPaths = @()
    for ($n=1; $n -le 501; $n++) {
        $digest = $n.ToString('x64'); $objectPath = 'sifangguan-ota/objects/' + $digest + '.pack.enc'
        $objectPaths += $objectPath
        $incremental.entries += [pscustomobject]@{path=$objectPath;category='sifangguan-ota';tier='objects';bytes=12;sha256=$digest}
    }
    $incremental | Add-Member NoteProperty recoveryPoints @([pscustomobject]@{
        statePath=$incremental.entries[1].path;stateSha256=$incremental.entries[1].sha256
        databasePath=$incremental.entries[0].path;databaseSha256=$incremental.entries[0].sha256
        snapshotId='20260920T030000+0800';objects=$objectPaths })
    Write-FixtureJson $manifestPath $incremental
    Assert-True (@((Get-ValidatedBackupManifest $manifestPath).entries).Count -eq 504) 'Complete incremental closure beyond 500 entries accepted'
    $incremental.recoveryPoints[0].objects += ('sifangguan-ota/objects/' + ('f'*64) + '.pack.enc')
    Write-FixtureJson $manifestPath $incremental
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Missing incremental dependency fails closed'
    $incremental.recoveryPoints[0].objects = $objectPaths
    $incremental.entries[3].sha256 = 'f'*64
    Write-FixtureJson $manifestPath $incremental
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Object name and ciphertext digest mismatch rejected'
    $incremental.entries[3].sha256 = [IO.Path]::GetFileName([string]$incremental.entries[3].path).Substring(0,64)
    $incremental.recoveryPoints = @()
    Write-FixtureJson $manifestPath $incremental
    Assert-Throws { Get-ValidatedBackupManifest $manifestPath } 'Unbound incremental index cannot be accepted'
    $script:batchSizes = @()
    function Invoke-BackupSftpGet {
        param($Connection,$BackupRoot,$KeyPath,$KnownHostsPath,$Transfers)
        $script:batchSizes += $Transfers.Count
    }
    Invoke-BackupSftpBatches $null $testRoot 'test' 'test' @(1..1001)
    Assert-True (($script:batchSizes -join ',') -ceq '500,500,1') 'More than 500 transfers split into bounded real caller batches'
    Assert-True ((New-BackupIncomingName 200000 ('b'*64)).StartsWith('incoming/200000-')) 'Large manifest ordinals remain unique and bounded'
    Assert-Throws { New-BackupIncomingName 200001 ('b'*64) } 'Manifest ordinal upper bound remains enforced'

    $atomicPath = Join-Path $testRoot 'status.json'
    Write-BackupPullJsonAtomic $atomicPath ([pscustomobject]@{ status='FIRST' })
    Write-BackupPullJsonAtomic $atomicPath ([pscustomobject]@{ status='SECOND' })
    Assert-True ((Read-BackupPullJson $atomicPath).status -ceq 'SECOND') 'Atomic JSON replacement succeeds'

    $safeChild = New-BackupPullDirectory $testRoot (Join-Path $testRoot 'safe\child')
    Assert-True ([IO.Directory]::Exists($safeChild)) 'Safe directory created inside backup root'
    Assert-Throws { Assert-BackupPullPath $testRoot (Join-Path $testParent 'outside') } 'Path outside backup root rejected'
    $sharedHash = 'e' * 64
    $firstIncoming = New-BackupIncomingName 1 $sharedHash
    $secondIncoming = New-BackupIncomingName 2 $sharedHash
    Assert-True ($firstIncoming -ceq ('incoming/0001-' + $sharedHash + '.part')) 'Incoming name contains a stable transfer ordinal'
    Assert-True ($firstIncoming -cne $secondIncoming) 'Equal-content manifest entries receive different incoming paths'
    Assert-Throws { New-BackupIncomingName 0 $sharedHash } 'Invalid incoming ordinal rejected'
    $incomingRoot = New-BackupPullDirectory $testRoot (Join-Path $testRoot 'incoming')
    $legacyPart = Join-Path $incomingRoot (('a' * 64) + '.part')
    $numberedPart = Join-Path $incomingRoot ('0001-' + ('b' * 64) + '.part')
    [IO.File]::WriteAllText($legacyPart, 'legacy')
    [IO.File]::WriteAllText($numberedPart, 'numbered')
    Clear-BackupPullIncoming $testRoot
    Assert-True (-not (Test-Path -LiteralPath $legacyPart) -and -not (Test-Path -LiteralPath $numberedPart)) 'Managed partial downloads are cleared before retry'
    $unmanagedIncoming = Join-Path $incomingRoot 'keep.txt'
    [IO.File]::WriteAllText($unmanagedIncoming, 'keep')
    Assert-Throws { Clear-BackupPullIncoming $testRoot } 'Unmanaged incoming files block cleanup'
    Assert-True (Test-Path -LiteralPath $unmanagedIncoming -PathType Leaf) 'Unmanaged incoming file is preserved'
    [IO.File]::Delete($unmanagedIncoming)
    $junction = Join-Path $testRoot 'junction'
    New-Item -Path $junction -ItemType Junction -Value (Join-Path $testRoot 'safe') | Out-Null
    Assert-Throws { Assert-BackupPullPath $testRoot (Join-Path $junction 'child') } 'Directory junction rejected'
    [IO.Directory]::Delete($junction)
    $installerText = [IO.File]::ReadAllText((Join-Path $packageRoot 'Install-BackupPull.ps1'), [Text.Encoding]::UTF8)
    Assert-True ($installerText.Contains('Invoke-BackupSystemVerification $root')) 'Initial sync is verified under SYSTEM, not the interactive installer identity'
    Assert-True ($installerText.Contains('Set-BackupSystemKeyPermission $root')) 'Installer applies the SYSTEM-safe key policy'
    Assert-True (-not $installerText.Contains("& (Join-Path `$program 'Invoke-BackupPull.ps1')")) 'Installer does not invoke exiting sync script in-process'
    $launcherBytes = [IO.File]::ReadAllBytes((Join-Path $packageRoot '02-Install-BackupPull.cmd'))
    Assert-True (@($launcherBytes | Where-Object { $_ -gt 127 }).Count -eq 0) 'CMD launcher is ASCII-only for every Windows system code page'
    $launcherText = [Text.Encoding]::ASCII.GetString($launcherBytes)
    Assert-True (-not $launcherText.Contains('chcp ')) 'CMD launcher does not change code page while cmd.exe is parsing it'
    Write-Host ('TOTAL PASSED: ' + $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -cne $testParent -or
        [IO.Path]::GetFileName($resolved) -notmatch '^SfgBackupPullTests-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside this test fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
