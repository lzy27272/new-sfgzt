[CmdletBinding()]
param(
    [Parameter(Mandatory)]
    [string]$PlanPath,
    [Parameter(Mandatory)]
    [ValidatePattern('^[0-9a-fA-F]{40}$')]
    [string]$SourceCommit,
    [Parameter(Mandatory)]
    [string]$ArtifactRoot,
    [string]$OutputPath = '',
    [switch]$RequireFinanceOcr,
    [string]$PythonCommand = ''
)

Set-StrictMode -Version 3.0
$ErrorActionPreference = 'Stop'

function Get-Sha256Hex {
    param([Parameter(Mandatory)][byte[]]$Bytes)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Bytes))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-StreamSha256Hex {
    param([Parameter(Mandatory)][System.IO.Stream]$Stream)

    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        return ([BitConverter]::ToString($sha.ComputeHash($Stream))).Replace('-', '').ToLowerInvariant()
    } finally {
        $sha.Dispose()
    }
}

function Get-FileDescriptor {
    param(
        [Parameter(Mandatory)][string]$Name,
        [Parameter(Mandatory)][string]$Path
    )

    $item = Get-Item -LiteralPath $Path
    return [ordered]@{
        name = $Name
        file = $item.Name
        sha256 = (Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        sizeBytes = [long]$item.Length
    }
}

function Read-WebBundle {
    param([Parameter(Mandatory)][string]$Path)

    Add-Type -AssemblyName System.IO.Compression
    $archive = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $seen = @{}
        $indexSha = $null
        $totalSize = [long]0
        $files = New-Object 'System.Collections.Generic.List[object]'
        if ($archive.Entries.Count -eq 0 -or $archive.Entries.Count -gt 20000) {
            throw 'Web bundle has an invalid entry count.'
        }
        foreach ($entry in $archive.Entries) {
            $name = $entry.FullName
            if ([string]::IsNullOrWhiteSpace($name) -or
                $name.StartsWith('/') -or
                $name.Contains('\') -or
                $name -match '(^|/)\.\.(/|$)' -or
                $name -match '^[A-Za-z]:') {
                throw "Unsafe Web bundle entry: $name"
            }
            $key = $name.ToLowerInvariant()
            if ($seen.ContainsKey($key)) { throw "Duplicate Web bundle entry: $name" }
            $seen[$key] = $true

            $isDirectory = $name.EndsWith('/', [StringComparison]::Ordinal)
            $unixType = (($entry.ExternalAttributes -shr 16) -band 0xF000)
            if ($isDirectory) {
                if ($unixType -notin @(0, 0x4000)) { throw "Unsafe Web bundle directory type: $name" }
                continue
            }
            if ($unixType -notin @(0, 0x8000)) { throw "Unsafe Web bundle file type: $name" }
            if ($entry.Length -lt 0 -or $entry.Length -gt 1073741824) {
                throw "Web bundle entry has an invalid size: $name"
            }
            $totalSize += [long]$entry.Length
            if ($totalSize -gt 1073741824) { throw 'Web bundle exceeds the uncompressed size limit.' }

            $stream = $entry.Open()
            try {
                $entrySha = Get-StreamSha256Hex $stream
            } finally {
                $stream.Dispose()
            }
            $files.Add([pscustomobject]@{
                Name = $name
                Size = [long]$entry.Length
                Sha256 = $entrySha
            })
            if ($name -ceq 'index.html') { $indexSha = $entrySha }
        }
        if ($null -eq $indexSha) { throw 'Web bundle must contain index.html at its root.' }

        $fileNames = [string[]]@($files | ForEach-Object { $_.Name })
        [Array]::Sort($fileNames, [StringComparer]::Ordinal)
        $filesByName = @{}
        foreach ($file in $files) { $filesByName[$file.Name] = $file }
        $fingerprintInput = ($fileNames | ForEach-Object {
            $file = $filesByName[$_]
            "$($file.Name):$($file.Size):$($file.Sha256)"
        }) -join "`n"
        return [ordered]@{
            indexSha256 = $indexSha
            entryCount = $archive.Entries.Count
            fileCount = $files.Count
            treeSha256 = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($fingerprintInput))
            treeFingerprintAlgorithm = 'sha256(sorted-path-colon-size-colon-content-sha256-v1)'
        }
    } finally {
        $archive.Dispose()
    }
}

