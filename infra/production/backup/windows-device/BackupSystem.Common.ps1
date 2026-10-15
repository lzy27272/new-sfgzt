#requires -Version 5.1
Set-StrictMode -Version Latest

function New-BackupSystemKeyAcl {
    # OpenSSH accepts the Administrators group as owner in both an elevated
    # interactive session and a SYSTEM session. A named user's ownership is not
    # sufficient merely because SYSTEM also has an allow ACE.
    $acl = [Security.AccessControl.FileSecurity]::new()
    $acl.SetOwner([Security.Principal.SecurityIdentifier]::new('S-1-5-32-544'))
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @('S-1-5-18','S-1-5-32-544')) {
        $acl.AddAccessRule([Security.AccessControl.FileSystemAccessRule]::new(
            [Security.Principal.SecurityIdentifier]::new($sid),
            [Security.AccessControl.FileSystemRights]::FullControl,
            [Security.AccessControl.AccessControlType]::Allow))
    }
    return $acl
}

function Set-BackupSystemKeyPermission {
    param([string]$BackupRoot)
    $keyPath = Assert-BackupPullPath $BackupRoot (Join-Path $BackupRoot 'identity\device_ed25519')
    $entry = Get-Item -LiteralPath $keyPath -Force -ErrorAction Stop
    if ($entry.PSIsContainer -or $entry.Length -le 0 -or $entry.Length -gt 16384) {
        throw 'Invalid registered backup private-key file.'
    }
    # Changes only this registered key's owner and DACL, never its contents.
    [IO.File]::SetAccessControl($keyPath, (New-BackupSystemKeyAcl))
    $actual = Get-Acl -LiteralPath $keyPath
    $rules = @($actual.GetAccessRules($true,$true,[Security.Principal.SecurityIdentifier]))
    if ($actual.GetOwner([Security.Principal.SecurityIdentifier]).Value -cne 'S-1-5-32-544' -or
        -not $actual.AreAccessRulesProtected -or $rules.Count -ne 2 -or
        @($rules | Where-Object { $_.IdentityReference.Value -notin @('S-1-5-18','S-1-5-32-544') -or
            $_.AccessControlType -ne 'Allow' -or $_.FileSystemRights -ne 'FullControl' }).Count -ne 0) {
        throw 'Backup key owner/permissions did not match the SYSTEM-safe policy.'
    }
}

function Test-BackupSystemSyncResult {
    param([object]$Result, [guid]$RunId, [DateTimeOffset]$StartedAt)
    if ($null -eq $Result) { return $false }
    foreach ($name in @('kind','schemaVersion','status','runId','executionIdentitySid','completedAt',
        'receiptVerified','totalEntries','manifestId')) {
        if ($null -eq $Result.PSObject.Properties[$name]) { return $false }
    }
    try { $completed = [DateTimeOffset]$Result.completedAt } catch { return $false }
    return ($Result.kind -ceq 'SFG_BACKUP_SYNC_RESULT_V1' -and [int]$Result.schemaVersion -eq 1 -and
        $Result.status -ceq 'SUCCESS' -and $Result.runId -ceq $RunId.ToString() -and
        $Result.executionIdentitySid -ceq 'S-1-5-18' -and
        $completed -ge $StartedAt -and $completed -le [DateTimeOffset]::UtcNow.AddMinutes(1) -and
        $Result.receiptVerified -is [bool] -and $Result.receiptVerified -and
        [int]$Result.totalEntries -ge 2 -and (Test-LowerSha256 ([string]$Result.manifestId)))
}

function Invoke-BackupSystemVerification {
    param([string]$BackupRoot, [ValidateRange(1,600)][int]$WaitSeconds = 180)
    $scriptPath = Assert-BackupPullPath $BackupRoot (Join-Path $BackupRoot 'program\Invoke-BackupPull.ps1')
    if (-not (Test-Path -LiteralPath $scriptPath -PathType Leaf)) { throw 'Installed backup program is missing.' }
    $runId = [guid]::NewGuid()
    $taskName = 'Sifangguan Backup Verify ' + $runId.ToString('N')
    $started = [DateTimeOffset]::UtcNow
    $powerShell = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $arguments = '-NoProfile -NonInteractive -ExecutionPolicy Bypass -File ' +
        (ConvertTo-BackupNativeArgument $scriptPath) + ' -BackupRoot ' +
        (ConvertTo-BackupNativeArgument $BackupRoot) + ' -RunId ' + $runId.ToString() + ' -PreserveExistingBackups'
    $registered = $false
    try {
        $action = New-ScheduledTaskAction -Execute $powerShell -Argument $arguments
        $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
        $settings = New-ScheduledTaskSettingsSet -MultipleInstances IgnoreNew -ExecutionTimeLimit (New-TimeSpan -Hours 6) `
            -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries
        # No triggers: this one-shot check never alters the existing daily task.
        Register-ScheduledTask -TaskName $taskName -Action $action -Principal $principal -Settings $settings `
            -Description 'One-time SYSTEM backup verification; preserves existing backup versions' | Out-Null
        $registered = $true
        Start-ScheduledTask -TaskName $taskName
        $deadline = [DateTimeOffset]::UtcNow.AddSeconds($WaitSeconds)
        do {
            $task = Get-ScheduledTask -TaskName $taskName
            $info = Get-ScheduledTaskInfo -TaskName $taskName
            $result = $null
            $resultPath = Join-Path $BackupRoot '同步结果.json'
            if (Test-Path -LiteralPath $resultPath -PathType Leaf) {
                $candidate = Read-BackupPullJson $resultPath 65536
                if ($null -ne $candidate.PSObject.Properties['runId'] -and $candidate.runId -ceq $runId.ToString()) {
                    $result = $candidate
                }
            }
            if ($task.State -notin @('Running','Queued') -and
                [DateTimeOffset]$info.LastRunTime -ge $started.AddSeconds(-1)) {
                $success = ($info.LastTaskResult -eq 0 -and (Test-BackupSystemSyncResult $result $runId $started))
                return [pscustomobject]@{
                    status = $(if ($success) { 'SUCCESS' } else { 'FAILED' })
                    runId = $runId.ToString(); taskName = $taskName; syncResult = $result
                    taskResult = $info.LastTaskResult
                    message = $(if ($success) { 'Fresh SYSTEM synchronization and every manifest entry verified.' }
                        else { 'SYSTEM synchronization did not produce a valid fresh success receipt. Inspect logs/connection-error-last.log.' })
                }
            }
            Start-Sleep -Seconds 2
        } while ([DateTimeOffset]::UtcNow -lt $deadline)
        return [pscustomobject]@{
            status = 'PENDING'; runId = $runId.ToString(); taskName = $taskName; syncResult = $result
            taskResult = $info.LastTaskResult
            message = 'Verification has not finished. The one-shot task may continue; no success is claimed.'
        }
    } finally {
        if ($registered) {
            try {
                $task = Get-ScheduledTask -TaskName $taskName
                if ($task.State -notin @('Running','Queued')) {
                    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
                }
            } catch { Write-Warning ('One-shot verification task cleanup: ' + $_.Exception.Message) }
        }
    }
}
