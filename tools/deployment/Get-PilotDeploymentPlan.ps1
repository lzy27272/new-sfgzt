[CmdletBinding()]
param(
    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string]$BaseRef = 'HEAD^',

    [Parameter()]
    [ValidateNotNullOrEmpty()]
    [string]$HeadRef = 'HEAD',

    [Parameter()]
    [AllowEmptyCollection()]
    [string[]]$ChangedPaths
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$changedPathsWereProvided = $PSBoundParameters.ContainsKey('ChangedPaths')
$policyVersion = 3
$classifierSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
$script:MaxCandidateSourceBytes = 5MB

function New-BlockedPlan {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Reasons,

        [Parameter()]
        [string[]]$NormalizedPaths = @()
    )

    return [ordered]@{
        schemaVersion = 1
        policyVersion = $policyVersion
        classifierSha256 = $classifierSha256
        status = 'BLOCKED'
        lane = 'FULL'
        baseRef = $BaseRef
        headRef = $HeadRef
        changedPaths = @($NormalizedPaths)
        reasons = @($Reasons)
        requiresWeb = $true
        requiresCore = $true
        requiresDatabaseBackup = $true
        requiresMigrationGate = $true
        requiresFullRegression = $true
    }
}

function Write-PlanAndExit {
    param(
        [Parameter(Mandatory = $true)]
        [System.Collections.IDictionary]$Plan,

        [Parameter(Mandatory = $true)]
        [int]$ExitCode
    )

    Write-Output ($Plan | ConvertTo-Json -Depth 6 -Compress)
    exit $ExitCode
}

function ConvertTo-NormalizedRepositoryPath {
    param(
        [AllowNull()]
        [string]$Path
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'EMPTY_CHANGED_PATH'
    }

    $normalized = $Path.Trim().Replace('\', '/')
    while ($normalized.StartsWith('./', [System.StringComparison]::Ordinal)) {
        $normalized = $normalized.Substring(2)
    }

    if (
        [string]::IsNullOrWhiteSpace($normalized) -or
        $normalized.StartsWith('/', [System.StringComparison]::Ordinal) -or
        $normalized -match '^[A-Za-z]:/' -or
        $normalized.IndexOf([char]0) -ge 0 -or
        $normalized -match '(^|/)\.\.(/|$)' -or
        $normalized -match '[\x00-\x1F]'
    ) {
        throw 'UNSAFE_CHANGED_PATH'
    }

    return $normalized
}

function Get-GitChangedPaths {
    if ($BaseRef -notmatch '^[A-Za-z0-9][A-Za-z0-9._/@{}~^:+-]*$') {
        throw 'INVALID_BASE_REF'
    }
    if ($HeadRef -notmatch '^[A-Za-z0-9][A-Za-z0-9._/@{}~^:+-]*$') {
        throw 'INVALID_HEAD_REF'
    }

    $gitCommand = Get-Command git -ErrorAction Stop
    # Disable rename detection so a move out of a protected path reports both the
    # deletion and addition. Otherwise a protected infra/database source path
    # could disappear from a name-only diff and be misclassified as WEB_ONLY.
    $gitOutput = & $gitCommand.Source -C $repositoryRoot diff --no-renames --name-only --diff-filter=ACDMRTUXB $BaseRef $HeadRef -- 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw 'GIT_DIFF_FAILED'
    }

    return @($gitOutput | ForEach-Object { [string]$_ })
}

function Test-CandidateRuntimeSource {
    param([Parameter(Mandatory = $true)][string]$Path)

    $lowerPath = $Path.ToLowerInvariant()
    return (
        $lowerPath -match '^apps/web/src/.+\.(ts|tsx|js|jsx|mjs|cjs)$'
    )
}

