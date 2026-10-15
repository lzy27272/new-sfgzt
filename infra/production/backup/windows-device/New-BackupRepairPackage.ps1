#requires -Version 5.1
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^SHA256:[A-Za-z0-9+/]{43}$')][string]$DeviceKeyFingerprint,
    [Parameter(Mandatory=$true)][ValidatePattern('^SHA256:[A-Za-z0-9+/]{43}$')][string]$ServerHostKeyFingerprint,
    [Parameter(Mandatory=$true)][string]$OutputDirectory
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$outputRoot = [IO.Path]::GetFullPath($OutputDirectory).TrimEnd('\')
if (-not (Test-Path -LiteralPath $outputRoot)) { New-Item -Path $outputRoot -ItemType Directory | Out-Null }
$stage = Join-Path $outputRoot ('.backup-repair-package-' + [guid]::NewGuid().ToString('N'))
New-Item -Path $stage -ItemType Directory | Out-Null
try {
    $records = @()
    foreach ($name in @('03-Repair-BackupPull.cmd','Repair-BackupPull.ps1','BackupPull.Common.ps1',
        'BackupSystem.Common.ps1','Invoke-BackupPull.ps1','REPAIR-README.txt')) {
        $source = Join-Path $PSScriptRoot $name
        $destination = Join-Path $stage $name
        $content = [IO.File]::ReadAllText($source,[Text.Encoding]::UTF8) -replace "`r?`n", "`r`n"
        if ($name.EndsWith('.cmd')) {
            if (@($content.ToCharArray() | Where-Object { [int]$_ -gt 127 }).Count -gt 0) { throw 'Launcher must be ASCII.' }
            [IO.File]::WriteAllText($destination,$content,[Text.Encoding]::ASCII)
        } else { [IO.File]::WriteAllText($destination,$content,[Text.UTF8Encoding]::new($true)) }
        $records += [pscustomobject]@{ name=$name; bytes=(Get-Item -LiteralPath $destination).Length
            sha256=(Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash.ToLowerInvariant() }
    }
    $manifest = [ordered]@{ kind='SFG_BACKUP_REPAIR_PACKAGE_V1'; schemaVersion=1
        createdAt=[DateTime]::UtcNow.ToString('o'); deviceKeyFingerprint=$DeviceKeyFingerprint
        serverHostKeyFingerprint=$ServerHostKeyFingerprint; files=$records }
    [IO.File]::WriteAllText((Join-Path $stage 'repair-package-manifest.json'),
        ($manifest | ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($true))
    $zipPath = Join-Path $outputRoot ('SFG-Backup-Repair-' + [DateTime]::Now.ToString('yyyyMMdd-HHmmss') + '.zip')
    if (Test-Path -LiteralPath $zipPath) { throw 'Refusing to overwrite an existing repair package.' }
    Compress-Archive -LiteralPath @((Get-ChildItem -LiteralPath $stage -File).FullName) -DestinationPath $zipPath
    [pscustomobject]@{ path=$zipPath; bytes=(Get-Item -LiteralPath $zipPath).Length
        sha256=(Get-FileHash -LiteralPath $zipPath -Algorithm SHA256).Hash }
} finally {
    $resolved = [IO.Path]::GetFullPath($stage)
    if ([IO.Path]::GetDirectoryName($resolved) -cne $outputRoot -or
        [IO.Path]::GetFileName($resolved) -notmatch '^\.backup-repair-package-[0-9a-f]{32}$') { throw 'Unsafe staging cleanup path.' }
    Remove-Item -LiteralPath $resolved -Recurse -Force
}
