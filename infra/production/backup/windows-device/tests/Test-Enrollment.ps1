#requires -Version 5.1
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$packageRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
. (Join-Path $packageRoot 'BackupDevice.Common.ps1')
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
function Invoke-PrepareFixture([string]$Directory, [string]$Answer) {
    $start = [Diagnostics.ProcessStartInfo]::new()
    $start.FileName = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
    $start.Arguments = '-NoProfile -ExecutionPolicy Bypass -File "' + (Join-Path $packageRoot 'Prepare-BackupDevice.ps1') + '" -BackupRoot "' + $Directory + '"'
    $start.UseShellExecute = $false; $start.CreateNoWindow = $true
    $start.RedirectStandardInput = $true; $start.RedirectStandardOutput = $true; $start.RedirectStandardError = $true
    $process = [Diagnostics.Process]::new(); $process.StartInfo = $start
    try {
        $process.Start() | Out-Null
        $output = $process.StandardOutput.ReadToEndAsync(); $errors = $process.StandardError.ReadToEndAsync()
        $process.StandardInput.WriteLine($Answer); $process.StandardInput.Close()
        if (-not $process.WaitForExit(30000)) { $process.Kill(); throw 'Preparation test timed out' }
        $outText = $output.GetAwaiter().GetResult(); $errText = $errors.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw ('Preparation test failed: ' + $outText + $errText) }
    } finally { $process.Dispose() }
}

