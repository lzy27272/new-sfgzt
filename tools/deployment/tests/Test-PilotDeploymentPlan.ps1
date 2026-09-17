[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$classifierPath = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\Get-PilotDeploymentPlan.ps1'))
$repositoryRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..\..'))
$pwshPath = (Get-Process -Id $PID).Path
$cases = New-Object System.Collections.Generic.List[object]
$failures = New-Object System.Collections.Generic.List[string]

function ConvertTo-SingleQuotedLiteral {
    param([Parameter(Mandatory = $true)][string]$Value)
    return "'" + $Value.Replace("'", "''") + "'"
}

function Invoke-Classifier {
    param(
        [Parameter(Mandatory = $true)]
        [AllowEmptyCollection()]
        [string[]]$Paths,

        [string]$BaseRef = 'base-test',
        [string]$HeadRef = 'head-test'
    )

    $pathExpression = if ($Paths.Count -eq 0) {
        '@()'
    } else {
        '@(' + (($Paths | ForEach-Object { ConvertTo-SingleQuotedLiteral $_ }) -join ',') + ')'
    }
    $command = '& {0} -BaseRef {1} -HeadRef {2} -ChangedPaths {3}' -f (
        ConvertTo-SingleQuotedLiteral $classifierPath
    ), (ConvertTo-SingleQuotedLiteral $BaseRef), (ConvertTo-SingleQuotedLiteral $HeadRef), $pathExpression

    $output = & $pwshPath -NoLogo -NoProfile -NonInteractive -Command $command 2>&1
    $exitCode = $LASTEXITCODE
    $text = (@($output | ForEach-Object { [string]$_ }) -join [Environment]::NewLine).Trim()
    $document = $null
    try {
        $document = $text | ConvertFrom-Json -ErrorAction Stop
    } catch {
        throw "Classifier did not return valid JSON. exit=$exitCode output=$text"
    }

    return [pscustomobject]@{
        ExitCode = $exitCode
        Document = $document
    }
}

function Invoke-SyntheticCoreSourceClassifier {
    param(
        [Parameter(Mandatory = $true)][string]$FileName,
        [Parameter(Mandatory = $true)][string]$Content
    )

    if ($FileName -notmatch '^[A-Za-z][A-Za-z0-9]*\.java$') {
        throw 'Unsafe synthetic classifier fixture filename.'
    }

    $fixtureId = [Guid]::NewGuid().ToString('N')
    $relativeDirectory = "apps/core-api/src/main/java/cn/sifangguan/hotelaios/classifiercontract/$fixtureId"
    $relativePath = "$relativeDirectory/$FileName"
    $fixtureDirectory = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $relativeDirectory.Replace('/', [System.IO.Path]::DirectorySeparatorChar)))
    $fixturePath = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot $relativePath.Replace('/', [System.IO.Path]::DirectorySeparatorChar)))
    $fixtureRoot = [System.IO.Path]::GetFullPath((Join-Path $repositoryRoot 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/classifiercontract'))

    if (Test-Path -LiteralPath $fixturePath) {
        throw 'Synthetic classifier fixture unexpectedly already exists.'
    }

    [System.IO.Directory]::CreateDirectory($fixtureDirectory) | Out-Null
    try {
        [System.IO.File]::WriteAllText($fixturePath, $Content, [System.Text.UTF8Encoding]::new($false))
        return Invoke-Classifier -Paths @($relativePath)
    } finally {
        if (Test-Path -LiteralPath $fixturePath -PathType Leaf) {
            [System.IO.File]::Delete($fixturePath)
        }
        if (Test-Path -LiteralPath $fixtureDirectory -PathType Container) {
            [System.IO.Directory]::Delete($fixtureDirectory, $false)
        }
        if ((Test-Path -LiteralPath $fixtureRoot -PathType Container) -and [System.IO.Directory]::GetFileSystemEntries($fixtureRoot).Count -eq 0) {
            [System.IO.Directory]::Delete($fixtureRoot, $false)
        }
    }
}

function Add-Case {
    param(
        [Parameter(Mandatory = $true)][string]$Name,
        [Parameter(Mandatory = $true)][bool]$Passed,
        [Parameter(Mandatory = $true)][string]$Detail
    )

    $cases.Add([ordered]@{ name = $Name; status = if ($Passed) { 'PASS' } else { 'FAIL' }; detail = $Detail })
    if (-not $Passed) {
        $failures.Add("${Name}: $Detail")
    }
}

function Test-CommonSchema {
    param(
        [Parameter(Mandatory = $true)]$Result,
        [Parameter(Mandatory = $true)][string]$CaseName
    )

    $document = $Result.Document
    $properties = @(
        'schemaVersion', 'policyVersion', 'classifierSha256', 'status', 'lane', 'baseRef', 'headRef', 'changedPaths', 'reasons',
        'requiresWeb', 'requiresCore', 'requiresDatabaseBackup', 'requiresMigrationGate', 'requiresFullRegression'
    )
    $missing = @($properties | Where-Object { $null -eq $document.PSObject.Properties[$_] })
    $booleans = @('requiresWeb', 'requiresCore', 'requiresDatabaseBackup', 'requiresMigrationGate', 'requiresFullRegression')
    $booleanTypesValid = @($booleans | Where-Object { $document.$_ -isnot [bool] }).Count -eq 0
    $valid = $document.schemaVersion -is [long] -and $document.schemaVersion -eq 1 -and
        $document.policyVersion -is [long] -and $document.policyVersion -eq 3 -and
        $document.classifierSha256 -match '^[0-9a-f]{64}$' -and $missing.Count -eq 0 -and $booleanTypesValid
    Add-Case "$CaseName-schema" $valid "missing=$($missing -join ','), booleanTypesValid=$booleanTypesValid, schemaVersion=$($document.schemaVersion), policyVersion=$($document.policyVersion)"
}

$safeWebPath = 'apps/web/src/shared/attachmentPreview.ts'
$coreRuntimePath = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/workdata/FormPayloadValidator.java'

$web = Invoke-Classifier -Paths @($safeWebPath)
Test-CommonSchema $web 'web-only'
Add-Case 'web-only-lane' (
    $web.ExitCode -eq 0 -and $web.Document.status -ceq 'PASS' -and $web.Document.lane -ceq 'WEB_ONLY' -and
    $web.Document.requiresWeb -and -not $web.Document.requiresCore -and -not $web.Document.requiresDatabaseBackup -and
    -not $web.Document.requiresMigrationGate -and -not $web.Document.requiresFullRegression
) "exit=$($web.ExitCode), lane=$($web.Document.lane)"

$webKinds = Invoke-Classifier -Paths @(
    'apps/web/src/styles/pilot.scss',
    'apps/web/public/logo.svg',
    'apps/web/tests/workbench.test.mjs',
    '.\apps\web\prototype\dashboard-cards.html'
)
Add-Case 'web-assets-tests-normalized' (
    $webKinds.ExitCode -eq 0 -and $webKinds.Document.lane -ceq 'WEB_ONLY' -and
    @($webKinds.Document.changedPaths | Where-Object { $_ -match '\\' }).Count -eq 0 -and
    @($webKinds.Document.changedPaths).Count -eq 4
) "exit=$($webKinds.ExitCode), lane=$($webKinds.Document.lane), paths=$(@($webKinds.Document.changedPaths).Count)"

$core = Invoke-Classifier -Paths @($coreRuntimePath)
Add-Case 'core-runtime-fails-closed' (
    $core.ExitCode -eq 0 -and $core.Document.lane -ceq 'FULL' -and $core.Document.requiresWeb -and
    $core.Document.requiresCore -and $core.Document.requiresDatabaseBackup -and $core.Document.requiresMigrationGate -and
    $core.Document.requiresFullRegression -and
    @($core.Document.reasons | Where-Object { $_ -match '^CORE_API_CHANGE:' }).Count -eq 1
) "exit=$($core.ExitCode), lane=$($core.Document.lane)"

$mixed = Invoke-Classifier -Paths @($safeWebPath, $coreRuntimePath)
Add-Case 'mixed-web-core-lane' (
    $mixed.ExitCode -eq 0 -and $mixed.Document.lane -ceq 'FULL' -and $mixed.Document.requiresWeb -and $mixed.Document.requiresCore -and
    $mixed.Document.requiresDatabaseBackup -and $mixed.Document.requiresMigrationGate -and
    @($mixed.Document.reasons | Where-Object { $_ -match '^CORE_API_CHANGE:' }).Count -eq 1 -and
    @($mixed.Document.changedPaths).Count -eq 2
) "exit=$($mixed.ExitCode), lane=$($mixed.Document.lane), paths=$(@($mixed.Document.changedPaths).Count)"

$coreFailClosedCases = [ordered]@{
    'controller-without-keywords' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/guest/GuestLedgerController.java'
    'repository-without-keywords' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/guest/GuestLedgerRepository.java'
    'integration-without-keywords' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/ota/RoomSyncClient.java'
    'runtime-resource-without-keywords' = 'apps/core-api/src/main/resources/templates/receipt.mustache'
}

# Real worktree fixtures prove that the result is not an artifact of an
# unreadable/deleted path. Neither source contains any classifier keyword: one
# performs native DDL and the other omits all ownership predicates.
$nativeDdl = Invoke-SyntheticCoreSourceClassifier -FileName 'TableWarmupJob.java' -Content @'
package cn.sifangguan.hotelaios.classifiercontract;
final class TableWarmupJob {
    void run(java.sql.Statement statement) throws Exception {
        statement.execute("create table if not exists work_snapshot (id bigint primary key)");
    }
}
'@
Add-Case 'native-ddl-without-keywords' (
    $nativeDdl.ExitCode -eq 0 -and $nativeDdl.Document.status -ceq 'PASS' -and $nativeDdl.Document.lane -ceq 'FULL' -and
    @($nativeDdl.Document.reasons | Where-Object { $_ -match '^CORE_API_CHANGE:' }).Count -eq 1
) "exit=$($nativeDdl.ExitCode), lane=$($nativeDdl.Document.lane), reasons=$($nativeDdl.Document.reasons -join '|')"

$dataRangeBypass = Invoke-SyntheticCoreSourceClassifier -FileName 'StoreDigestReader.java' -Content @'
package cn.sifangguan.hotelaios.classifiercontract;
final class StoreDigestReader {
    String load() {
        return "select id, title from work_item order by created_at desc";
    }
}
'@
Add-Case 'data-range-bypass-without-keywords' (
    $dataRangeBypass.ExitCode -eq 0 -and $dataRangeBypass.Document.status -ceq 'PASS' -and $dataRangeBypass.Document.lane -ceq 'FULL' -and
    @($dataRangeBypass.Document.reasons | Where-Object { $_ -match '^CORE_API_CHANGE:' }).Count -eq 1
) "exit=$($dataRangeBypass.ExitCode), lane=$($dataRangeBypass.Document.lane), reasons=$($dataRangeBypass.Document.reasons -join '|')"

foreach ($entry in $coreFailClosedCases.GetEnumerator()) {
    $result = Invoke-Classifier -Paths @($entry.Value)
    Add-Case $entry.Key (
        $result.ExitCode -eq 0 -and $result.Document.status -ceq 'PASS' -and $result.Document.lane -ceq 'FULL' -and
        $result.Document.requiresWeb -and $result.Document.requiresCore -and $result.Document.requiresDatabaseBackup -and
        $result.Document.requiresMigrationGate -and $result.Document.requiresFullRegression -and
        @($result.Document.reasons | Where-Object { $_ -match '^CORE_API_CHANGE:' }).Count -eq 1
    ) "path=$($entry.Value), exit=$($result.ExitCode), lane=$($result.Document.lane), reasons=$($result.Document.reasons -join '|')"
}

$mixedFull = Invoke-Classifier -Paths @($safeWebPath, 'database/migrations/V100__mixed.sql')
Add-Case 'full-path-dominates-mixed-change-set' (
    $mixedFull.ExitCode -eq 0 -and $mixedFull.Document.lane -ceq 'FULL' -and
    $mixedFull.Document.requiresDatabaseBackup -and $mixedFull.Document.requiresMigrationGate -and
    @($mixedFull.Document.changedPaths).Count -eq 2
) "exit=$($mixedFull.ExitCode), lane=$($mixedFull.Document.lane), paths=$(@($mixedFull.Document.changedPaths).Count)"

$contract = Invoke-Classifier -Paths @('packages/ota-contracts/src/ReservationContract.ts')
Add-Case 'shared-contract-lane' (
    $contract.ExitCode -eq 0 -and $contract.Document.lane -ceq 'FULL' -and $contract.Document.requiresWeb -and $contract.Document.requiresCore -and
    $contract.Document.requiresDatabaseBackup -and $contract.Document.requiresMigrationGate
) "exit=$($contract.ExitCode), lane=$($contract.Document.lane)"

$webApi = Invoke-Classifier -Paths @('apps/web/src/api/workbenchClient.ts')
Add-Case 'web-api-not-web-only' ($webApi.ExitCode -eq 0 -and $webApi.Document.lane -ceq 'FULL') "exit=$($webApi.ExitCode), lane=$($webApi.Document.lane)"

$contentBoundaryCases = [ordered]@{
    'app-page-access-boundary-content' = 'apps/web/src/App.tsx'
    'daily-operation-access-policy-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/dailyoperations/DailyOperationReadService.java'
    'organization-tenant-boundary-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/organization/OrganizationService.java'
    'wecom-entry-oauth-credential-content' = 'apps/web/src/features/wecom/entryRoute.ts'
    'wecom-registration-password-content' = 'apps/web/src/features/wecom/directoryOnboardingRegistration.ts'
    'ctrip-bridge-token-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/ota/CtripPilotBridgeProperties.java'
    'ctrip-credential-client-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/ota/CtripPilotUnixSocketClient.java'
    'wecom-oauth-cookie-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/wecom/WeComDirectoryOnboardingController.java'
    'wecom-enrollment-token-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/wecom/WeComBindingEnrollmentController.java'
    'wecom-webhook-credential-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/integrations/wecom/WeComGroupRobotClient.java'
    'attachment-malware-bypass-content' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/workdata/AttachmentMalwareScanner.java'
}

foreach ($entry in $contentBoundaryCases.GetEnumerator()) {
    $result = Invoke-Classifier -Paths @($entry.Value)
    $expectedReason = if ($entry.Value.StartsWith('apps/core-api/', [System.StringComparison]::OrdinalIgnoreCase)) {
        '^CORE_API_CHANGE:'
    } else {
        '^SECURITY_BOUNDARY_CONTENT:'
    }
    Add-Case $entry.Key (
        $result.ExitCode -eq 0 -and $result.Document.status -ceq 'PASS' -and $result.Document.lane -ceq 'FULL' -and
        @($result.Document.reasons | Where-Object { $_ -match $expectedReason }).Count -eq 1
    ) "path=$($entry.Value), exit=$($result.ExitCode), lane=$($result.Document.lane), reasons=$($result.Document.reasons -join '|')"
}

$unreadableSource = Invoke-Classifier -Paths @('apps/web/src/features/not-present/UnreadableCandidate.tsx')
Add-Case 'unreadable-candidate-source-fails-closed' (
    $unreadableSource.ExitCode -eq 0 -and $unreadableSource.Document.lane -ceq 'FULL' -and
    @($unreadableSource.Document.reasons | Where-Object { $_ -match '^SOURCE_CONTENT_UNAVAILABLE:' }).Count -eq 1
) "exit=$($unreadableSource.ExitCode), lane=$($unreadableSource.Document.lane), reasons=$($unreadableSource.Document.reasons -join '|')"

$fullCases = [ordered]@{
    'database-migration' = 'database/migrations/V99__test.sql'
    'infrastructure' = 'infra/production/Caddyfile'
    'ci-workflow' = '.github/workflows/ci.yml'
    'deployment-tooling' = 'tools/deployment/Get-PilotDeploymentPlan.ps1'
    'dependency-lock' = 'apps/web/pnpm-lock.yaml'
    'identity-sensitive-web' = 'apps/web/src/auth/LoginPage.tsx'
    'permission-sensitive-core' = 'apps/core-api/src/main/java/cn/sfgzt/security/PermissionService.java'
    'role-sensitive-web' = 'apps/web/src/data/roles.ts'
    'access-sensitive-web' = 'apps/web/src/features/tasks/taskDispatchAccess.ts'
    'policy-sensitive-web' = 'apps/web/src/app/rolePresentationPolicy.ts'
    'scope-sensitive-core' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/dailyoperations/OperationScopeService.java'
    'tenant-sensitive-core' = 'apps/core-api/src/main/java/cn/sifangguan/hotelaios/shared/events/TenantSystemAccountResolver.java'
    'runtime-environment' = 'apps/web/.env.pilot'
    'shared-domain-model' = 'apps/web/src/domain.ts'
    'unsupported-worker-target' = 'apps/ota-connector-worker/src/worker.mjs'
    'unsupported-standalone-web-target' = 'apps/ota-standalone-web/src/App.tsx'
    'documentation' = 'docs/DEPLOYMENT.md'
    'unknown-root-file' = 'unexpected.release-input'
}

foreach ($entry in $fullCases.GetEnumerator()) {
    $result = Invoke-Classifier -Paths @($entry.Value)
    Add-Case $entry.Key (
        $result.ExitCode -eq 0 -and $result.Document.status -ceq 'PASS' -and $result.Document.lane -ceq 'FULL' -and
        $result.Document.requiresWeb -and $result.Document.requiresCore -and $result.Document.requiresDatabaseBackup -and
        $result.Document.requiresMigrationGate -and $result.Document.requiresFullRegression
    ) "path=$($entry.Value), exit=$($result.ExitCode), lane=$($result.Document.lane)"
}

$duplicate = Invoke-Classifier -Paths @($safeWebPath, $safeWebPath, 'apps/web/src/styles/base.css')
Add-Case 'duplicate-paths-deduplicated' (
    $duplicate.ExitCode -eq 0 -and @($duplicate.Document.changedPaths).Count -eq 2
) "exit=$($duplicate.ExitCode), paths=$(@($duplicate.Document.changedPaths).Count)"

$unsafe = Invoke-Classifier -Paths @('../outside.ps1')
Test-CommonSchema $unsafe 'unsafe-path'
Add-Case 'unsafe-path-blocked' (
    $unsafe.ExitCode -ne 0 -and $unsafe.Document.status -ceq 'BLOCKED' -and $unsafe.Document.lane -ceq 'FULL' -and
    @($unsafe.Document.reasons | Where-Object { $_ -match '^UNSAFE_CHANGED_PATH:' }).Count -eq 1
) "exit=$($unsafe.ExitCode), status=$($unsafe.Document.status), lane=$($unsafe.Document.lane)"

$empty = Invoke-Classifier -Paths @()
Add-Case 'empty-change-set-blocked' (
    $empty.ExitCode -ne 0 -and $empty.Document.status -ceq 'BLOCKED' -and $empty.Document.lane -ceq 'FULL' -and
    @($empty.Document.reasons | Where-Object { $_ -match '^NO_CHANGED_PATHS:' }).Count -eq 1
) "exit=$($empty.ExitCode), status=$($empty.Document.status), lane=$($empty.Document.lane)"

$caseArray = [object[]]$cases
$failureArray = [string[]]$failures
$passedCount = @($caseArray | Where-Object { $_.status -ceq 'PASS' }).Count
$result = [ordered]@{
    schemaVersion = 1
    status = if ($failures.Count -eq 0) { 'PASS' } else { 'FAIL' }
    summary = [ordered]@{
        total = $cases.Count
        passed = $passedCount
        failed = $failures.Count
    }
    cases = $caseArray
    failures = $failureArray
}

Write-Output ($result | ConvertTo-Json -Depth 8)
if ($failures.Count -eq 0) { exit 0 }
exit 1
