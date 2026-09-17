[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$manifestScript = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\New-PilotReleaseManifest.ps1'))
$pwshPath = (Get-Process -Id $PID).Path
$sourceCommit = 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
$testRoot = Join-Path ([IO.Path]::GetTempPath()) ("pilot-release-manifest-test-" + [Guid]::NewGuid().ToString('N'))
$cases = [Collections.Generic.List[object]]::new()
$failures = [Collections.Generic.List[string]]::new()

function Write-Utf8Json {
    param([Parameter(Mandatory)][string]$Path, [Parameter(Mandatory)]$Value)

    [IO.File]::WriteAllText(
        $Path,
        (($Value | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
        [Text.UTF8Encoding]::new($false)
    )
}

function New-TestArchive {
    param(
        [Parameter(Mandatory)][string]$Path,
        [Parameter(Mandatory)][Collections.IDictionary]$Entries
    )

    Add-Type -AssemblyName System.IO.Compression
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
        try {
            foreach ($entryName in $Entries.Keys) {
                $entry = $archive.CreateEntry([string]$entryName)
                $entryStream = $entry.Open()
                try {
                    $bytes = [Text.Encoding]::UTF8.GetBytes([string]$Entries[$entryName])
                    $entryStream.Write($bytes, 0, $bytes.Length)
                } finally {
                    $entryStream.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-Sha256Text {
    param([Parameter(Mandatory)][string]$Value)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($Value)))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function New-Plan {
    param(
        [Parameter(Mandatory)][string]$Lane,
        [Parameter(Mandatory)][bool]$RequiresWeb,
        [Parameter(Mandatory)][bool]$RequiresCore,
        [bool]$RequiresDatabaseBackup = $false,
        [bool]$RequiresMigrationGate = $false,
        [bool]$RequiresFullRegression = $false
    )

    return [ordered]@{
        schemaVersion = 1
        policyVersion = 3
        classifierSha256 = ('b' * 64)
        status = 'PASS'
        lane = $Lane
        baseRef = 'base'
        headRef = 'head'
        changedPaths = @('fixture')
        reasons = @('test fixture')
        requiresWeb = $RequiresWeb
        requiresCore = $RequiresCore
        requiresDatabaseBackup = $RequiresDatabaseBackup
        requiresMigrationGate = $RequiresMigrationGate
        requiresFullRegression = $RequiresFullRegression
    }
}

function Invoke-Manifest {
    param([Parameter(Mandatory)][string]$ArtifactRoot)

    $planPath = Join-Path $ArtifactRoot 'deployment-plan.json'
    $output = & $pwshPath -NoLogo -NoProfile -NonInteractive -File $manifestScript `
        -PlanPath $planPath `
        -SourceCommit $sourceCommit `
        -ArtifactRoot $ArtifactRoot 2>&1
    return [pscustomobject]@{
        ExitCode = $LASTEXITCODE
        Output = (@($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    }
}

function Add-Case {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][bool]$Passed,
        [Parameter(Mandatory)][string]$Detail
    )

    $cases.Add([ordered]@{ name = $Name; status = if ($Passed) { 'PASS' } else { 'FAIL' }; detail = $Detail })
    if (-not $Passed) { $failures.Add("${Name}: $Detail") }
}

New-Item -ItemType Directory -Path $testRoot | Out-Null
try {
    $webRoot = Join-Path $testRoot 'web-valid'
    New-Item -ItemType Directory -Path $webRoot | Out-Null
    Write-Utf8Json (Join-Path $webRoot 'deployment-plan.json') (New-Plan -Lane WEB_ONLY -RequiresWeb $true -RequiresCore $false)
    New-TestArchive (Join-Path $webRoot 'web.zip') ([ordered]@{
        'index.html' = '<!doctype html><title>pilot</title>'
        'assets/app.js' = 'console.log("pilot")'
    })
    $webResult = Invoke-Manifest $webRoot
    $webDocument = if ($webResult.ExitCode -eq 0) { $webResult.Output | ConvertFrom-Json } else { $null }
    $webIndex = '<!doctype html><title>pilot</title>'
    $webScript = 'console.log("pilot")'
    $expectedWebTreeInput = @(
        "assets/app.js:$([Text.Encoding]::UTF8.GetByteCount($webScript)):$(Get-Sha256Text $webScript)",
        "index.html:$([Text.Encoding]::UTF8.GetByteCount($webIndex)):$(Get-Sha256Text $webIndex)"
    ) -join "`n"
    Add-Case 'web-only-manifest' (
        $webResult.ExitCode -eq 0 -and
        $webDocument.status -ceq 'PASS' -and
        $webDocument.lane -ceq 'WEB_ONLY' -and
        $webDocument.sourceCommit -ceq $sourceCommit -and
        @($webDocument.artifacts).Count -eq 1 -and
        $webDocument.artifacts[0].file -ceq 'web.zip' -and
        $webDocument.web.entryCount -eq 2 -and
        $webDocument.web.fileCount -eq 2 -and
        $webDocument.web.treeSha256 -ceq (Get-Sha256Text $expectedWebTreeInput) -and
        $webDocument.web.treeFingerprintAlgorithm -ceq 'sha256(sorted-path-colon-size-colon-content-sha256-v1)' -and
        $webDocument.policy.version -eq 3 -and
        $webDocument.policy.classifierSha256 -ceq ('b' * 64) -and
        $null -eq $webDocument.core -and
        $webDocument.safeguards.deployRequiresEnvironmentApproval
    ) "exit=$($webResult.ExitCode), output=$($webResult.Output)"

    $unsafeWebRoot = Join-Path $testRoot 'web-unsafe'
    New-Item -ItemType Directory -Path $unsafeWebRoot | Out-Null
    Write-Utf8Json (Join-Path $unsafeWebRoot 'deployment-plan.json') (New-Plan -Lane WEB_ONLY -RequiresWeb $true -RequiresCore $false)
    New-TestArchive (Join-Path $unsafeWebRoot 'web.zip') ([ordered]@{
        'index.html' = 'safe root'
        '../escape.txt' = 'unsafe'
    })
    $unsafeWebResult = Invoke-Manifest $unsafeWebRoot
    Add-Case 'unsafe-web-entry-blocked' (
        $unsafeWebResult.ExitCode -ne 0 -and $unsafeWebResult.Output -match 'Unsafe Web bundle entry'
    ) "exit=$($unsafeWebResult.ExitCode), output=$($unsafeWebResult.Output)"

    $backendRoot = Join-Path $testRoot 'backend-valid'
    New-Item -ItemType Directory -Path $backendRoot | Out-Null
    Write-Utf8Json (Join-Path $backendRoot 'deployment-plan.json') (
        New-Plan -Lane BACKEND -RequiresWeb $false -RequiresCore $true -RequiresFullRegression $true
    )
    $v1Name = 'BOOT-INF/classes/db/migration/V1__base.sql'
    $v10Name = 'BOOT-INF/classes/db/migration/V10__latest.sql'
    $v1Content = 'select 1;'
    $v10Content = 'select 10;'
    New-TestArchive (Join-Path $backendRoot 'core-api.jar') ([ordered]@{
        $v1Name = $v1Content
        $v10Name = $v10Content
        'BOOT-INF/classes/application.yml' = 'spring: {}'
    })
    [IO.File]::WriteAllText((Join-Path $backendRoot 'run-migration-once.sh'), "#!/usr/bin/env bash`n", [Text.UTF8Encoding]::new($false))
    $backendResult = Invoke-Manifest $backendRoot
    $backendDocument = if ($backendResult.ExitCode -eq 0) { $backendResult.Output | ConvertFrom-Json } else { $null }
    $expectedFingerprintInput = @(
        "${v10Name}:$(Get-Sha256Text $v10Content)",
        "${v1Name}:$(Get-Sha256Text $v1Content)"
    ) -join "`n"
    $expectedFingerprint = Get-Sha256Text $expectedFingerprintInput
    Add-Case 'backend-manifest-and-migration-identity' (
        $backendResult.ExitCode -eq 0 -and
        $backendDocument.lane -ceq 'BACKEND' -and
        @($backendDocument.artifacts).Count -eq 2 -and
        $backendDocument.migration.version -ceq '10' -and
        $backendDocument.migration.migrationCount -eq 2 -and
        $backendDocument.migration.fingerprint -ceq $expectedFingerprint -and
        $backendDocument.migration.algorithm -ceq 'sha256(sorted-entry-name-colon-content-sha256-v1)'
    ) "exit=$($backendResult.ExitCode), output=$($backendResult.Output)"

    $mixedRoot = Join-Path $testRoot 'backend-mixed'
    New-Item -ItemType Directory -Path $mixedRoot | Out-Null
    Write-Utf8Json (Join-Path $mixedRoot 'deployment-plan.json') (
        New-Plan -Lane BACKEND -RequiresWeb $true -RequiresCore $true -RequiresFullRegression $true
    )
    New-TestArchive (Join-Path $mixedRoot 'web.zip') ([ordered]@{ 'index.html' = 'mixed' })
    New-TestArchive (Join-Path $mixedRoot 'core-api.jar') ([ordered]@{ $v1Name = $v1Content })
    $mixedResult = Invoke-Manifest $mixedRoot
    Add-Case 'mixed-backend-plan-blocked' (
        $mixedResult.ExitCode -ne 0 -and $mixedResult.Output -match 'cannot include Web changes'
    ) "exit=$($mixedResult.ExitCode), output=$($mixedResult.Output)"

    $unknownRoot = Join-Path $testRoot 'unknown-artifact'
    New-Item -ItemType Directory -Path $unknownRoot | Out-Null
    Write-Utf8Json (Join-Path $unknownRoot 'deployment-plan.json') (New-Plan -Lane WEB_ONLY -RequiresWeb $true -RequiresCore $false)
    New-TestArchive (Join-Path $unknownRoot 'web.zip') ([ordered]@{ 'index.html' = 'safe' })
    [IO.File]::WriteAllText((Join-Path $unknownRoot 'unexpected.bin'), 'unexpected', [Text.UTF8Encoding]::new($false))
    $unknownResult = Invoke-Manifest $unknownRoot
    Add-Case 'unknown-artifact-blocked' (
        $unknownResult.ExitCode -ne 0 -and $unknownResult.Output -match 'unrecognized files'
    ) "exit=$($unknownResult.ExitCode), output=$($unknownResult.Output)"
} finally {
    $canonicalTestRoot = [IO.Path]::GetFullPath($testRoot)
    $canonicalTempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    if ($canonicalTestRoot.StartsWith($canonicalTempRoot, [StringComparison]::OrdinalIgnoreCase) -and
        (Split-Path -Leaf $canonicalTestRoot).StartsWith('pilot-release-manifest-test-', [StringComparison]::Ordinal)) {
        Remove-Item -LiteralPath $canonicalTestRoot -Recurse -Force
    }
}

$result = [ordered]@{
    schemaVersion = 1
    status = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    summary = [ordered]@{
        total = $cases.Count
        passed = @($cases | Where-Object { $_.status -ceq 'PASS' }).Count
        failed = $failures.Count
    }
    cases = [object[]]$cases
    failures = [string[]]$failures
}

Write-Output ($result | ConvertTo-Json -Depth 8)
if ($failures.Count -eq 0) { exit 0 }
exit 1
