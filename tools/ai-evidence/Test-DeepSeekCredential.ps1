Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$helper = Join-Path $PSScriptRoot 'Set-DeepSeekCredential.ps1'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ('sfg-deepseek-credential-test-' + [Guid]::NewGuid().ToString('N'))
$credentialPath = Join-Path $testRoot 'deepseek-api-key.clixml'
$fakeValue = 'test-only-not-a-real-api-key-20260925'
$fakeKey = ConvertTo-SecureString $fakeValue -AsPlainText -Force
try {
    $transcript = (& $helper -ApiKey $fakeKey -CredentialDirectory $testRoot 6>&1 | Out-String)
    if ($transcript.Contains($fakeValue)) { throw 'The helper exposed its input.' }
    $serialized = Get-Content -LiteralPath $credentialPath -Raw
    if ($serialized.Contains($fakeValue)) { throw 'The credential was stored in plaintext.' }
    $restored = Import-Clixml -LiteralPath $credentialPath
    if ($restored -isnot [Security.SecureString]) { throw 'The stored value is not a SecureString.' }
    if ((New-Object Net.NetworkCredential('', $restored)).Password -ne $fakeValue) { throw 'Current-user decryption did not match.' }
    $acl = Get-Acl -LiteralPath $testRoot
    if (-not $acl.AreAccessRulesProtected) { throw 'Credential permissions are inherited.' }
    $ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User.Value
    foreach ($rule in $acl.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier])) {
        if ($rule.AccessControlType -eq 'Allow' -and $rule.IdentityReference.Value -ne $ownerSid) { throw 'An unintended principal has access.' }
    }
    Write-Output 'PASS: encrypted round-trip, private ACL, no key in output'

    $originalHash = (Get-FileHash -LiteralPath $credentialPath -Algorithm SHA256).Hash
    $refused = $false
    try { & $helper -ApiKey $fakeKey -CredentialDirectory $testRoot 6>$null } catch { $refused = $true }
    if (-not $refused -or (Get-FileHash -LiteralPath $credentialPath -Algorithm SHA256).Hash -ne $originalHash) {
        throw 'Existing credentials were overwritten without explicit replacement.'
    }
    Write-Output 'PASS: existing credential is preserved without -Replace'

    $updatedValue = 'replacement-test-only-not-a-real-api-key'
    & $helper -ApiKey (ConvertTo-SecureString $updatedValue -AsPlainText -Force) -CredentialDirectory $testRoot -Replace 6>$null
    $updated = Import-Clixml -LiteralPath $credentialPath
    if ((New-Object Net.NetworkCredential('', $updated)).Password -ne $updatedValue) { throw 'Explicit replacement failed.' }
    Write-Output 'PASS: explicit replacement preserves encrypted storage'

    $invalidRoot = Join-Path $testRoot 'invalid'
    $refused = $false
    try { & $helper -ApiKey (ConvertTo-SecureString 'short' -AsPlainText -Force) -CredentialDirectory $invalidRoot 6>$null } catch { $refused = $true }
    if (-not $refused -or (Test-Path -LiteralPath $invalidRoot)) { throw 'Invalid input changed the credential store.' }
    Write-Output 'PASS: invalid input does not create a credential'
} finally {
    # Delete only the disposable fake credential and its exact, non-recursive temporary directory.
    if (Test-Path -LiteralPath $credentialPath) { Remove-Item -LiteralPath $credentialPath -Force }
    if (Test-Path -LiteralPath $testRoot) { Remove-Item -LiteralPath $testRoot }
}
