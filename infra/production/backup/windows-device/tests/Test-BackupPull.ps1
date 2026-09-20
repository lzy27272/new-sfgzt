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

    $atomicPath = Join-Path $testRoot 'status.json'
    Write-BackupPullJsonAtomic $atomicPath ([pscustomobject]@{ status='FIRST' })
    Write-BackupPullJsonAtomic $atomicPath ([pscustomobject]@{ status='SECOND' })
    Assert-True ((Read-BackupPullJson $atomicPath).status -ceq 'SECOND') 'Atomic JSON replacement succeeds'

    $safeChild = New-BackupPullDirectory $testRoot (Join-Path $testRoot 'safe\child')
    Assert-True ([IO.Directory]::Exists($safeChild)) 'Safe directory created inside backup root'
    Assert-Throws { Assert-BackupPullPath $testRoot (Join-Path $testParent 'outside') } 'Path outside backup root rejected'
    $junction = Join-Path $testRoot 'junction'
    New-Item -Path $junction -ItemType Junction -Value (Join-Path $testRoot 'safe') | Out-Null
    Assert-Throws { Assert-BackupPullPath $testRoot (Join-Path $junction 'child') } 'Directory junction rejected'
    [IO.Directory]::Delete($junction)
    $installerText = [IO.File]::ReadAllText((Join-Path $packageRoot 'Install-BackupPull.ps1'), [Text.Encoding]::UTF8)
    Assert-True ($installerText.Contains('& $powerShell -NoProfile -NonInteractive -ExecutionPolicy Bypass')) 'Initial sync runs in a child PowerShell process so installer continues'
    Assert-True (-not $installerText.Contains("& (Join-Path `$program 'Invoke-BackupPull.ps1')")) 'Installer does not invoke exiting sync script in-process'
    Write-Host ('TOTAL PASSED: ' + $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -cne $testParent -or
        [IO.Path]::GetFileName($resolved) -notmatch '^SfgBackupPullTests-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside this test fixture'
    }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