$testParent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$testRoot = Join-Path $testParent ('SfgBackupEnrollmentTests-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $testRoot -ItemType Directory | Out-Null
try {
    foreach ($scriptFile in Get-ChildItem -LiteralPath $packageRoot -Filter '*.ps1') {
        $tokens = $null; $parseErrors = $null
        [Management.Automation.Language.Parser]::ParseFile($scriptFile.FullName, [ref]$tokens, [ref]$parseErrors) | Out-Null
        Assert-True ($parseErrors.Count -eq 0) ('PowerShell 5.1 parser: ' + $scriptFile.Name)
        $bytes = [IO.File]::ReadAllBytes($scriptFile.FullName)
        Assert-True ($bytes[0] -eq 239 -and $bytes[1] -eq 187 -and $bytes[2] -eq 191) ('Chinese UTF8 BOM: ' + $scriptFile.Name)
    }
    foreach ($bad in @('E:\', '\\server\share', 'E:\a\..\b', 'E:\a\.', 'E:\a*', 'E:\a:stream', 'E:\a"b', 'E:\a ')) {
        Assert-Throws { Resolve-BackupDirectory $bad } ('Reject unsafe path ' + $bad)
    }
    $ownedPath = Join-Path $testRoot '登记目录 中文 spaces'
    $marker = Initialize-BackupDirectory $ownedPath
    Assert-True ((Initialize-BackupDirectory $ownedPath).deviceId -eq $marker.deviceId) 'Repeated preparation keeps device identity'
    $foreignPath = Join-Path $testRoot 'existing-data'
    New-Item -Path $foreignPath -ItemType Directory | Out-Null
    $sentinelPath = Join-Path $foreignPath 'user-data.txt'
    [IO.File]::WriteAllText($sentinelPath, 'preserve-me')
    Assert-Throws { Initialize-BackupDirectory $foreignPath } 'Existing user data blocks initialization'
    Assert-True ([IO.File]::ReadAllText($sentinelPath) -eq 'preserve-me') 'Existing user data preserved'
    $identityDirectory = Join-Path $ownedPath 'identity'
    $tool = Join-Path $env:WINDIR 'System32\OpenSSH\ssh-keygen.exe'
    $identity = Initialize-BackupIdentity $identityDirectory $marker.deviceId $tool
    $keyPath = Join-Path $identityDirectory 'device_ed25519'
    $keyHash = (Get-FileHash -LiteralPath $keyPath -Algorithm SHA256).Hash
    $identity2 = Initialize-BackupIdentity $identityDirectory $marker.deviceId $tool
    Assert-True ($identity2.fingerprint -eq $identity.fingerprint -and $keyHash -eq (Get-FileHash -LiteralPath $keyPath -Algorithm SHA256).Hash) 'Real Ed25519 identity is reused, never overwritten'
    $acl = Get-Acl -LiteralPath $identityDirectory
    $allowed = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')
    $rules = @($acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    Assert-True ($acl.AreAccessRulesProtected -and @($rules | Where-Object { $_.IsInherited -or $_.IdentityReference.Value -notin $allowed }).Count -eq 0) 'Only device owner, SYSTEM and administrators can access identity directory'
    $disk = [pscustomobject]@{ FileSystem = 'NTFS'; Size = 3000574678680; FreeSpace = 3000374235136 }
    $os = [pscustomobject]@{ Caption = 'Windows 11 Pro'; BuildNumber = '26200' }
    $enrollment = New-BackupEnrollment $marker $identity $disk $os
    $json = $enrollment | ConvertTo-Json
    Assert-True ($json -notmatch 'PRIVATE KEY|ownerSid|password|BEGIN OPENSSH|username' -and $enrollment.status -eq 'PENDING_SERVER_BINDING') 'Enrollment exports public information only and is explicitly not connected'
    $publicPath = Join-Path $ownedPath '连接登记.json'
    Write-NewBackupJson $publicPath $enrollment
    Assert-True ((Read-BackupJson $publicPath).publicKeyFingerprint -eq $identity.fingerprint) 'Chinese enrollment filename and JSON round trip'
    $registrationHash = (Get-FileHash -LiteralPath $publicPath -Algorithm SHA256).Hash
    Assert-Throws { Write-NewBackupJson $publicPath @{ replacement = $true } } 'Registration creation cannot overwrite files'
    Assert-True ((Get-FileHash -LiteralPath $publicPath -Algorithm SHA256).Hash -eq $registrationHash) 'Registration remains unchanged after rejected overwrite'
    $secondDirectory = Join-Path $testRoot 'other-identity'
    $otherIdentity = Initialize-BackupIdentity $secondDirectory ([guid]::NewGuid().ToString()) $tool
    $originalPublic = [IO.File]::ReadAllText($keyPath + '.pub')
    [IO.File]::WriteAllText($keyPath + '.pub', $otherIdentity.publicKey)
    Assert-Throws { Get-BackupPublicIdentity $tool $keyPath } 'Mismatched public and private keys rejected'
    [IO.File]::WriteAllText($keyPath + '.pub', $originalPublic)
    [IO.File]::Move($keyPath + '.pub', $keyPath + '.pub.saved')
    Assert-Throws { Initialize-BackupIdentity $identityDirectory $marker.deviceId $tool } 'Incomplete identity is not silently regenerated'
    Assert-True ($keyHash -eq (Get-FileHash -LiteralPath $keyPath -Algorithm SHA256).Hash) 'Incomplete pair does not alter private key'
    [IO.File]::Move($keyPath + '.pub.saved', $keyPath + '.pub')
    $junction = Join-Path $testRoot 'junction'
    New-Item -Path $junction -ItemType Junction -Value $ownedPath | Out-Null
    Assert-Throws { Resolve-BackupDirectory (Join-Path $junction 'child') } 'Directory junctions cannot redirect setup'
    [IO.Directory]::Delete($junction)
    $cancelledDirectory = Join-Path $testRoot 'cancelled'
    Invoke-PrepareFixture $cancelledDirectory 'NO'
    Assert-True (-not (Test-Path -LiteralPath $cancelledDirectory)) 'Full script cancellation creates no directory'
    $mainDirectory = Join-Path $testRoot '完整流程 中文'
    Invoke-PrepareFixture $mainDirectory 'YES'
    $mainRequestPath = Join-Path $mainDirectory '连接登记.json'
    $mainRequest = Read-BackupJson $mainRequestPath
    Assert-True ($mainRequest.status -eq 'PENDING_SERVER_BINDING' -and $mainRequest.backupRoot -ceq $mainDirectory -and [long]$mainRequest.availableBytes -gt 0) 'Full Windows 11 setup generates enrollment in isolated test directory'
    $mainKey = Join-Path $mainDirectory 'identity\device_ed25519'
    $mainKeyHash = (Get-FileHash -LiteralPath $mainKey -Algorithm SHA256).Hash
    $mainRequestHash = (Get-FileHash -LiteralPath $mainRequestPath -Algorithm SHA256).Hash
    Invoke-PrepareFixture $mainDirectory 'YES'
    Assert-True ($mainKeyHash -eq (Get-FileHash -LiteralPath $mainKey -Algorithm SHA256).Hash -and $mainRequestHash -eq (Get-FileHash -LiteralPath $mainRequestPath -Algorithm SHA256).Hash) 'Full setup retry preserves key and enrollment file'
    Write-Host ('TOTAL PASSED: ' + $script:passed)
} finally {
    # Remove only this run's isolated fixture, never a user backup directory.
    $resolvedTest = [IO.Path]::GetFullPath($testRoot)
    if ([IO.Path]::GetDirectoryName($resolvedTest) -cne $testParent -or [IO.Path]::GetFileName($resolvedTest) -notmatch '^SfgBackupEnrollmentTests-[0-9a-f]{32}$') {
        throw 'Refusing cleanup outside this test fixture'
    }
    Remove-Item -LiteralPath $resolvedTest -Recurse -Force
}
