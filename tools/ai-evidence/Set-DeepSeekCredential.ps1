[CmdletBinding()]
param(
    [Security.SecureString]$ApiKey,
    [string]$CredentialDirectory = [IO.Path]::Combine([Environment]::GetFolderPath('LocalApplicationData'), 'Sifangguan', 'AI', 'credentials'),
    [switch]$Replace
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
if ($env:OS -ne 'Windows_NT') { throw 'This credential helper requires Windows DPAPI.' }
if (-not [IO.Path]::IsPathRooted($CredentialDirectory)) { throw 'An absolute credential directory is required.' }
$credentialRoot = [IO.Path]::GetFullPath($CredentialDirectory)
$credentialPath = Join-Path $credentialRoot 'deepseek-api-key.clixml'
if ((Test-Path -LiteralPath $credentialPath) -and -not $Replace) {
    throw 'A protected credential already exists. To change it, run this script with -Replace.'
}
if ($null -eq $ApiKey) {
    Write-Host 'DeepSeek official API key: input is hidden. No key is sent over the network.'
    $ApiKey = Read-Host 'Paste the API key and press Enter' -AsSecureString
}
if ($ApiKey.Length -lt 20 -or $ApiKey.Length -gt 512) { throw 'Unexpected API key length. Nothing was saved.' }

New-Item -ItemType Directory -Path $credentialRoot -Force | Out-Null
$directory = Get-Item -LiteralPath $credentialRoot -Force
if (($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Credential directories must not be links.' }

# Private directory plus Windows current-user encryption; never plaintext, repo files, or environment-variable output.
$ownerSid = [Security.Principal.WindowsIdentity]::GetCurrent().User
$acl = $directory.GetAccessControl([Security.AccessControl.AccessControlSections]::Access)
$acl.SetAccessRuleProtection($true, $false)
foreach ($existingRule in $acl.GetAccessRules($true, $false, [Security.Principal.SecurityIdentifier])) {
    $acl.RemoveAccessRuleSpecific($existingRule)
}
$rule = New-Object Security.AccessControl.FileSystemAccessRule(
    $ownerSid, 'FullControl', 'ContainerInherit,ObjectInherit', 'None', 'Allow'
)
$acl.AddAccessRule($rule)
# Change only the DACL, never the audit SACL/owner; this needs no administrator privileges.
$directory.SetAccessControl($acl)

$temporaryPath = Join-Path $credentialRoot ('deepseek-' + [Guid]::NewGuid().ToString('N') + '.tmp')
try {
    $ApiKey | Export-Clixml -LiteralPath $temporaryPath -Encoding UTF8
    $roundTrip = Import-Clixml -LiteralPath $temporaryPath
    if ($roundTrip -isnot [Security.SecureString] -or $roundTrip.Length -ne $ApiKey.Length) {
        throw 'Credential encryption verification failed.'
    }
    if (Test-Path -LiteralPath $credentialPath) {
        $existing = Get-Item -LiteralPath $credentialPath -Force
        if (($existing.Attributes -band [IO.FileAttributes]::ReparsePoint) -ne 0) { throw 'Credential files must not be links.' }
        [IO.File]::Replace($temporaryPath, $credentialPath, [System.Management.Automation.Language.NullString]::Value)
    } else {
        Move-Item -LiteralPath $temporaryPath -Destination $credentialPath
    }
    Write-Host 'Credential saved with Windows current-user encryption.'
    Write-Host ('Saved path: ' + $credentialPath)
    Write-Host 'No model call, server change or upload was performed. Return to Codex and say: key saved.'
} finally {
    if (Test-Path -LiteralPath $temporaryPath) { Remove-Item -LiteralPath $temporaryPath -Force }
}