function Invoke-GitTextCommand {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    $gitCommand = Get-Command git -ErrorAction Stop
    $startInfo = [System.Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $gitCommand.Source
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    $startInfo.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false, $true)
    $startInfo.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false, $true)
    $startInfo.ArgumentList.Add('-C')
    $startInfo.ArgumentList.Add($repositoryRoot)
    foreach ($argument in $Arguments) {
        $startInfo.ArgumentList.Add($argument)
    }

    $process = [System.Diagnostics.Process]::new()
    $process.StartInfo = $startInfo
    if (-not $process.Start()) {
        throw 'SOURCE_CONTENT_GIT_START_FAILED'
    }
    try {
        $standardOutput = $process.StandardOutput.ReadToEndAsync()
        $standardError = $process.StandardError.ReadToEndAsync()
        $process.WaitForExit()
        return [pscustomobject]@{
            ExitCode = $process.ExitCode
            StandardOutput = $standardOutput.GetAwaiter().GetResult()
            StandardError = $standardError.GetAwaiter().GetResult()
        }
    } finally {
        $process.Dispose()
    }
}

$script:GitRefAvailability = @{}

function Test-GitCommitRef {
    param([Parameter(Mandatory = $true)][string]$Ref)

    if ($Ref -notmatch '^[A-Za-z0-9][A-Za-z0-9._/@{}~^:+-]*$') {
        return $false
    }
    if ($script:GitRefAvailability.ContainsKey($Ref)) {
        return [bool]$script:GitRefAvailability[$Ref]
    }

    $result = Invoke-GitTextCommand @('rev-parse', '--verify', '--quiet', "${Ref}^{commit}")
    $available = $result.ExitCode -eq 0
    $script:GitRefAvailability[$Ref] = $available
    return $available
}

function Read-GitSourceContent {
    param(
        [Parameter(Mandatory = $true)][string]$Ref,
        [Parameter(Mandatory = $true)][string]$Path
    )

    if (-not (Test-GitCommitRef $Ref)) {
        return [pscustomobject]@{ Found = $false; Failed = $false; Content = ''; Source = "git:$Ref" }
    }

    $treeResult = Invoke-GitTextCommand @('ls-tree', '--full-tree', '-z', $Ref, '--', ":(literal)$Path")
    if ($treeResult.ExitCode -ne 0) {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = "git:$Ref" }
    }
    $treeEntries = @($treeResult.StandardOutput.Split([char]0, [System.StringSplitOptions]::RemoveEmptyEntries))
    if ($treeEntries.Count -eq 0) {
        return [pscustomobject]@{ Found = $false; Failed = $false; Content = ''; Source = "git:$Ref" }
    }
    if ($treeEntries.Count -ne 1 -or $treeEntries[0] -notmatch '^\d+ blob ([0-9a-fA-F]+)\t') {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = "git:$Ref" }
    }

    $objectId = $Matches[1]
    $sizeResult = Invoke-GitTextCommand @('cat-file', '-s', $objectId)
    $objectSize = 0L
    if ($sizeResult.ExitCode -ne 0 -or -not [long]::TryParse($sizeResult.StandardOutput.Trim(), [ref]$objectSize) -or $objectSize -gt $script:MaxCandidateSourceBytes) {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = "git:$Ref" }
    }

    $contentResult = Invoke-GitTextCommand @('cat-file', 'blob', $objectId)
    if ($contentResult.ExitCode -ne 0 -or $contentResult.StandardOutput.IndexOf([char]0) -ge 0 -or $contentResult.StandardOutput.Contains([char]0xFFFD)) {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = "git:$Ref" }
    }
    return [pscustomobject]@{ Found = $true; Failed = $false; Content = $contentResult.StandardOutput; Source = "git:$Ref" }
}

