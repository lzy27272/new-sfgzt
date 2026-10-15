#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$sourceRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $sourceRoot 'BackupPull.Common.ps1')
. (Join-Path $sourceRoot 'BackupSystem.Common.ps1')
$script:passed = 0
function Assert-True([bool]$Condition,[string]$Label) {
    if (-not $Condition) { throw ('FAIL: ' + $Label) }
    $script:passed++; Write-Host ('PASS: ' + $Label)
}
function New-Success([guid]$RunId) {
    [pscustomobject]@{
        kind='SFG_BACKUP_SYNC_RESULT_V1'; schemaVersion=1; status='SUCCESS'; runId=$RunId.ToString()
        executionIdentitySid='S-1-5-18'; completedAt=[DateTime]::UtcNow.ToString('o')
        receiptVerified=$true; totalEntries=2; manifestId=('a'*64)
    }
}
$tempParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = Join-Path $tempParent ('SfgBackupSystemTests-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $testRoot -ItemType Directory | Out-Null
try {
    $powerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $probe = "[Console]::Out.WriteLine('output ok'); [Console]::Error.WriteLine('first error'); [Console]::Error.WriteLine('last error'); exit 23"
    $encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes($probe))
    $native = Invoke-BackupNativeProcess $powerShell @('-NoProfile','-NonInteractive','-EncodedCommand',$encoded)
    Assert-True ($native.exitCode -eq 23 -and -not $native.timedOut) 'Native nonzero exit code captured without a PowerShell stderr exception'
    Assert-True ($native.stdout.Contains('output ok') -and $native.stderr.Contains('first error') -and $native.stderr.Contains('last error')) 'All native output streams retained'
    $ssh = Join-Path $env:WINDIR 'System32\OpenSSH\ssh.exe'
    $version = Invoke-BackupNativeProcess $ssh @('-V')
    Assert-True ($version.exitCode -eq 0 -and $version.stderr.Contains('OpenSSH')) 'Successful native stderr is not treated as failure'
    $samplePath = 'E:\test space\中台\known_hosts'
    $config = Invoke-BackupNativeProcess $ssh @('-G','-F','NUL','-o',('UserKnownHostsFile="' + $samplePath + '"'),'127.0.0.1')
    Assert-True ($config.exitCode -eq 0 -and $config.stdout -match 'userknownhostsfile .*test space.*known_hosts') 'Quoted Unicode/space path accepted by offline OpenSSH config'
    Assert-True ((ConvertTo-BackupNativeArgument 'E:\a b\') -ceq '"E:\a b\\"') 'Trailing backslashes are quoted correctly'
    $slow = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('Start-Sleep -Seconds 30'))
    $timed = Invoke-BackupNativeProcess $powerShell @('-NoProfile','-NonInteractive','-EncodedCommand',$slow) '' 1
    Assert-True $timed.timedOut 'Timed-out child process is stopped within the bounded invocation'

    $acl = New-BackupSystemKeyAcl
    $rules = @($acl.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    Assert-True ($acl.GetOwner([Security.Principal.SecurityIdentifier]).Value -ceq 'S-1-5-32-544') 'Private-key owner is a trusted administrator group, not a named user'
    Assert-True ($acl.AreAccessRulesProtected -and $rules.Count -eq 2) 'Private-key ACL is explicit and does not inherit access'
    Assert-True ((@($rules | ForEach-Object { $_.IdentityReference.Value } | Sort-Object) -join ',') -ceq 'S-1-5-18,S-1-5-32-544') 'Only SYSTEM and Administrators retain key access'

    $run = [guid]::NewGuid(); $start = [DateTimeOffset]::UtcNow.AddSeconds(-1)
    $result = New-Success $run
    Assert-True (Test-BackupSystemSyncResult $result $run $start) 'Fresh SYSTEM success with verified manifest accepted'
    Assert-True (-not (Test-BackupSystemSyncResult $result ([guid]::NewGuid()) $start)) 'Previous-run success is rejected'
    $result.executionIdentitySid='S-1-5-21-1-2-3-1001'
    Assert-True (-not (Test-BackupSystemSyncResult $result $run $start)) 'Interactive-user success is rejected'
    $result=New-Success $run; $result.completedAt=[DateTime]::UtcNow.AddDays(-1).ToString('o')
    Assert-True (-not (Test-BackupSystemSyncResult $result $run $start)) 'Old timestamp is rejected'
    $result=New-Success $run; $result.receiptVerified='true'
    Assert-True (-not (Test-BackupSystemSyncResult $result $run $start)) 'String truthiness cannot bypass receipt verification'
    $result=New-Success $run; $result.totalEntries=0
    Assert-True (-not (Test-BackupSystemSyncResult $result $run $start)) 'Empty verification cannot be called success'
    Assert-True (-not (Test-BackupSystemSyncResult ([pscustomobject]@{status='SUCCESS'}) $run $start)) 'Legacy result without execution identity is rejected'

    # Mock Task Scheduler only inside this test process. No real tasks created.
    New-Item -Path (Join-Path $testRoot 'program') -ItemType Directory | Out-Null
    [IO.File]::WriteAllText((Join-Path $testRoot 'program\Invoke-BackupPull.ps1'),'# fixture')
    $script:scenario='SUCCESS'; $script:registeredNames=@(); $script:removedNames=@()
    function New-ScheduledTaskAction { param($Execute,$Argument)
        if ($Argument -notmatch '-RunId ([0-9a-f-]{36})') { throw 'Missing run id' }
        $script:mockRun=[guid]$Matches[1]
        Assert-True ($Argument.Contains('-PreserveExistingBackups')) 'Verification opts out of backup retention cleanup'
        [pscustomobject]@{Execute=$Execute;Arguments=$Argument}
    }
    function New-ScheduledTaskPrincipal { param($UserId,$LogonType,$RunLevel)
        Assert-True ($UserId -eq 'SYSTEM' -and $LogonType -eq 'ServiceAccount') 'Verification actually requests SYSTEM principal'
        [pscustomobject]@{UserId=$UserId}
    }
    function New-ScheduledTaskSettingsSet { param($MultipleInstances,$ExecutionTimeLimit,[switch]$AllowStartIfOnBatteries,[switch]$DontStopIfGoingOnBatteries) [pscustomobject]@{} }
    function Register-ScheduledTask { param($TaskName,$Action,$Principal,$Settings,$Description) $script:registeredNames+= $TaskName }
    function Start-ScheduledTask { param($TaskName)
        $mock=New-Success $script:mockRun
        if ($script:scenario -eq 'STALE') { $mock.runId=[guid]::NewGuid().ToString() }
        if ($script:scenario -eq 'INTERACTIVE') { $mock.executionIdentitySid='S-1-5-21-1-2-3-1001' }
        Write-BackupPullJsonAtomic (Join-Path $testRoot '同步结果.json') $mock
    }
    function Get-ScheduledTask { param($TaskName) [pscustomobject]@{State=$(if($script:scenario-eq'PENDING'){'Running'}else{'Ready'})} }
    function Get-ScheduledTaskInfo { param($TaskName) [pscustomobject]@{LastRunTime=[DateTime]::Now;LastTaskResult=$(if($script:scenario-eq'EXIT_ERROR'){1}else{0})} }
    function Unregister-ScheduledTask { param($TaskName,[switch]$Confirm) $script:removedNames+=$TaskName }
    foreach ($scenario in @('SUCCESS','STALE','INTERACTIVE','EXIT_ERROR','PENDING')) {
        $script:scenario=$scenario
        $verification=Invoke-BackupSystemVerification $testRoot 1
        $expected = if($scenario-eq'SUCCESS'){'SUCCESS'}elseif($scenario-eq'PENDING'){'PENDING'}else{'FAILED'}
        Assert-True ($verification.status -ceq $expected) ('Scheduler verification result: ' + $scenario)
    }
    Assert-True (@($script:registeredNames | Where-Object { $_ -eq 'Sifangguan Encrypted Backup Pull' }).Count -eq 0) 'Original daily task is never registered or modified'
    Assert-True ($script:removedNames.Count -eq 4) 'Only finished temporary tasks removed; running verification is preserved'
    Write-Host ('TOTAL PASSED: ' + $script:passed)
} finally {
    $resolved = [IO.Path]::GetFullPath($testRoot)
    if ([IO.Path]::GetDirectoryName($resolved) -cne $tempParent -or
        [IO.Path]::GetFileName($resolved) -notmatch '^SfgBackupSystemTests-[0-9a-f]{32}$') { throw 'Unsafe fixture cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
