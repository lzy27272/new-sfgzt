[CmdletBinding()]
param(
    [string]$ReleaseVersion = 'TECH-V0.2-RC',
    [string]$OutputRoot,
    [string]$BuildTimestamp = '2026-07-18T00:00:00Z',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path

function Mount-AsciiWorkspace([string]$path) {
    if ($path -notmatch '[^\x00-\x7F]') {
        return [ordered]@{ root = $path; drive = $null; junction = $null }
    }
    $junction = Join-Path ([System.IO.Path]::GetTempPath()) ("hotel-ai-os-release-" + [Guid]::NewGuid().ToString('N'))
    try {
        New-Item -ItemType Junction -Path $junction -Target $path -ErrorAction Stop | Out-Null
        return [ordered]@{ root = $junction; drive = $null; junction = $junction }
    } catch {
        if (Test-Path -LiteralPath $junction) {
            Remove-Item -LiteralPath $junction -Force
        }
    }
    $subst = Join-Path $env:SystemRoot 'System32\subst.exe'
    foreach ($letter in @('R', 'Q', 'P', 'O')) {
        if (Test-Path -LiteralPath "${letter}:\") { continue }
        & $subst "${letter}:" $path
        if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath "${letter}:\")) {
            return [ordered]@{ root = "${letter}:\"; drive = "${letter}:"; junction = $null }
        }
    }
    throw 'No free drive letter was available for the ASCII build-path mapping.'
}

function Dismount-AsciiWorkspace($mapping) {
    if ($mapping.junction) {
        $tempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath()).TrimEnd('\') + '\'
        $junctionPath = [System.IO.Path]::GetFullPath($mapping.junction)
        if (-not $junctionPath.StartsWith($tempRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to remove junction outside the temporary directory: $junctionPath"
        }
        [System.IO.Directory]::Delete($junctionPath, $false)
    }
    if ($mapping.drive) {
        & (Join-Path $env:SystemRoot 'System32\subst.exe') $mapping.drive '/D'
        if ($LASTEXITCODE -ne 0) { Write-Warning "Failed to remove build-path mapping $($mapping.drive)" }
    }
}

$workspaceMapping = Mount-AsciiWorkspace $repoRoot
$buildRepoRoot = $workspaceMapping.root
$apiDir = Join-Path $buildRepoRoot 'apps\core-api'
$webDir = Join-Path $buildRepoRoot 'apps\web'
$migrationDir = Join-Path $buildRepoRoot 'database\migrations'
$openApiFile = Join-Path $buildRepoRoot 'docs\openapi.yaml'
$apiDocFile = Join-Path $buildRepoRoot 'docs\API.md'

if (-not $OutputRoot) {
    $OutputRoot = Join-Path $repoRoot ".uat-runtime\release-artifacts\$ReleaseVersion"
}
$outputDir = [System.IO.Path]::GetFullPath($OutputRoot)
$allowedRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot '.uat-runtime\release-artifacts'))
if (-not $outputDir.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "OutputRoot must stay under $allowedRoot"
}

function Resolve-Executable([string]$name, [string]$fallbackRoot, [string]$filter) {
    $command = Get-Command $name -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $candidate = Get-ChildItem -LiteralPath $fallbackRoot -Filter $filter -Recurse -ErrorAction SilentlyContinue |
        Select-Object -First 1
    if (-not $candidate) { throw "Required executable was not found: $name" }
    return $candidate.FullName
}

function Get-RelativeUnixPath([string]$basePath, [string]$path) {
    $baseUri = [Uri]((Resolve-Path -LiteralPath $basePath).Path.TrimEnd('\') + '\')
    $pathUri = [Uri](Resolve-Path -LiteralPath $path).Path
    return [Uri]::UnescapeDataString($baseUri.MakeRelativeUri($pathUri).ToString())
}

function New-DeterministicZip([string]$sourceDirectory, [string]$destinationFile, [DateTimeOffset]$timestamp) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    if (Test-Path -LiteralPath $destinationFile) { Remove-Item -LiteralPath $destinationFile -Force }
    $stream = [System.IO.File]::Open($destinationFile, [System.IO.FileMode]::CreateNew)
    try {
        $archive = New-Object System.IO.Compression.ZipArchive(
            $stream,
            [System.IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            $files = Get-ChildItem -LiteralPath $sourceDirectory -File -Recurse |
                Sort-Object { Get-RelativeUnixPath $sourceDirectory $_.FullName }
            foreach ($file in $files) {
                $entryName = Get-RelativeUnixPath $sourceDirectory $file.FullName
                $entry = $archive.CreateEntry($entryName, [System.IO.Compression.CompressionLevel]::Optimal)
                $entry.LastWriteTime = $timestamp
                $input = [System.IO.File]::OpenRead($file.FullName)
                $output = $entry.Open()
                try { $input.CopyTo($output) } finally { $output.Dispose(); $input.Dispose() }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-ToolVersion([string]$executable, [string[]]$arguments) {
    $previousErrorActionPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $lines = & $executable @arguments 2>&1
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousErrorActionPreference
    }
    if ($exitCode -ne 0) { throw "Failed to query version: $executable" }
    return (($lines | Select-Object -First 1).ToString()).Trim()
}

function Get-GitState {
    $git = Get-Command git -ErrorAction SilentlyContinue
    $gitPath = if ($git) { $git.Source } else {
        Join-Path $env:USERPROFILE '.cache\codex-runtimes\codex-primary-runtime\dependencies\native\git\cmd\git.exe'
    }
    if (-not (Test-Path -LiteralPath $gitPath) -or
        -not (Test-Path -LiteralPath (Join-Path $repoRoot '.git')) -or
        -not (Test-Path -LiteralPath (Join-Path $repoRoot '.git\HEAD'))) {
        return [ordered]@{ commit = $null; dirty = $null }
    }
    $previousPreference = $ErrorActionPreference
    $ErrorActionPreference = 'Continue'
    try {
        $commitOutput = @(& $gitPath -C $repoRoot rev-parse --verify HEAD 2>&1)
        $commitExitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $previousPreference
    }
    if ($commitExitCode -ne 0) { return [ordered]@{ commit = $null; dirty = $null } }
    $commit = ($commitOutput | Select-Object -First 1).ToString().Trim()
    & $gitPath -C $repoRoot diff --quiet --ignore-submodules --
    $trackedDirty = $LASTEXITCODE -ne 0
    & $gitPath -C $repoRoot diff --cached --quiet --ignore-submodules --
    $stagedDirty = $LASTEXITCODE -ne 0
    $untracked = @(& $gitPath -C $repoRoot ls-files --others --exclude-standard)
    return [ordered]@{ commit = $commit; dirty = ($trackedDirty -or $stagedDirty -or $untracked.Count -gt 0) }
}

$timestamp = [DateTimeOffset]::Parse(
    $BuildTimestamp,
    [Globalization.CultureInfo]::InvariantCulture,
    [Globalization.DateTimeStyles]::AssumeUniversal
).ToUniversalTime()
if ($timestamp.Year -lt 1980) { throw 'BuildTimestamp must be 1980 or later for ZIP compatibility.' }
$sourceDateEpoch = $timestamp.ToUnixTimeSeconds().ToString()

$javaRoot = Join-Path $buildRepoRoot '.tooling\jdk'
$mavenRoot = Join-Path $buildRepoRoot '.tooling\maven'
$java = Resolve-Executable 'java' $javaRoot 'java.exe'
$maven = Resolve-Executable 'mvn' $mavenRoot 'mvn.cmd'
$pnpm = Resolve-Executable 'pnpm' $buildRepoRoot 'pnpm.cmd'
$nodeCommand = Get-Command node -ErrorAction SilentlyContinue
if ($nodeCommand) {
    $node = $nodeCommand.Source
} else {
    $node = [System.IO.Path]::GetFullPath((Join-Path (Split-Path -Parent $pnpm) '..\..\node\bin\node.exe'))
    if (-not (Test-Path -LiteralPath $node)) { throw 'Node.js was not found in PATH or beside the bundled pnpm launcher.' }
}
$javaHome = Split-Path -Parent (Split-Path -Parent $java)
$previousJavaHome = $env:JAVA_HOME
$previousSourceDateEpoch = $env:SOURCE_DATE_EPOCH
$previousPath = $env:Path

try {
    $env:JAVA_HOME = $javaHome
    $env:SOURCE_DATE_EPOCH = $sourceDateEpoch
    $env:Path = "$(Split-Path -Parent $node);$(Join-Path $javaHome 'bin');$env:Path"

    if (-not $SkipBuild) {
        Push-Location $apiDir
        try {
            & $maven "-Dmaven.repo.local=$(Join-Path $buildRepoRoot '.tooling\m2')" `
                "-Dproject.build.outputTimestamp=$($timestamp.ToString('yyyy-MM-ddTHH:mm:ssZ'))" `
                '-DskipTests' clean package
            if ($LASTEXITCODE -ne 0) { throw 'Backend release build failed.' }
        } finally { Pop-Location }

        Push-Location $webDir
        try {
            & $pnpm 'build:pilot'
            if ($LASTEXITCODE -ne 0) { throw 'Frontend release build failed.' }
        } finally { Pop-Location }
    }

    $jar = Get-ChildItem -LiteralPath (Join-Path $apiDir 'target') -Filter 'hotel-ai-os-core-api-*.jar' |
        Where-Object { $_.Name -notlike '*.original' } |
        Sort-Object Name |
        Select-Object -First 1
    if (-not $jar) { throw 'Backend JAR was not found.' }
    if (-not (Test-Path -LiteralPath (Join-Path $webDir 'dist\index.html'))) {
        throw 'Frontend dist/index.html was not found.'
    }
    $webScripts = @(Get-ChildItem -LiteralPath (Join-Path $webDir 'dist\assets') -Filter '*.js' -File -ErrorAction SilentlyContinue)
    $bearerHeaderPresent = $webScripts.Count -gt 0 -and [bool](
        $webScripts | Select-String -SimpleMatch 'X-Hotel-AI-Authorization' -Quiet
    )
    if (-not $bearerHeaderPresent) {
        throw 'Frontend release does not enforce the Pilot bearer-login contract. Run pnpm build:pilot in apps\web.'
    }
    if (-not (Test-Path -LiteralPath $openApiFile)) { throw 'docs/openapi.yaml was not found.' }

    New-Item -ItemType Directory -Force -Path $allowedRoot | Out-Null
    if (Test-Path -LiteralPath $outputDir) {
        $resolvedOutput = (Resolve-Path -LiteralPath $outputDir).Path
        if (-not $resolvedOutput.StartsWith($allowedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
            throw "Refusing to replace output outside $allowedRoot"
        }
        Remove-Item -LiteralPath $resolvedOutput -Recurse -Force
    }
    New-Item -ItemType Directory -Force -Path $outputDir | Out-Null

    $apiArtifact = Join-Path $outputDir "hotel-ai-os-core-api-$ReleaseVersion.jar"
    $webArtifact = Join-Path $outputDir "hotel-ai-os-web-$ReleaseVersion.zip"
    $migrationArtifact = Join-Path $outputDir "hotel-ai-os-db-v13-$ReleaseVersion.zip"
    $openApiArtifact = Join-Path $outputDir "hotel-ai-os-openapi-$ReleaseVersion.yaml"
    $apiDocArtifact = Join-Path $outputDir "hotel-ai-os-api-$ReleaseVersion.md"

    Copy-Item -LiteralPath $jar.FullName -Destination $apiArtifact
    New-DeterministicZip (Join-Path $webDir 'dist') $webArtifact $timestamp
    New-DeterministicZip $migrationDir $migrationArtifact $timestamp
    Copy-Item -LiteralPath $openApiFile -Destination $openApiArtifact
    Copy-Item -LiteralPath $apiDocFile -Destination $apiDocArtifact

    $migrationFiles = Get-ChildItem -LiteralPath $migrationDir -Filter 'V*__*.sql' -File |
        Sort-Object { [int]([regex]::Match($_.Name, '^V(\d+)__').Groups[1].Value) }
    if ($migrationFiles.Count -ne 13 -or $migrationFiles[-1].Name -notlike 'V13__*') {
        throw "Expected exactly V1-V13 migrations; found $($migrationFiles.Count), latest $($migrationFiles[-1].Name)."
    }

    $artifactFiles = @($apiArtifact, $webArtifact, $migrationArtifact, $openApiArtifact, $apiDocArtifact)
    $artifacts = foreach ($artifact in ($artifactFiles | Sort-Object { Split-Path -Leaf $_ })) {
        $item = Get-Item -LiteralPath $artifact
        $hash = (Get-FileHash -LiteralPath $artifact -Algorithm SHA256).Hash.ToLowerInvariant()
        [ordered]@{ file = $item.Name; sizeBytes = $item.Length; sha256 = $hash }
    }
    $payloadFingerprintInput = ($artifacts | ForEach-Object { "$($_.file):$($_.sha256)" }) -join "`n"
    $fingerprintBytes = [Text.Encoding]::UTF8.GetBytes($payloadFingerprintInput)
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $payloadFingerprint = ([BitConverter]::ToString($sha.ComputeHash($fingerprintBytes))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }

    $openApiVersionLine = Select-String -LiteralPath $openApiFile -Pattern '^\s*version:\s*(.+?)\s*$' |
        Select-Object -First 1
    $openApiVersion = if ($openApiVersionLine) { $openApiVersionLine.Matches[0].Groups[1].Value.Trim("'`"") } else { $null }
    $gitState = Get-GitState
    $manifest = [ordered]@{
        schemaVersion = 1
        releaseVersion = $ReleaseVersion
        createdAt = (Get-Date).ToUniversalTime().ToString('o')
        reproducibleBuild = [ordered]@{
            buildTimestamp = $timestamp.ToString('o')
            sourceDateEpoch = $sourceDateEpoch
            payloadFingerprintSha256 = $payloadFingerprint
        }
        source = $gitState
        versions = [ordered]@{
            database = 'DB-V13'
            apiBase = '/api/v1'
            openApi = $openApiVersion
            backend = '0.1.0-SNAPSHOT'
            frontend = '0.2.0'
        }
        tools = [ordered]@{
            java = Get-ToolVersion $java @('-version')
            maven = Get-ToolVersion $maven @('-version')
            node = Get-ToolVersion $node @('--version')
            pnpm = Get-ToolVersion $pnpm @('--version')
        }
        migrations = @($migrationFiles | ForEach-Object {
            [ordered]@{
                file = $_.Name
                sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            }
        })
        artifacts = @($artifacts)
    }
    $manifestFile = Join-Path $outputDir 'manifest.json'
    $manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestFile -Encoding UTF8

    $sumLines = $artifacts | ForEach-Object { "$($_.sha256)  $($_.file)" }
    $sumLines | Set-Content -LiteralPath (Join-Path $outputDir 'SHA256SUMS.txt') -Encoding ASCII

    Write-Host "Release artifacts: $outputDir"
    Write-Host "Payload fingerprint: $payloadFingerprint"
    [pscustomobject]@{
        outputDirectory = $outputDir
        manifest = $manifestFile
        payloadFingerprintSha256 = $payloadFingerprint
    }
} finally {
    $env:JAVA_HOME = $previousJavaHome
    $env:SOURCE_DATE_EPOCH = $previousSourceDateEpoch
    $env:Path = $previousPath
    Dismount-AsciiWorkspace $workspaceMapping
}