function Read-WorktreeSourceContent {
    param([Parameter(Mandatory = $true)][string]$Path)

    $platformPath = $Path.Replace('/', [System.IO.Path]::DirectorySeparatorChar)
    $fullPath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $platformPath))
    $rootPrefix = $repositoryRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
    if (-not $fullPath.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = 'worktree' }
    }
    if (-not (Test-Path -LiteralPath $fullPath)) {
        return [pscustomobject]@{ Found = $false; Failed = $false; Content = ''; Source = 'worktree' }
    }
    if (-not (Test-Path -LiteralPath $fullPath -PathType Leaf)) {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = 'worktree' }
    }

    try {
        $item = Get-Item -LiteralPath $fullPath -Force
        if (($item.Attributes -band [System.IO.FileAttributes]::ReparsePoint) -ne 0 -or $item.Length -gt $script:MaxCandidateSourceBytes) {
            throw 'SOURCE_CONTENT_UNSAFE_FILE'
        }
        $bytes = [System.IO.File]::ReadAllBytes($fullPath)
        $strictUtf8 = [System.Text.UTF8Encoding]::new($false, $true)
        $content = $strictUtf8.GetString($bytes)
        if ($content.IndexOf([char]0) -ge 0) {
            throw 'SOURCE_CONTENT_CONTAINS_NUL'
        }
        return [pscustomobject]@{ Found = $true; Failed = $false; Content = $content; Source = 'worktree' }
    } catch {
        return [pscustomobject]@{ Found = $false; Failed = $true; Content = ''; Source = 'worktree' }
    }
}

function Get-CandidateSourceContents {
    param([Parameter(Mandatory = $true)][string]$Path)

    $contents = New-Object System.Collections.Generic.List[string]
    $sources = New-Object System.Collections.Generic.List[string]
    $readFailed = $false

    if ($changedPathsWereProvided) {
        $worktreeResult = Read-WorktreeSourceContent $Path
        $readFailed = $readFailed -or $worktreeResult.Failed
        if ($worktreeResult.Found) {
            $contents.Add($worktreeResult.Content)
            $sources.Add($worktreeResult.Source)
        }

        # Explicit ChangedPaths is used for local/contract checks. Inspect any
        # resolvable requested refs and HEAD as a deletion-safe baseline, while
        # allowing an existing worktree file when synthetic test refs are used.
        $candidateRefs = @($BaseRef, $HeadRef, 'HEAD') | Select-Object -Unique
        foreach ($ref in $candidateRefs) {
            if (-not (Test-GitCommitRef $ref)) { continue }
            $gitResult = Read-GitSourceContent $ref $Path
            $readFailed = $readFailed -or $gitResult.Failed
            if ($gitResult.Found) {
                $contents.Add($gitResult.Content)
                $sources.Add($gitResult.Source)
            }
        }
    } else {
        foreach ($ref in @($BaseRef, $HeadRef) | Select-Object -Unique) {
            if (-not (Test-GitCommitRef $ref)) {
                $readFailed = $true
                continue
            }
            $gitResult = Read-GitSourceContent $ref $Path
            $readFailed = $readFailed -or $gitResult.Failed
            if ($gitResult.Found) {
                $contents.Add($gitResult.Content)
                $sources.Add($gitResult.Source)
            }
        }
    }

    return [pscustomobject]@{
        Success = -not $readFailed -and $contents.Count -gt 0
        Contents = [string[]]$contents
        Sources = [string[]]$sources
    }
}

function Test-SecurityBoundaryContent {
    param([Parameter(Mandatory = $true)][string[]]$Contents)

    $patterns = @(
        '(?i)(?<![A-Za-z0-9_])(AccessPolicy|TenantPrincipal|PageAccessBoundary|AccessDeniedException|OrgScopeResolver|SecurityContextHolder|Authentication|Principal|GrantedAuthority|ResponseCookie|CookieValue|HttpOnly|SameSite|AttachmentMalwareScanner)(?![A-Za-z0-9_])',
        '(?i)\b(permissions?|permissionCodes?|roleCodes?|assignedRoles?|businessRole|presentationRole|hasRole|tenantScope|tenantId|tenant_id|orgScopes?|organizationScope|authorizationScope(Type)?|authorization_scope|visibleNavigation|visibility|scopeIds|org_unit_closure|role_code|permission_code)\b',
        '(?i)(auth(?:entication|orization)?|authorit(?:y|ies)|oauth2?|oidc|sso|tokens?|jwt|secrets?|credentials?|passwords?|passcodes?|otp|cookies?|csrf|cors|malware|antivirus|scanner|bypass|webhook)',
        '(权限|角色|租户|认证|鉴权|授权|凭据|口令|密码|令牌|密钥|组织.{0,16}(可见|范围)|可见.{0,16}组织|数据.{0,8}范围)'
    )

    foreach ($content in $Contents) {
        foreach ($pattern in $patterns) {
            if ($content -match $pattern) {
                return $true
            }
        }
    }
    return $false
}