function Read-MigrationState {
    param([Parameter(Mandatory)][string]$JarPath)

    Add-Type -AssemblyName System.IO.Compression
    $archive = [IO.Compression.ZipFile]::OpenRead($JarPath)
    try {
        $candidatePattern = '^BOOT-INF/classes/db/migration/V.*[.]sql$'
        $validPattern = '^BOOT-INF/classes/db/migration/V([1-9][0-9]*)__[^/]+[.]sql$'
        $candidates = @($archive.Entries | Where-Object { $_.FullName -match $candidatePattern })
        if ($candidates.Count -eq 0) { throw 'Core API JAR contains no versioned Flyway migrations.' }
        $migrations = foreach ($entry in $candidates) {
            $match = [regex]::Match($entry.FullName, $validPattern)
            if (-not $match.Success) { throw "Invalid Flyway migration entry: $($entry.FullName)" }
            $stream = $entry.Open()
            try {
                $memory = New-Object IO.MemoryStream
                try {
                    $stream.CopyTo($memory)
                    [pscustomobject]@{
                        Version = [int64]$match.Groups[1].Value
                        Name = $entry.FullName
                        Sha256 = Get-Sha256Hex $memory.ToArray()
                    }
                } finally {
                    $memory.Dispose()
                }
            } finally {
                $stream.Dispose()
            }
        }
        $duplicates = @($migrations | Group-Object Version | Where-Object Count -gt 1)
        if ($duplicates.Count -gt 0) {
            throw "Core API JAR contains duplicate Flyway versions: $($duplicates.Name -join ', ')"
        }
        $migrationNames = [string[]]@($migrations | ForEach-Object { $_.Name })
        [Array]::Sort($migrationNames, [StringComparer]::Ordinal)
        $migrationByName = @{}
        foreach ($migration in $migrations) {
            $migrationByName[$migration.Name] = $migration
        }
        $ordered = @($migrationNames | ForEach-Object { $migrationByName[$_] })
        $fingerprintInput = ($ordered | ForEach-Object { "$($_.Name):$($_.Sha256)" }) -join "`n"
        return [ordered]@{
            version = [string](($migrations | Measure-Object Version -Maximum).Maximum)
            fingerprint = Get-Sha256Hex ([Text.Encoding]::UTF8.GetBytes($fingerprintInput))
            migrationCount = $ordered.Count
            algorithm = 'sha256(sorted-entry-name-colon-content-sha256-v1)'
        }
    } finally {
        $archive.Dispose()
    }
}