function Apply-ContentSafetyClassification {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)]$Classification
    )

    if ($Classification.Lane -ceq 'FULL' -or -not (Test-CandidateRuntimeSource $Path)) {
        return $Classification
    }

    $inspection = Get-CandidateSourceContents $Path
    if (-not $inspection.Success) {
        return [pscustomobject]@{
            Lane = 'FULL'
            Code = 'SOURCE_CONTENT_UNAVAILABLE'
            Message = 'Candidate runtime source could not be read completely from the requested refs or usable worktree and was failed closed.'
            RequiresWeb = $true
        }
    }
    if (Test-SecurityBoundaryContent $inspection.Contents) {
        return [pscustomobject]@{
            Lane = 'FULL'
            Code = 'SECURITY_BOUNDARY_CONTENT'
            Message = 'Source content references authentication, credentials, authorization, malware controls, role, tenant, or organization-visibility boundaries and requires the complete release path.'
            RequiresWeb = $true
        }
    }
    return $Classification
}

function Test-DependencyOrBuildInput {
    param([Parameter(Mandatory = $true)][string]$LowerPath)

    return (
        $LowerPath -match '(^|/)(package\.json|package-lock\.json|pnpm-lock\.ya?ml|pnpm-workspace\.ya?ml|yarn\.lock|bun\.lockb?|[^/]*pom\.xml|gradle\.properties|build\.gradle(\.kts)?|settings\.gradle(\.kts)?|requirements([.-][^/]*)?\.txt|poetry\.lock|pipfile(\.lock)?|cargo\.(toml|lock)|go\.(mod|sum)|composer\.(json|lock)|[^/]+\.(csproj|fsproj|vbproj))$' -or
        $LowerPath -match '(^|/)(\.npmrc|\.yarnrc(\.yml)?|\.pnpmfile\.cjs|\.mvn/|gradle/wrapper/)'
    )
}

function Test-SecurityOrIdentityPath {
    param([Parameter(Mandatory = $true)][string]$LowerPath)

    return (
        $LowerPath -match '(^|/)(auth|authentication|authorization|security|permissions?|roles?|scopes?|policies|policy|access|rbac|iam|oauth|oidc|sso|sessions?|tokens?|credentials?|secrets?|tenants?|privileges?)(/|$)' -or
        $LowerPath -match '(^|/)[^/]*(auth|permission|security|roles?|scopes?|polic(y|ies)|access|rbac|oauth|oidc|sso|credential|secret|jwt|session|login|tenant|privilege|entitlement|row[-_]?level)[^/]*\.[^/]+$'
    )
}

function Test-ApiContractOrDomainPath {
    param([Parameter(Mandatory = $true)][string]$LowerPath)

    return (
        $LowerPath -match '(^|/)(api|contracts?|schemas?|domain|models?|dto|openapi|swagger|graphql)(/|$)' -or
        $LowerPath -match '(^|/)[^/]*(api|contract|schema|domain|model|dto|openapi|swagger)[^/]*\.(ts|tsx|js|jsx|mjs|cjs|java|kt|kts|json|ya?ml|proto|graphql|gql|avsc)$' -or
        $LowerPath -match '\.(proto|graphql|gql|avsc)$'
    )
}

function Get-PathClassification {
    param([Parameter(Mandatory = $true)][string]$Path)

    $lowerPath = $Path.ToLowerInvariant()

    # Core API has no provably safe partial-release subset. Java/Kotlin code,
    # resources, native SQL embedded in otherwise innocuous source, build
    # inputs, and data-boundary behavior all execute in the same privileged
    # process. Keep this application-boundary guard first so later allowlists or
    # keyword heuristics can never downgrade a Core API change to BACKEND.
    if ($lowerPath.StartsWith('apps/core-api/', [System.StringComparison]::Ordinal)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'CORE_API_CHANGE'; Message = 'Every Core API source, resource, configuration, build, and runtime change fails closed to the complete release gate.'; RequiresWeb = $true }
    }

    if ($lowerPath -match '(^|/)(migrations?|flyway|liquibase)(/|$)' -or $lowerPath.StartsWith('database/', [System.StringComparison]::Ordinal) -or $lowerPath.EndsWith('.sql', [System.StringComparison]::Ordinal)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'DATABASE_OR_MIGRATION'; Message = 'Database or migration inputs require backup and migration gates.'; RequiresWeb = $true }
    }

    if ($lowerPath.StartsWith('infra/', [System.StringComparison]::Ordinal) -or $lowerPath.StartsWith('.github/', [System.StringComparison]::Ordinal) -or $lowerPath -match '(^|/)(dockerfile|docker-compose[^/]*\.ya?ml|compose[^/]*\.ya?ml|caddyfile|nginx[^/]*\.conf)$') {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'INFRA_OR_CI'; Message = 'Infrastructure and CI changes require the complete release gate.'; RequiresWeb = $true }
    }

    if ($lowerPath.StartsWith('tools/', [System.StringComparison]::Ordinal)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'OPERATIONAL_TOOLING'; Message = 'Operational, release, and deployment tooling changes are never self-approved for a fast lane.'; RequiresWeb = $true }
    }

    if (Test-DependencyOrBuildInput $lowerPath) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'DEPENDENCY_OR_BUILD_INPUT'; Message = 'Dependency manifests, locks, and build-system inputs require the complete release gate.'; RequiresWeb = $true }
    }

    if ($lowerPath -match '(^|/)\.env($|\.)' -or $lowerPath -match '(^|/)(application|bootstrap)(-[^/]*)?\.(properties|ya?ml)$' -or $lowerPath -match '(^|/)(config|configuration)/') {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'RUNTIME_CONFIGURATION'; Message = 'Runtime configuration changes require the complete release gate.'; RequiresWeb = $true }
    }

    if (Test-SecurityOrIdentityPath $lowerPath) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'SECURITY_OR_IDENTITY'; Message = 'Authentication, authorization, permission, session, and security changes require the complete release gate.'; RequiresWeb = $true }
    }

    if ($lowerPath.StartsWith('packages/', [System.StringComparison]::Ordinal) -or (Test-ApiContractOrDomainPath $lowerPath)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'SHARED_CONTRACT_OR_DOMAIN'; Message = 'API contracts and shared domain models can affect both web and core and require the complete release path.'; RequiresWeb = $true }
    }

    $webSourcePattern = '^apps/web/src/.+\.(ts|tsx|js|jsx|mjs|cjs|css|scss|sass|less|svg|png|jpe?g|gif|webp|avif|ico|woff2?|ttf|otf)$'
    $webPublicPattern = '^apps/web/public/.+\.(html?|css|svg|png|jpe?g|gif|webp|avif|ico|woff2?|ttf|otf|txt|xml)$'
    $webTestPattern = '^apps/web/(tests|prototype)/.+\.(ts|tsx|js|jsx|mjs|cjs|css|scss|sass|less|snap|json|html?|svg|png|jpe?g|gif|webp|avif)$'
    if ($lowerPath -eq 'apps/web/index.html' -or $lowerPath -match $webSourcePattern -or $lowerPath -match $webPublicPattern -or $lowerPath -match $webTestPattern) {
        return [pscustomobject]@{ Lane = 'WEB_ONLY'; Code = 'EXPLICIT_WEB_FRONTEND'; Message = 'Only explicitly allowlisted frontend source, style, static asset, or frontend test files changed.'; RequiresWeb = $true }
    }

    if ($lowerPath.StartsWith('apps/web/', [System.StringComparison]::Ordinal)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'WEB_BUILD_OR_UNCERTAIN'; Message = 'The web path is not in the pure-frontend allowlist and requires the complete release path.'; RequiresWeb = $true }
    }

    if ($lowerPath.StartsWith('apps/', [System.StringComparison]::Ordinal)) {
        return [pscustomobject]@{ Lane = 'FULL'; Code = 'UNSUPPORTED_APPLICATION_TARGET'; Message = 'This application has no dedicated fast-lane installer and requires the complete release path.'; RequiresWeb = $true }
    }

    return [pscustomobject]@{ Lane = 'FULL'; Code = 'UNRECOGNIZED_OR_DOCUMENTATION'; Message = 'Documentation, repository metadata, and unrecognized paths fail closed to the complete release gate.'; RequiresWeb = $true }
}