$planFile = (Resolve-Path -LiteralPath $PlanPath).Path
$artifactDirectory = (Resolve-Path -LiteralPath $ArtifactRoot).Path
if ([string]::IsNullOrWhiteSpace($OutputPath)) {
    $OutputPath = Join-Path $artifactDirectory 'release-manifest.json'
}
$outputFullPath = [IO.Path]::GetFullPath($OutputPath)
$artifactPrefix = $artifactDirectory.TrimEnd([char[]]@('\', '/')) + [IO.Path]::DirectorySeparatorChar
if (-not $outputFullPath.StartsWith($artifactPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'OutputPath must stay inside ArtifactRoot.'
}

$planText = Get-Content -LiteralPath $planFile -Raw -Encoding UTF8
$plan = $planText | ConvertFrom-Json
if ($plan.schemaVersion -ne 1 -or $plan.policyVersion -ne 3 -or
    [string]$plan.classifierSha256 -notmatch '^[0-9a-f]{64}$' -or $plan.status -ne 'PASS') {
    throw 'Deployment plan is not a supported PASS result.'
}
$lane = [string]$plan.lane
if ($lane -notin @('WEB_ONLY', 'BACKEND', 'FULL')) { throw "Unsupported deployment lane: $lane" }

$requiresWeb = [bool]$plan.requiresWeb
$requiresCore = [bool]$plan.requiresCore
if ($lane -eq 'WEB_ONLY' -and (-not $requiresWeb -or $requiresCore)) {
    throw 'WEB_ONLY plan has inconsistent component requirements.'
}
if ($lane -eq 'BACKEND' -and -not $requiresCore) {
    throw 'BACKEND plan must require the Core API component.'
}
if ($lane -eq 'BACKEND' -and $requiresWeb) {
    throw 'BACKEND fast lane cannot include Web changes; coordinated releases must use FULL.'
}
if ($lane -eq 'FULL' -and (-not [bool]$plan.requiresFullRegression -or -not [bool]$plan.requiresDatabaseBackup)) {
    throw 'FULL plan must preserve full regression and database backup gates.'
}

$recognized = @('web.zip', 'core-api.jar', 'run-migration-once.sh', 'finance-ocr.zip')
$unexpected = @(Get-ChildItem -LiteralPath $artifactDirectory -File | Where-Object {
    $_.FullName -ne $outputFullPath -and $_.FullName -ne $planFile -and $_.Name -notin $recognized
})
if ($unexpected.Count -gt 0) {
    throw "ArtifactRoot contains unrecognized files: $($unexpected.Name -join ', ')"
}

$artifacts = New-Object 'System.Collections.Generic.List[object]'
$web = $null
$core = $null
$migration = $null
$financeOcr = $null
if ($requiresWeb) {
    $webPath = Join-Path $artifactDirectory 'web.zip'
    if (-not (Test-Path -LiteralPath $webPath -PathType Leaf)) { throw 'Required Web artifact is missing: web.zip' }
    $descriptor = Get-FileDescriptor 'web' $webPath
    [void]$artifacts.Add($descriptor)
    $bundle = Read-WebBundle $webPath
    $web = [ordered]@{
        bundleSha256 = $descriptor.sha256
        indexSha256 = $bundle.indexSha256
        entryCount = $bundle.entryCount
        fileCount = $bundle.fileCount
        treeSha256 = $bundle.treeSha256
        treeFingerprintAlgorithm = $bundle.treeFingerprintAlgorithm
    }
}
if ($requiresCore) {
    $corePath = Join-Path $artifactDirectory 'core-api.jar'
    if (-not (Test-Path -LiteralPath $corePath -PathType Leaf)) { throw 'Required Core API artifact is missing: core-api.jar' }
    $descriptor = Get-FileDescriptor 'core' $corePath
    [void]$artifacts.Add($descriptor)
    $core = [ordered]@{ jarSha256 = $descriptor.sha256 }
    $migration = Read-MigrationState $corePath
    $migrationRunner = Join-Path $artifactDirectory 'run-migration-once.sh'
    if (Test-Path -LiteralPath $migrationRunner -PathType Leaf) {
        [void]$artifacts.Add((Get-FileDescriptor 'migration-runner' $migrationRunner))
    }
}

$ocrPath = Join-Path $artifactDirectory 'finance-ocr.zip'
if ($RequireFinanceOcr -and -not $requiresCore) { throw 'Finance OCR requires the Core API component.' }
if ($RequireFinanceOcr -and -not (Test-Path -LiteralPath $ocrPath -PathType Leaf)) {
    throw 'Required Finance OCR artifact is missing: finance-ocr.zip'
}
if (Test-Path -LiteralPath $ocrPath -PathType Leaf) {
    if (-not $requiresCore) { throw 'Finance OCR cannot be included without the Core API component.' }
    if ([string]::IsNullOrWhiteSpace($PythonCommand)) {
        foreach ($candidate in @('python3', 'python')) {
            $python = Get-Command $candidate -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
            if ($null -ne $python) { $PythonCommand = $python.Source; break }
        }
    }
    if ([string]::IsNullOrWhiteSpace($PythonCommand)) { throw 'Python 3 is required to verify the offline Finance OCR artifact.' }
    $validator = Join-Path $PSScriptRoot 'finance_ocr_bundle.py'
    $validation = @(& $PythonCommand $validator verify --bundle $ocrPath --source-commit $SourceCommit.ToLowerInvariant() 2>&1)
    if ($LASTEXITCODE -ne 0) { throw "Finance OCR artifact validation failed: $($validation -join ' ')" }
    $ocr = ($validation -join [Environment]::NewLine) | ConvertFrom-Json
    if ($ocr.status -cne 'PASS' -or $ocr.sourceCommit -cne $SourceCommit.ToLowerInvariant()) { throw 'Finance OCR verification identity differs.' }
    $descriptor = Get-FileDescriptor 'finance-ocr' $ocrPath
    if ($descriptor.sha256 -cne $ocr.bundleSha256) { throw 'Finance OCR bundle changed during verification.' }
    [void]$artifacts.Add($descriptor)
    $financeOcr = [ordered]@{
        sourceCommit = [string]$ocr.sourceCommit
        bundleSha256 = [string]$ocr.bundleSha256
        bundleManifestSha256 = [string]$ocr.bundleManifestSha256
        requirementsSha256 = [string]$ocr.requirementsSha256
        pythonMajorMinor = [string]$ocr.pythonMajorMinor
        platform = [string]$ocr.platform
    }
}

$manifest = [ordered]@{
    schemaVersion = 1
    status = 'PASS'
    sourceCommit = $SourceCommit.ToLowerInvariant()
    lane = $lane
    policy = [ordered]@{
        version = [int]$plan.policyVersion
        classifierSha256 = [string]$plan.classifierSha256
    }
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
    planSha256 = (Get-FileHash -LiteralPath $planFile -Algorithm SHA256).Hash.ToLowerInvariant()
    safeguards = [ordered]@{
        deployRequiresEnvironmentApproval = $true
        fullLaneAutoDeployAllowed = $false
        sourceCommitBound = $true
        hashesRequired = $true
    }
    artifacts = $artifacts.ToArray()
    web = $web
    core = $core
    migration = $migration
    financeOcr = $financeOcr
}

$temporaryPath = "$outputFullPath.$([Guid]::NewGuid().ToString('N')).tmp"
[IO.File]::WriteAllText(
    $temporaryPath,
    (($manifest | ConvertTo-Json -Depth 8) + [Environment]::NewLine),
    [Text.UTF8Encoding]::new($false)
)
Move-Item -LiteralPath $temporaryPath -Destination $outputFullPath -Force
Get-Content -LiteralPath $outputFullPath -Raw -Encoding UTF8