try {
    $rawPaths = if ($changedPathsWereProvided) { @($ChangedPaths) } else { @(Get-GitChangedPaths) }
    $normalizedPaths = New-Object System.Collections.Generic.List[string]
    $seenPaths = New-Object 'System.Collections.Generic.HashSet[string]' ([System.StringComparer]::Ordinal)

    foreach ($rawPath in $rawPaths) {
        $normalizedPath = ConvertTo-NormalizedRepositoryPath $rawPath
        if ($seenPaths.Add($normalizedPath)) {
            $normalizedPaths.Add($normalizedPath)
        }
    }

    $sortedPaths = @($normalizedPaths | Sort-Object)
    if ($sortedPaths.Count -eq 0) {
        Write-PlanAndExit (New-BlockedPlan -Reasons @('NO_CHANGED_PATHS: Deployment classification requires at least one changed repository path.')) 2
    }

    $classifications = @($sortedPaths | ForEach-Object {
        $classification = Apply-ContentSafetyClassification $_ (Get-PathClassification $_)
        [pscustomobject]@{
            Path = $_
            Lane = $classification.Lane
            Code = $classification.Code
            Message = $classification.Message
            RequiresWeb = [bool]$classification.RequiresWeb
        }
    })

    $containsFull = @($classifications | Where-Object { $_.Lane -ceq 'FULL' }).Count -gt 0
    $containsBackend = @($classifications | Where-Object { $_.Lane -ceq 'BACKEND' }).Count -gt 0
    $containsWebOnly = @($classifications | Where-Object { $_.Lane -ceq 'WEB_ONLY' }).Count -gt 0
    $mixedWebAndBackend = $containsBackend -and $containsWebOnly

    $lane = if ($containsFull -or $mixedWebAndBackend) {
        'FULL'
    } elseif ($containsBackend) {
        'BACKEND'
    } else {
        'WEB_ONLY'
    }

    $reasons = @($classifications |
        Group-Object Code |
        Sort-Object Name |
        ForEach-Object {
            $group = $_.Group
            $groupPaths = @($group | ForEach-Object { $_.Path } | Sort-Object)
            '{0}: {1} Paths: {2}' -f $_.Name, $group[0].Message, ($groupPaths -join ', ')
        })

    if ($mixedWebAndBackend) {
        $reasons = @($reasons) + @('COMBINED_WEB_CORE_UNSUPPORTED: The backend fast-lane installer only switches Core API; combined web/core changes require the complete release path.')
        $reasons = @($reasons | Sort-Object)
    }

    $plan = [ordered]@{
        schemaVersion = 1
        policyVersion = $policyVersion
        classifierSha256 = $classifierSha256
        status = 'PASS'
        lane = $lane
        baseRef = $BaseRef
        headRef = $HeadRef
        changedPaths = $sortedPaths
        reasons = $reasons
        requiresWeb = [bool]($lane -ne 'BACKEND')
        requiresCore = [bool]($lane -ne 'WEB_ONLY')
        requiresDatabaseBackup = [bool]($lane -ceq 'FULL')
        requiresMigrationGate = [bool]($lane -ceq 'FULL')
        requiresFullRegression = [bool]($lane -ne 'WEB_ONLY')
    }

    Write-PlanAndExit $plan 0
} catch {
    $reasonCode = if ($_.Exception.Message -match '^[A-Z][A-Z0-9_]+$') { $_.Exception.Message } else { 'CLASSIFICATION_FAILED' }
    Write-PlanAndExit (New-BlockedPlan -Reasons @("${reasonCode}: Deployment classification failed closed.")) 2
}
