import { apiBlob, apiMutation, apiRequest, asList, demoFallbackEnabled, demoOnlyEnabled, type ApiIdentity } from './client'
import { withRequestDeadline } from './requestDeadline'
import { dashboardApiPaths } from '../app/dashboardContract'
import { camelizeKeysDeep } from '../features/shared/featureApi'
import type { UsageDetail } from '../features/storeResources/workUsage'
import { materializeFileForUpload, readableUploadError } from '../app/uploadFiles'
import type {
  AccessibleHotels,
  ApiSource,
  DashboardMetric,
  DashboardRisk,
  EnterpriseTemplate,
  EnterpriseTemplateType,
  HotelDashboard,
  IdentitySnapshot,
  ManagementRule,
  ManagementTask,
  NotificationItem,
  OperationsDashboard,
  OperationsHotel,
  RuleDetail,
  RuleScope,
  RuleVersionDetail,
  RuleVersionDraft,
  StandardEvaluation,
  TeamWorkCase,
  TaskTimelineItem,
  TaskEvidence,
  WorkExpectation,
  WorkCompletionMetric,
  WorkPackage,
  WorkbenchSummary,
  WorkRecordAttachment,
  WorkRecordDetail,
  WorkRecordSummary,
  WorkStandardReference,
} from '../domain'

type JsonObject = Record<string, unknown>

const object = (value: unknown): JsonObject => value && typeof value === 'object' ? value as JsonObject : {}
const value = (item: JsonObject, ...keys: string[]): unknown => {
  for (const key of keys) if (item[key] !== undefined && item[key] !== null) return item[key]
  return undefined
}
const text = (item: JsonObject, keys: string[], fallback = '—') => String(value(item, ...keys) ?? fallback)
const number = (item: JsonObject, keys: string[], fallback = 0) => Number(value(item, ...keys) ?? fallback)

function jsonColumn(raw: unknown): unknown {
  if (typeof raw === 'string') {
    try { return JSON.parse(raw) } catch { return raw }
  }
  const wrapped = object(raw)
  if (typeof wrapped.value === 'string' && (wrapped.type === 'jsonb' || wrapped.type === 'json')) {
    try { return JSON.parse(wrapped.value) } catch { return raw }
  }
  return raw
}

function stringArrayMap(raw: unknown): Record<string, string[]> {
  return Object.fromEntries(Object.entries(object(raw)).map(([key, configured]) => [
    key,
    asList<unknown>(configured).filter((entry): entry is string => typeof entry === 'string'),
  ]))
}

export async function loadIdentity(identity: ApiIdentity, fallback: IdentitySnapshot) {
  return withFallback(async () => {
    // Bootstrap must not reuse the demo/default assignment. The server resolves
    // the real account first and returns its valid assignments for selection.
    const raw = object(await withRequestDeadline(
      (signal) => apiRequest<unknown>('/iam/me', { ...identity, businessActorAssignmentId: undefined }, { signal }),
      '身份与权限读取超时，请检查网络后点击“重新验证”；系统未放开任何账号权限。',
    ))
    const account = object(raw.account)
    const employee = object(raw.employee)
    const assignments = asList<JsonObject>(value(raw, 'positionAssignments', 'assignments'))
    const capabilities = object(raw.capabilities)
    const groupManagement = object(capabilities.groupManagement)
    const snapshot: IdentitySnapshot = {
      accountId: text(account, ['id'], identity.actorId),
      displayName: text(account, ['displayName', 'display_name'], '当前用户'),
      employeeId: text(employee, ['id'], '') || undefined,
      employeeName: text(employee, ['name'], '') || undefined,
      primaryRoleCode: text(raw, ['primaryRole', 'primaryRoleCode', 'primary_role_code', 'roleCode'], identity.roleCode),
      roleCodes: asList<string>(value(raw, 'roles', 'roleCodes')),
      permissions: asList<string>(raw.permissions),
      tenantScope: Boolean(value(raw, 'tenantScope', 'tenant_scope')),
      orgScopes: asList<string>(value(raw, 'organizationScopes', 'orgScopes')),
      assignments: assignments.map((item) => {
        const rawPermissionCodes = value(item, 'permissionCodes', 'permission_codes')
        return {
          id: text(item, ['id']), orgUnitId: text(item, ['organizationId', 'orgUnitId', 'org_unit_id']),
          orgName: text(item, ['organizationName', 'orgName', 'org_name']), positionId: text(item, ['positionId', 'position_id']),
          positionCode: text(item, ['positionCode', 'position_code']), positionName: text(item, ['positionName', 'position_name']),
          primary: Boolean(value(item, 'primary', 'isPrimary', 'is_primary')),
          assignmentType: text(item, ['assignmentType', 'assignment_type'], 'PERMANENT'),
          ...(rawPermissionCodes === undefined ? {} : { permissionCodes: asList<string>(rawPermissionCodes) }),
          authorizationScopeType: text(item, ['authorizationScopeType', 'authorization_scope_type'], '') as IdentitySnapshot['assignments'][number]['authorizationScopeType'] || undefined,
        }
      }),
      businessActorAssignmentId: text(raw, ['businessActorAssignmentId'], '') || undefined,
      capabilities: {
        groupManagement: {
          workPlansEnabled: Boolean(groupManagement.workPlansEnabled),
          executiveTasksEnabled: Boolean(groupManagement.executiveTasksEnabled),
        },
      },
    }
    return snapshot
  }, fallback)
}

type DemoExport =
  | 'demoWorkPackages'
  | 'demoExpectations'
  | 'demoRules'
  | 'demoTasks'
  | 'demoEvaluations'
  | 'demoNotifications'
  | 'demoHotelDashboard'
  | 'demoOperationsDashboard'

async function demoValue<T>(name: DemoExport): Promise<T> {
  const module = await import('../data/demo')
  return module[name] as T
}

async function withFallback<T>(request: () => Promise<T>, fallback: T | (() => Promise<T>)): Promise<{ data: T; source: ApiSource }> {
  if (demoOnlyEnabled) {
    return { data: typeof fallback === 'function' ? await (fallback as () => Promise<T>)() : fallback, source: 'demo' }
  }
  try {
    return { data: await request(), source: 'api' }
  } catch (error) {
    if (!demoFallbackEnabled) throw error
    return { data: typeof fallback === 'function' ? await (fallback as () => Promise<T>)() : fallback, source: 'demo' }
  }
}

export async function loadWorkPackages(identity: ApiIdentity) {
  return withFallback(async () => {
    const payload = await apiRequest<unknown>('/work-packages?page=0&size=100', identity)
    return asList<JsonObject>(payload).map((item): WorkPackage => ({
      id: text(item, ['id']), code: text(item, ['code']), name: text(item, ['name', 'title']),
      positionName: text(item, ['positionName', 'position_name', 'targetPositionName'], '全部岗位'),
      versionNo: number(item, ['versionNo', 'version_no', 'currentVersionNo'], 1),
      lifecycleStatus: text(item, ['lifecycleStatus', 'lifecycle_status', 'status'], 'DRAFT'),
      scopeName: text(item, ['scopeName', 'scope_name', 'applicableScope', 'owner_org_unit_name'], '租户范围'),
      completionRate: value(item, 'completionRate', 'completion_rate') === undefined ? undefined : number(item, ['completionRate', 'completion_rate']),
      itemCount: value(item, 'itemCount', 'item_count') === undefined ? undefined : number(item, ['itemCount', 'item_count']),
      updatedAt: text(item, ['updatedAt', 'updated_at'], ''),
    }))
  }, () => demoValue<WorkPackage[]>('demoWorkPackages'))
}

function normalizeExpectation(item: JsonObject): WorkExpectation {
  const standards = asList<JsonObject>(item.standards).map((standard): WorkStandardReference => ({
    standardVersionId: text(standard, ['standardVersionId', 'standard_version_id']),
    usageType: text(standard, ['usageType', 'usage_type'], 'EXECUTION'),
    standardCode: text(standard, ['standardCode', 'standard_code']),
    title: text(standard, ['title', 'standardTitle', 'standard_title'], '已发布标准'),
    versionNo: number(standard, ['standardVersionNo', 'standard_version_no', 'versionNo'], 1),
  }))
  const records = asList<JsonObject>(item.records).map((record): WorkRecordSummary => ({
    id: text(record, ['id']),
    status: text(record, ['status'], 'SUBMITTED'),
    attemptNo: number(record, ['attemptNo', 'attempt_no'], 1),
    seriesEntryNo: number(record, ['seriesEntryNo', 'series_entry_no'], 1),
    repeatableEntry: value(record, 'repeatableEntry', 'repeatable_entry') === true,
    submittedAt: text(record, ['submittedAt', 'submitted_at'], '') || undefined,
    reviewedAt: text(record, ['reviewedAt', 'reviewed_at'], '') || undefined,
    reviewReason: text(record, ['reviewReason', 'review_reason'], '') || undefined,
  }))
  return {
    id: text(item, ['id']),
    title: text(item, ['title', 'expectationTitle', 'workItemName', 'itemName', 'item_name'], '岗位工作'),
    packageName: text(item, ['packageName', 'workPackageName', 'work_package_name'], '工作包'),
    itemName: text(item, ['itemName', 'workItemName', 'item_name', 'work_item_name'], '工作项'),
    status: text(item, ['status'], 'PENDING'),
    businessDate: text(item, ['businessDate', 'business_date'], ''),
    periodKey: text(item, ['periodKey', 'period_key'], '') || undefined,
    periodType: text(item, ['periodType', 'period_type'], '') || undefined,
    availableAt: text(item, ['availableAt', 'available_at'], '') || undefined,
    dueAt: text(item, ['dueAt', 'due_at'], '') || undefined,
    targetOrgName: text(item, ['targetOrgName', 'targetOrgUnitName', 'orgUnitName', 'target_org_name', 'target_org_unit_name'], '当前组织'),
    hotelOrgUnitId: text(item, ['hotelOrgUnitId', 'hotel_org_unit_id'], '') || undefined,
    hotelName: text(item, ['hotelName', 'hotel_name'], '') || undefined,
    departmentName: text(item, ['departmentName', 'department_name'], '') || undefined,
    assigneeName: text(item, ['assigneeName', 'employeeName', 'employee_name', 'assignee_name'], '当前负责人'),
    positionName: text(item, ['positionName', 'position_name'], '') || undefined,
    positionJobFamily: text(item, ['positionJobFamily', 'position_job_family'], '') || undefined,
    assignmentId: text(item, ['assignmentId', 'employeePositionAssignmentId', 'positionAssignmentId', 'assignment_id', 'position_assignment_id'], '') || undefined,
    orgUnitId: text(item, ['orgUnitId', 'targetOrgUnitId', 'org_unit_id', 'target_org_unit_id'], '') || undefined,
    employeeId: text(item, ['employeeId', 'employee_id'], '') || undefined,
    formVersionId: text(item, ['formVersionId', 'form_version_id'], '') || undefined,
    formCode: text(item, ['formCode', 'form_code'], '') || undefined,
    formName: text(item, ['formName', 'form_name'], '') || undefined,
    formSchema: (() => {
      const raw = jsonColumn(value(item, 'formSchema', 'form_schema'))
      return raw && typeof raw === 'object' ? raw as WorkExpectation['formSchema'] : undefined
    })(),
    workPackageVersionId: text(item, ['workPackageVersionId', 'work_package_version_id'], '') || undefined,
    workPackageItemId: text(item, ['workPackageItemId', 'work_package_item_id'], '') || undefined,
    itemCode: text(item, ['itemCode', 'item_code'], '') || undefined,
    recordId: text(item, ['recordId', 'workRecordId', 'record_id'], '') || records[0]?.id,
    firstSubmittedAt: text(item, ['firstSubmittedAt', 'first_submitted_at'], '') || records.at(-1)?.submittedAt,
    latestSubmittedAt: text(item, ['latestSubmittedAt', 'latest_submitted_at'], '') || records[0]?.submittedAt,
    rowVersion: value(item, 'rowVersion', 'row_version') === undefined ? undefined : number(item, ['rowVersion', 'row_version']),
    evaluationOutcome: text(item, ['evaluationOutcome', 'evaluation_outcome'], '') || undefined,
    standards,
    records,
    submissionPolicy: (() => {
      const raw = object(jsonColumn(value(item, 'submissionPolicy', 'submission_policy')))
      return {
        completionStatementRequired: raw.completionStatementRequired !== false,
        inventoryUsage: raw.inventoryUsage === true,
        exceptionStatementRequired: raw.exceptionStatementRequired === true,
        nextActionRequired: raw.nextActionRequired === true,
        attachmentRequired: raw.attachmentRequired === true,
        maxAttachments: Number(raw.maxAttachments ?? 10),
        attachmentCountUnlimited: raw.attachmentCountUnlimited === true,
        maxFileSizeBytes: Number(raw.maxFileSizeBytes ?? 20 * 1024 * 1024),
        allowedExtensions: asList<string>(raw.allowedExtensions).length
          ? asList<string>(raw.allowedExtensions)
          : ['jpg', 'jpeg', 'png', 'pdf', 'docx', 'xlsx'],
        evidenceRequirements: asList<JsonObject>(raw.evidenceRequirements).map((requirement) => ({
          checkpointCode: text(requirement, ['checkpointCode', 'checkpoint_code']),
          label: text(requirement, ['label'], '现场证据'),
          captureSource: text(requirement, ['captureSource', 'capture_source'], '') as 'CAMERA' | 'FILE_PICKER' || undefined,
          mediaTypes: asList<string>(requirement.mediaTypes),
          allowedKinds: asList<string>(requirement.allowedKinds) as Array<'IMAGE' | 'DOCUMENT' | 'PDF' | 'WORD' | 'EXCEL'>,
          minimum: value(requirement, 'minimum') === undefined ? undefined : number(requirement, ['minimum']),
          recommendedMaximum: value(requirement, 'recommendedMaximum') === undefined ? undefined : number(requirement, ['recommendedMaximum']),
          minimumPerHotelFloor: value(requirement, 'minimumPerHotelFloor') === undefined ? undefined : number(requirement, ['minimumPerHotelFloor']),
          recommendedMaximumPerHotelFloor: value(requirement, 'recommendedMaximumPerHotelFloor') === undefined ? undefined : number(requirement, ['recommendedMaximumPerHotelFloor']),
          requiredInstances: value(requirement, 'requiredInstances') === undefined ? undefined : number(requirement, ['requiredInstances']),
          instanceField: text(requirement, ['instanceField'], '') || undefined,
          instanceLabel: text(requirement, ['instanceLabel'], '') || undefined,
          minimumPerInstance: value(requirement, 'minimumPerInstance') === undefined ? undefined : number(requirement, ['minimumPerInstance']),
          minimumPerConfiguredFacility: value(requirement, 'minimumPerConfiguredFacility') === undefined ? undefined : number(requirement, ['minimumPerConfiguredFacility']),
          recommendedMaximumPerConfiguredFacility: value(requirement, 'recommendedMaximumPerConfiguredFacility') === undefined ? undefined : number(requirement, ['recommendedMaximumPerConfiguredFacility']),
          minimumPerConfiguredAsset: value(requirement, 'minimumPerConfiguredAsset') === undefined ? undefined : number(requirement, ['minimumPerConfiguredAsset']),
          configuredFacilityInstanceField: text(requirement, ['configuredFacilityInstanceField'], '') || undefined,
          configuredAssetInstanceField: text(requirement, ['configuredAssetInstanceField'], '') || undefined,
          conditionalFacility: text(requirement, ['conditionalFacility'], '') || undefined,
          unlimited: requirement.unlimited === true,
          allowReference: requirement.allowReference === true,
          requiredWhen: (() => {
            const condition = object(requirement.requiredWhen)
            const field = text(condition, ['field'], '')
            return field ? { field, equals: condition.equals } : undefined
          })(),
        })),
      }
    })(),
    reminderPolicy: object(jsonColumn(value(item, 'reminderPolicy', 'reminder_policy'))),
    reportPolicy: object(jsonColumn(value(item, 'reportPolicy', 'report_policy'))),
    applicabilityPolicy: object(jsonColumn(value(item, 'applicabilityPolicy', 'applicability_policy'))),
    executionPolicy: object(jsonColumn(value(item, 'executionPolicy', 'execution_policy'))) as WorkExpectation['executionPolicy'],
    guestRoomFloorCount: value(item, 'guestRoomFloorCount', 'guest_room_floor_count') === undefined ? undefined : number(item, ['guestRoomFloorCount', 'guest_room_floor_count']),
    operationalFeatureFlags: Object.fromEntries(
      Object.entries(object(jsonColumn(value(item, 'operationalFeatureFlags', 'operational_feature_flags'))))
        .filter((entry): entry is [string, boolean] => typeof entry[1] === 'boolean'),
    ),
    evidenceInstanceConfiguration: (() => {
      const configured = object(jsonColumn(value(
        item, 'evidenceInstanceConfiguration', 'evidence_instance_configuration')))
      return {
        facilityInstances: stringArrayMap(configured.facilityInstances),
        assetInstances: stringArrayMap(configured.assetInstances),
      }
    })(),
    delegateAssignmentId: text(item, ['delegateAssignmentId', 'delegate_assignment_id'], '') || undefined,
    delegatedEmployeeName: text(item, ['delegatedEmployeeName', 'delegated_employee_name'], '') || undefined,
    ownerResting: value(item, 'ownerResting', 'owner_resting') === true,
  }
}

export async function loadDelegationCandidates(identity: ApiIdentity, expectationId: string) {
  const payload = await apiRequest<unknown>(`/work-expectations/${expectationId}/delegation-candidates`, identity)
  return asList<JsonObject>(payload).map((item) => ({
    assignmentId: text(item, ['assignmentId', 'assignment_id']),
    employeeName: text(item, ['employeeName', 'employee_name']),
    positionName: text(item, ['positionName', 'position_name']),
  }))
}

export async function delegateExpectation(identity: ApiIdentity, expectationId: string, input: {
  delegateAssignmentId: string; ownerResting: boolean; reason?: string; expectedVersion: number
}) {
  return apiRequest<{
    id: string
    delegationId?: string
    delegateAssignmentId: string
    ownerResting: boolean
    transferredCount: number
    transferredIds: string[]
    rowVersion: number
  }>(`/work-expectations/${expectationId}/delegate`, identity, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(input),
  })
}

export async function revokeExpectationDelegation(identity: ApiIdentity, expectationId: string, expectedVersion: number) {
  return apiRequest(`/work-expectations/${expectationId}/delegation?expectedVersion=${expectedVersion}`, identity, { method: 'DELETE' })
}

export async function loadExpectation(identity: ApiIdentity, id: string, fallback: WorkExpectation) {
  return withFallback(async () => normalizeExpectation(object(await apiRequest<unknown>(`/work-expectations/${id}`, identity))), fallback)
}

type WorkExpectationQuery = {
  orgUnitId?: string
  businessDate?: string
  fromBusinessDate?: string
  toBusinessDate?: string
}

async function loadExpectationPages(identity: ApiIdentity, endpoint: string, options: WorkExpectationQuery) {
  const pageSize = 200
  const maxPages = 100
  const result: WorkExpectation[] = []
  const ids = new Set<string>()
  for (let page = 0; page < maxPages; page += 1) {
    const query = new URLSearchParams({ page: String(page), size: String(pageSize) })
    if (options.orgUnitId) query.set('targetOrgUnitId', options.orgUnitId)
    if (options.businessDate) query.set('businessDate', options.businessDate)
    if (options.fromBusinessDate) query.set('fromBusinessDate', options.fromBusinessDate)
    if (options.toBusinessDate) query.set('toBusinessDate', options.toBusinessDate)
    const payload = await apiRequest<unknown>(`${endpoint}?${query.toString()}`, identity)
    const rows = asList<JsonObject>(payload).map(normalizeExpectation)
    let added = 0
    rows.forEach((row) => {
      if (!row.id || ids.has(row.id)) return
      ids.add(row.id)
      result.push(row)
      added += 1
    })
    if (rows.length < pageSize || added === 0) return result
  }
  throw new Error('工作记录超过20000条，请缩小日期或组织范围后重试。')
}

export async function loadMyWork(identity: ApiIdentity, options: Omit<WorkExpectationQuery, 'orgUnitId'> = {}) {
  return withFallback(async () => {
    return loadExpectationPages(identity, '/my/work-expectations', options)
  }, () => demoValue<WorkExpectation[]>('demoExpectations'))
}

export async function loadTeamWork(identity: ApiIdentity, options: WorkExpectationQuery = {}) {
  return withFallback(async () => {
    const endpoint = options.orgUnitId ? '/work-expectations' : '/team/work-expectations'
    return loadExpectationPages(identity, endpoint, options)
  }, () => demoValue<WorkExpectation[]>('demoExpectations'))
}

function normalizeCompletionMetric(payload: unknown): WorkCompletionMetric {
  const item = object(payload)
  return {
    expected: number(item, ['expected']),
    completed: number(item, ['completed']),
    onTimeCompleted: number(item, ['onTimeCompleted', 'on_time_completed']),
    lateSubmitted: number(item, ['lateSubmitted', 'late_submitted']),
    pending: number(item, ['pending']),
    overdue: number(item, ['overdue']),
    completionRate: number(item, ['completionRate', 'completion_rate']),
  }
}

function demoWorkbenchSummary(): WorkbenchSummary {
  const asOfDate = new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(new Date())
  const metric = (expected: number, onTime: number, late: number, pending: number, overdue: number): WorkCompletionMetric => ({
    expected,
    completed: onTime + late,
    onTimeCompleted: onTime,
    lateSubmitted: late,
    pending,
    overdue,
    completionRate: expected ? Math.round(onTime * 100 / expected) : 0,
  })
  return {
    asOfDate,
    monthStart: `${asOfDate.slice(0, 8)}01`,
    today: metric(5, 2, 1, 1, 1),
    monthToDate: metric(36, 29, 2, 2, 3),
    hotels: [
      { id: '12000000-0000-0000-0000-000000000003', name: '杭州中心店', today: metric(4, 2, 1, 0, 1), monthToDate: metric(24, 20, 1, 1, 2), departments: [] },
      { id: '12000000-0000-0000-0000-000000000004', name: '上海滨江店', today: metric(1, 0, 0, 1, 0), monthToDate: metric(12, 9, 1, 1, 1), departments: [] },
    ],
  }
}

function normalizeWorkbenchOrganizations(payload: unknown, fallbackName: string) {
  return asList<JsonObject>(payload).map((hotel) => ({
    id: text(hotel, ['id']),
    name: text(hotel, ['name'], fallbackName),
    today: normalizeCompletionMetric(hotel.today),
    monthToDate: normalizeCompletionMetric(value(hotel, 'monthToDate', 'month_to_date')),
    departments: asList<JsonObject>(hotel.departments).map((department) => ({
      id: text(department, ['id']),
      name: text(department, ['name'], '未归属部门'),
      today: normalizeCompletionMetric(department.today),
      monthToDate: normalizeCompletionMetric(value(department, 'monthToDate', 'month_to_date')),
      employees: asList<JsonObject>(department.employees).map((employee) => ({
        id: text(employee, ['id']),
        assignmentId: text(employee, ['assignmentId', 'assignment_id']),
        employeeId: text(employee, ['employeeId', 'employee_id']),
        name: text(employee, ['name'], '未命名员工'),
        positionName: text(employee, ['positionName', 'position_name']),
        today: normalizeCompletionMetric(employee.today),
        monthToDate: normalizeCompletionMetric(value(employee, 'monthToDate', 'month_to_date')),
      })),
    })),
  }))
}

export async function loadWorkbenchSummary(identity: ApiIdentity, date?: string) {
  return withFallback(async () => {
    const query = date ? `?businessDate=${encodeURIComponent(date)}` : ''
    const payload = object(await apiRequest<unknown>(`/team/workbench-summary${query}`, identity))
    return {
      asOfDate: text(payload, ['asOfDate', 'as_of_date'], ''),
      monthStart: text(payload, ['monthStart', 'month_start'], ''),
      today: normalizeCompletionMetric(payload.today),
      monthToDate: normalizeCompletionMetric(value(payload, 'monthToDate', 'month_to_date')),
      hotels: normalizeWorkbenchOrganizations(payload.hotels, '授权门店'),
      organizations: normalizeWorkbenchOrganizations(payload.organizations, '集团及总部'),
    } satisfies WorkbenchSummary
  }, async () => demoWorkbenchSummary())
}

function normalizeAttachment(item: JsonObject): WorkRecordAttachment {
  const rawOrientation = text(item, ['displayOrientation', 'display_orientation'], '')
  const displayOrientation = ['LANDSCAPE', 'PORTRAIT', 'SQUARE'].includes(rawOrientation)
    ? rawOrientation as WorkRecordAttachment['displayOrientation']
    : undefined
  const normalizedWidth = number(item, ['normalizedWidth', 'normalized_width'])
  const normalizedHeight = number(item, ['normalizedHeight', 'normalized_height'])
  return {
    id: text(item, ['id']),
    objectKey: text(item, ['objectKey', 'object_key'], '') || undefined,
    originalName: text(item, ['originalName', 'original_name'], '现场图片'),
    mediaType: text(item, ['mediaType', 'media_type'], 'application/octet-stream'),
    sizeBytes: number(item, ['sizeBytes', 'size_bytes'], 0),
    sha256: text(item, ['sha256'], '') || undefined,
    scanStatus: text(item, ['scanStatus', 'scan_status'], 'PENDING'),
    captureSource: text(item, ['captureSource', 'capture_source'], '') || undefined,
    checkpointCode: text(item, ['checkpointCode', 'checkpoint_code'], '') || undefined,
    evidenceInstanceKey: text(item, ['evidenceInstanceKey', 'evidence_instance_key'], '') || undefined,
    capturedAtClient: text(item, ['capturedAtClient', 'captured_at_client'], '') || undefined,
    receivedAt: text(item, ['receivedAt', 'received_at'], '') || undefined,
    sourceSha256: text(item, ['sourceSha256', 'source_sha256'], '') || undefined,
    displayOrientation,
    normalizedWidth: normalizedWidth > 0 ? normalizedWidth : undefined,
    normalizedHeight: normalizedHeight > 0 ? normalizedHeight : undefined,
    createdAt: text(item, ['createdAt', 'created_at'], '') || undefined,
  }
}

function normalizeWorkRecord(item: JsonObject): WorkRecordDetail {
  const rawPayload = jsonColumn(value(item, 'payload'))
  return {
    id: text(item, ['id']),
    status: text(item, ['status'], 'SUBMITTED'),
    rowVersion: number(item, ['rowVersion', 'row_version'], 0),
    businessDate: text(item, ['businessDate', 'business_date'], '') || undefined,
    payload: rawPayload && typeof rawPayload === 'object' ? rawPayload as Record<string, unknown> : {},
    inventoryUsage: object(item.inventoryUsage).status ? camelizeKeysDeep<UsageDetail>(item.inventoryUsage) : undefined,
    employeeName: text(item, ['employeeName', 'employee_name'], '提交员工'),
    positionName: text(item, ['positionName', 'position_name'], '执行岗位'),
    formName: text(item, ['formName', 'form_name'], '岗位工作记录'),
    targetOrgName: text(item, ['targetOrgUnitName', 'target_org_unit_name', 'targetOrgName'], '当前组织'),
    targetOrgUnitId: text(item, ['targetOrgUnitId', 'target_org_unit_id'], '') || undefined,
    positionAssignmentId: text(item, ['positionAssignmentId', 'position_assignment_id'], '') || undefined,
    workExpectationId: text(item, ['workExpectationId', 'work_expectation_id'], '') || undefined,
    completionStatement: text(item, ['completionStatement', 'completion_statement'], '') || undefined,
    exceptionStatement: text(item, ['exceptionStatement', 'exception_statement'], '') || undefined,
    nextAction: text(item, ['nextAction', 'next_action'], '') || undefined,
    reviewReason: text(item, ['reviewReason', 'review_reason'], '') || undefined,
    submittedAt: text(item, ['submittedAt', 'submitted_at'], '') || undefined,
    reviewedAt: text(item, ['reviewedAt', 'reviewed_at'], '') || undefined,
    attachments: asList<JsonObject>(item.attachments).map(normalizeAttachment),
    supplements: asList<JsonObject>(item.supplements).map((supplement) => ({
      id: text(supplement, ['id']),
      submittedByAssignmentId: text(supplement, ['submittedByAssignmentId', 'submitted_by_assignment_id']),
      submittedByName: text(supplement, ['submittedByName', 'submitted_by_name'], '提交人'),
      content: text(supplement, ['content']),
      createdAt: text(supplement, ['createdAt', 'created_at'], '') || undefined,
    })),
  }
}

function demoRecord(expectation: WorkExpectation): WorkRecordDetail | undefined {
  if (!['SUBMITTED', 'COMPLETED', 'SATISFIED', 'FAILED'].includes(expectation.status) && !expectation.recordId) return undefined
  return {
    id: expectation.recordId ?? `record-${expectation.id}`,
    status: expectation.status === 'COMPLETED' ? 'APPROVED' : 'SUBMITTED',
    rowVersion: 0,
    businessDate: expectation.businessDate,
    payload: { summary: `${expectation.itemName}已完成现场记录`, issues: expectation.evaluationOutcome === 'FAIL' ? 2 : 0 },
    employeeName: expectation.assigneeName,
    positionName: '当前岗位',
    formName: expectation.packageName,
    targetOrgName: expectation.targetOrgName,
    targetOrgUnitId: expectation.orgUnitId,
    positionAssignmentId: expectation.assignmentId,
    workExpectationId: expectation.id,
    completionStatement: '已完成现场记录',
    submittedAt: new Date().toISOString(),
    attachments: [{
      id: `attachment-${expectation.id}`,
      originalName: '现场检查照片.jpg',
      mediaType: 'image/jpeg',
      sizeBytes: 842_116,
      scanStatus: 'CLEAN',
      createdAt: new Date().toISOString(),
    }],
    supplements: [],
  }
}

export async function createWorkRecordDraft(identity: ApiIdentity, input: Record<string, unknown>) {
  return apiRequest<Record<string, unknown>>('/work-data/records', identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ ...input, saveAsDraft: true }),
  })
}

export async function submitWorkRecordDraft(identity: ApiIdentity, recordId: string, expectedVersion: number) {
  return apiRequest(`/work-data/records/${recordId}/actions/submit`, identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ expectedVersion }),
  })
}

export async function updateWorkRecordDraft(identity: ApiIdentity, recordId: string, input: {
  payload: Record<string, unknown>; completionStatement: string; exceptionStatement?: string
  nextAction?: string; expectedVersion: number
}) {
  return apiRequest<Record<string, unknown>>(`/work-data/records/${recordId}`, identity, {
    method: 'PUT',
    body: JSON.stringify({ ...input, occurredAt: new Date().toISOString() }),
  })
}

export async function addWorkRecordSupplement(identity: ApiIdentity, recordId: string, assignmentId: string, content: string) {
  return apiRequest(`/work-data/records/${recordId}/supplements`, identity, {
    method: 'POST',
    body: JSON.stringify({ submittedByAssignmentId: assignmentId, content }),
  })
}

export async function loadWorkRecord(identity: ApiIdentity, recordId: string, fallback?: WorkRecordDetail) {
  return withFallback(async () => {
    const record = normalizeWorkRecord(object(await apiRequest<unknown>(`/work-data/records/${recordId}`, identity)))
    const attachments = await apiRequest<unknown>(`/work-data/records/${recordId}/attachments`, identity).catch(() => record.attachments)
    record.attachments = asList<JsonObject>(attachments).map(normalizeAttachment)
    return record
  }, fallback ?? normalizeWorkRecord({ id: recordId }))
}

export async function loadTeamWorkCase(identity: ApiIdentity, initial: WorkExpectation, requestedRecordId?: string) {
  return withFallback<TeamWorkCase>(async () => {
    const expectation = normalizeExpectation(object(await apiRequest<unknown>(`/work-expectations/${initial.id}`, identity)))
    const recordId = requestedRecordId ?? expectation.recordId ?? expectation.records?.[0]?.id
    const record = recordId ? normalizeWorkRecord(object(await apiRequest<unknown>(`/work-data/records/${recordId}`, identity))) : undefined
    if (requestedRecordId && record?.workExpectationId !== expectation.id) throw new Error('所选记录不属于此工作，请刷新后重试')
    if (record) {
      const attachments = await apiRequest<unknown>(`/work-data/records/${record.id}/attachments`, identity).catch(() => record.attachments)
      record.attachments = asList<JsonObject>(attachments).map(normalizeAttachment)
    }
    return { expectation, record }
  }, { expectation: initial, record: demoRecord(initial) })
}

export async function reviewWorkRecord(identity: ApiIdentity, recordId: string, outcome: 'APPROVED' | 'REJECTED', reason: string, expectedVersion: number) {
  return apiRequest(`/work-data/records/${recordId}/actions/review`, identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ outcome, reason: reason || null, expectedVersion }),
  })
}

export async function uploadWorkRecordAttachment(
  identity: ApiIdentity,
  recordId: string,
  file: File,
  metadata: { captureSource?: 'CAMERA' | 'FILE_PICKER'; checkpointCode?: string; evidenceInstanceKey?: string; capturedAtClient?: string } = {},
) {
  const uploadFile = await materializeFileForUpload(file)
  const form = new FormData()
  form.append('file', uploadFile, uploadFile.name)
  const query = new URLSearchParams()
  if (metadata.captureSource) query.set('captureSource', metadata.captureSource)
  if (metadata.checkpointCode) query.set('checkpointCode', metadata.checkpointCode)
  if (metadata.evidenceInstanceKey) query.set('evidenceInstanceKey', metadata.evidenceInstanceKey)
  if (metadata.capturedAtClient) query.set('capturedAtClient', metadata.capturedAtClient)
  const suffix = query.size ? `?${query.toString()}` : ''
  try {
    return await apiRequest(`/work-data/records/${recordId}/attachments/upload${suffix}`, identity, {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: form,
    })
  } catch (reason) {
    throw readableUploadError(reason, file.name)
  }
}

export async function deleteWorkRecordAttachment(identity: ApiIdentity, recordId: string, attachmentId: string) {
  return apiRequest(`/work-data/records/${recordId}/attachments/${attachmentId}`, identity, { method: 'DELETE' })
}

export async function loadAttachmentContent(identity: ApiIdentity, attachmentId: string) {
  return apiBlob(`/work-data/attachments/${attachmentId}/content`, identity)
}

export async function createCorrectiveTask(identity: ApiIdentity, input: {
  orgUnitId: string
  assigneeAssignmentId: string
  reviewerAssignmentId?: string
  creatorAssignmentId?: string
  standardVersionId?: string
  workRecordId: string
  title: string
  description: string
  priority: string
  dueAt?: string
}) {
  return apiRequest('/tasks', identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      ...input,
      creatorAssignmentId: input.creatorAssignmentId ?? identity.businessActorAssignmentId,
      dispatchNow: true,
      sourceSnapshot: { source: 'TEAM_WORK_REVIEW', workRecordId: input.workRecordId },
    }),
  })
}

export async function createWorkRecordEvaluation(identity: ApiIdentity, input: {
  record: WorkRecordDetail
  standardVersionId: string
}) {
  if (!input.record.targetOrgUnitId) throw new Error('工作记录缺少目标组织，不能创建标准评价。')
  return apiRequest('/standard-evaluations', identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      subjectType: 'WORK_RECORD',
      subjectId: input.record.id,
      orgUnitId: input.record.targetOrgUnitId,
      positionAssignmentId: input.record.positionAssignmentId,
      standardVersionId: input.standardVersionId,
      inputSnapshot: input.record.payload,
    }),
  })
}

export async function loadRules(identity: ApiIdentity) {
  return withFallback(async () => {
    const payload = await apiRequest<unknown>('/rules?page=0&size=100', identity)
    return asList<JsonObject>(payload).map((item): ManagementRule => ({
      id: text(item, ['id']), code: text(item, ['code']), name: text(item, ['name', 'title']),
      status: text(item, ['lifecycleStatus', 'lifecycle_status', 'status'], 'DRAFT'),
      versionNo: number(item, ['versionNo', 'version_no', 'currentVersionNo'], 1),
      eventType: text(item, ['eventType', 'event_type', 'triggerEventType'], '—'),
      scopeName: text(item, ['scopeName', 'applicableScope'], '租户范围'),
      hitCount: value(item, 'hitCount') === undefined ? undefined : number(item, ['hitCount']),
      latestVersionId: text(item, ['latestVersionId', 'latest_version_id'], '') || undefined,
      rowVersion: value(item, 'rowVersion', 'row_version') === undefined ? undefined : number(item, ['rowVersion', 'row_version']),
    }))
  }, () => demoValue<ManagementRule[]>('demoRules'))
}

function ruleScope(item: JsonObject): RuleScope {
  return {
    scopeType: text(item, ['scopeType', 'scope_type'], 'TENANT'),
    brandId: text(item, ['brandId', 'brand_id'], '') || undefined,
    orgUnitId: text(item, ['orgUnitId', 'org_unit_id'], '') || undefined,
    positionId: text(item, ['positionId', 'position_id'], '') || undefined,
  }
}

export async function loadRuleDetail(identity: ApiIdentity, ruleId: string): Promise<RuleDetail> {
  const raw = object(await apiRequest<unknown>(`/rules/${ruleId}`, identity))
  const scopes = asList<JsonObject>(raw.scopes)
  const scopesByVersion = new Map<string, RuleScope[]>()
  for (const item of scopes) {
    const versionId = text(item, ['ruleVersionId', 'rule_version_id'], '')
    if (!versionId) continue
    scopesByVersion.set(versionId, [...(scopesByVersion.get(versionId) ?? []), ruleScope(item)])
  }
  const versions = asList<JsonObject>(raw.versions).map((item): RuleVersionDetail => {
    const id = text(item, ['id'])
    const conditionValue = jsonColumn(value(item, 'conditionAst', 'condition_ast'))
    const actionValue = jsonColumn(value(item, 'actions'))
    return {
      id,
      versionNo: number(item, ['versionNo', 'version_no'], 1),
      lifecycleStatus: text(item, ['lifecycleStatus', 'lifecycle_status'], 'DRAFT'),
      conditionAst: object(conditionValue),
      actions: asList<Record<string, unknown>>(actionValue),
      priority: number(item, ['priority'], 100),
      cooldownMinutes: number(item, ['cooldownMinutes', 'cooldown_minutes'], 0),
      rowVersion: number(item, ['rowVersion', 'row_version'], 0),
      effectiveFrom: text(item, ['effectiveFrom', 'effective_from'], '') || undefined,
      effectiveTo: text(item, ['effectiveTo', 'effective_to'], '') || undefined,
      scopes: scopesByVersion.get(id) ?? [{ scopeType: 'TENANT' }],
    }
  })
  return {
    id: text(raw, ['id']), code: text(raw, ['code']), name: text(raw, ['name']),
    eventType: text(raw, ['eventType', 'event_type']), description: text(raw, ['description'], '') || undefined,
    status: text(raw, ['status'], 'ACTIVE'), versions,
  }
}

export async function createRuleWithVersion(identity: ApiIdentity, definition: {
  code: string
  name: string
  eventType: string
  description?: string
}, version: RuleVersionDraft): Promise<string> {
  const created = object(await apiRequest<unknown>('/rules', identity, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(definition),
  }))
  const ruleId = text(created, ['id'], '')
  if (!ruleId) throw new Error('规则已创建，但服务端未返回规则ID。')
  await apiRequest(`/rules/${ruleId}/versions`, identity, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(version),
  })
  return ruleId
}

export async function saveRuleVersion(identity: ApiIdentity, ruleId: string, current: RuleVersionDetail, version: RuleVersionDraft) {
  if (current.lifecycleStatus === 'DRAFT') {
    return apiRequest(`/rules/${ruleId}/versions/${current.id}`, identity, {
      method: 'PUT', body: JSON.stringify({ ...version, expectedVersion: current.rowVersion }),
    })
  }
  return apiRequest(`/rules/${ruleId}/versions`, identity, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(version),
  })
}

export async function publishRuleVersion(identity: ApiIdentity, ruleId: string, version: RuleVersionDetail) {
  return apiRequest(`/rules/${ruleId}/versions/${version.id}/publish`, identity, {
    method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({ expectedVersion: version.rowVersion, effectiveFrom: new Date().toISOString() }),
  })
}

function normalizeTimeline(payload: unknown): TaskTimelineItem[] {
  return asList<JsonObject>(payload).map((item): TaskTimelineItem => ({
    id: text(item, ['id']), command: text(item, ['command', 'action']),
    fromStatus: text(item, ['fromStatus', 'from_status'], '') || undefined,
    toStatus: text(item, ['toStatus', 'to_status'], '—'), actorName: text(item, ['actorName', 'actor_name'], '系统'),
    occurredAt: text(item, ['occurredAt', 'occurred_at', 'createdAt'], ''),
    remark: text(item, ['remark', 'reason'], '') || undefined,
  }))
}

function normalizeTask(item: JsonObject): ManagementTask {
  const participants = asList<JsonObject>(item.participants)
  const participantName = (kind: string) => {
    const participant = participants.find((row) => text(row, ['participantType', 'participant_type'], '') === kind)
    const employee = object(jsonColumn(value(participant ?? {}, 'employeeSnapshot', 'employee_snapshot')))
    return participant ? text(employee, ['name', 'employeeName'], '待解析') : '待解析'
  }
  const participantId = (kind: string) => {
    const participant = participants.find((row) => text(row, ['participantType', 'participant_type'], '') === kind)
    return participant ? text(participant, ['positionAssignmentId', 'position_assignment_id'], '') || undefined : undefined
  }
  const evidence = asList<JsonObject>(item.evidence).map((row): TaskEvidence => ({
    id: text(row, ['id']),
    submittedByAssignmentId: text(row, ['submittedByAssignmentId', 'submitted_by_assignment_id'], '') || undefined,
    evidenceType: text(row, ['evidenceType', 'evidence_type'], 'FILE'),
    objectKey: text(row, ['objectKey', 'object_key'], '') || undefined,
    originalName: text(row, ['originalName', 'original_name'], '') || undefined,
    mediaType: text(row, ['mediaType', 'media_type'], '') || undefined,
    sizeBytes: value(row, 'sizeBytes', 'size_bytes') === undefined ? undefined : number(row, ['sizeBytes', 'size_bytes']),
    scanStatus: text(row, ['scanStatus', 'scan_status'], 'PENDING'),
    createdAt: text(row, ['createdAt', 'created_at'], '') || undefined,
    structuredResult: (() => {
      const raw = jsonColumn(value(row, 'structuredResult', 'structured_result'))
      return raw && typeof raw === 'object' ? raw as Record<string, unknown> : undefined
    })(),
  }))
  return {
    id: text(item, ['id']), code: text(item, ['code', 'taskNo', 'task_no']), title: text(item, ['title', 'name']),
    status: text(item, ['status', 'lifecycleStatus', 'lifecycle_status'], 'PROPOSED'), slaStatus: text(item, ['slaStatus', 'sla_status'], 'ON_TIME'),
    priority: text(item, ['priority'], 'NORMAL'), assigneeName: text(item, ['assigneeName', 'assignee_name', 'responsibleName'], participantName('ASSIGNEE')),
    reviewerName: text(item, ['reviewerName', 'reviewer_name', 'acceptanceOwnerName'], participantName('REVIEWER')),
    assigneeAssignmentId: text(item, ['assigneeAssignmentId', 'assignee_assignment_id'], '') || participantId('ASSIGNEE'),
    reviewerAssignmentId: text(item, ['reviewerAssignmentId', 'reviewer_assignment_id'], '') || participantId('REVIEWER'),
    targetOrgName: text(item, ['targetOrgName', 'orgUnitName', 'org_unit_name', 'org_unit_id'], '当前组织'),
    targetOrgUnitId: text(item, ['targetOrgUnitId', 'orgUnitId', 'org_unit_id'], '') || undefined,
    standardVersionId: text(item, ['standardVersionId', 'standard_version_id'], '') || undefined,
    resultSnapshot: (() => {
      const raw = value(item, 'resultSnapshot', 'result_snapshot')
      return raw && typeof raw === 'object' ? raw as Record<string, unknown> : undefined
    })(),
    sourceSnapshot: (() => {
      const raw = jsonColumn(value(item, 'sourceSnapshot', 'source_snapshot'))
      return raw && typeof raw === 'object' ? raw as Record<string, unknown> : undefined
    })(),
    sourceType: text(item, ['sourceType', 'source_type'], 'MANUAL'),
    creationSource: text(item, ['creationSource', 'creation_source'], '') || undefined,
    sourceTitle: text(item, ['sourceTitle', 'source_title'], '') || undefined,
    description: text(item, ['description', 'requirement'], '') || undefined,
    dueAt: text(item, ['dueAt', 'due_at'], '') || undefined,
    version: number(item, ['version', 'lockVersion', 'row_version'], 0),
    createdByAssignmentId: text(item, ['createdByAssignmentId', 'created_by_assignment_id'], '') || undefined,
    evaluationId: text(item, ['evaluationId', 'standardEvaluationId'], '') || undefined,
    timeline: value(item, 'timeline', 'transitions') ? normalizeTimeline(value(item, 'timeline', 'transitions')) : undefined,
    evidence,
  }
}

export async function createManagementTask(
  identity: ApiIdentity,
  input: Record<string, unknown>,
  idempotencyKey: string = crypto.randomUUID(),
) {
  return apiRequest<Record<string, unknown>>('/tasks', identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': idempotencyKey },
    body: JSON.stringify(input),
  })
}

export async function uploadTaskDispatchAttachment(
  identity: ApiIdentity,
  taskId: string,
  assignmentId: string | undefined,
  note: string,
  file: File,
) {
  const uploadFile = await materializeFileForUpload(file)
  const query = new URLSearchParams()
  if (assignmentId) query.set('submittedByAssignmentId', assignmentId)
  if (note.trim()) query.set('note', note.trim())
  const form = new FormData()
  form.append('file', uploadFile, uploadFile.name)
  try {
    return await apiRequest(`/tasks/${taskId}/dispatch-attachments/upload?${query.toString()}`, identity, {
      method: 'POST', body: form,
    })
  } catch (reason) {
    throw readableUploadError(reason, file.name)
  }
}

export async function dispatchManagementTask(
  identity: ApiIdentity,
  taskId: string,
  actorAssignmentId: string | undefined,
  expectedVersion: number,
  idempotencyKey: string,
) {
  return apiMutation(`/tasks/${taskId}/actions/dispatch`, identity, {
    body: { actorAssignmentId: actorAssignmentId ?? null, payload: { source: 'CREATE_AND_DISPATCH' } },
    expectedVersion,
    idempotencyKey,
  })
}

export async function uploadTaskEvidence(identity: ApiIdentity, taskId: string, assignmentId: string, file: File) {
  const uploadFile = await materializeFileForUpload(file)
  const form = new FormData()
  form.append('file', uploadFile, uploadFile.name)
  try {
    return await apiRequest(`/tasks/${taskId}/evidence/upload?submittedByAssignmentId=${encodeURIComponent(assignmentId)}`, identity, {
      method: 'POST', body: form,
    })
  } catch (reason) {
    throw readableUploadError(reason, file.name)
  }
}

export async function deleteTaskEvidence(identity: ApiIdentity, taskId: string, evidenceId: string, assignmentId: string) {
  return apiRequest(`/tasks/${taskId}/evidence/${evidenceId}?actorAssignmentId=${encodeURIComponent(assignmentId)}`, identity, { method: 'DELETE' })
}

export async function loadTaskEvidenceContent(identity: ApiIdentity, taskId: string, evidenceId: string) {
  return apiBlob(`/tasks/${taskId}/evidence/${evidenceId}/content`, identity)
}

function normalizeEnterpriseTemplate(item: JsonObject): EnterpriseTemplate {
  const configuration = jsonColumn(value(item, 'configuration'))
  const publishedConfiguration = jsonColumn(value(item, 'publishedConfiguration', 'published_configuration'))
  return {
    id: text(item, ['id']),
    templateType: text(item, ['templateType', 'template_type'], 'TASK') as EnterpriseTemplateType,
    code: text(item, ['code']),
    name: text(item, ['name']),
    description: text(item, ['description'], '') || undefined,
    positionName: text(item, ['positionName', 'position_name'], '') || undefined,
    ownerOrgName: text(item, ['ownerOrgUnitName', 'owner_org_unit_name'], '') || undefined,
    latestVersionId: text(item, ['latestVersionId', 'latest_version_id'], '') || undefined,
    versionNo: number(item, ['versionNo', 'version_no'], 0),
    lifecycleStatus: text(item, ['lifecycleStatus', 'lifecycle_status'], 'DRAFT'),
    configuration: configuration && typeof configuration === 'object' ? configuration as Record<string, unknown> : {},
    publishedVersionId: text(item, ['publishedVersionId', 'published_version_id'], '') || undefined,
    publishedVersionNo: value(item, 'publishedVersionNo', 'published_version_no') === undefined ? undefined : number(item, ['publishedVersionNo', 'published_version_no']),
    publishedConfiguration: publishedConfiguration && typeof publishedConfiguration === 'object' ? publishedConfiguration as Record<string, unknown> : undefined,
    rowVersion: number(item, ['rowVersion', 'row_version'], 0),
  }
}

export async function loadEnterpriseTemplates(identity: ApiIdentity, type?: EnterpriseTemplateType) {
  const payload = await apiRequest<unknown>(`/templates${type ? `?type=${type}` : ''}`, identity)
  return asList<JsonObject>(payload).map(normalizeEnterpriseTemplate)
}

export async function createEnterpriseTemplate(identity: ApiIdentity, input: {
  templateType: EnterpriseTemplateType; code: string; name: string; description?: string
  targetPositionId?: string; ownerOrgUnitId?: string; configuration: Record<string, unknown>
}) {
  return apiRequest<Record<string, unknown>>('/templates', identity, { method: 'POST', body: JSON.stringify(input) })
}

export async function saveEnterpriseTemplateVersion(identity: ApiIdentity, template: EnterpriseTemplate, configuration: Record<string, unknown>) {
  if (template.latestVersionId && template.lifecycleStatus === 'DRAFT') {
    return apiRequest(`/templates/${template.id}/versions/${template.latestVersionId}`, identity, {
      method: 'PUT', body: JSON.stringify({ configuration, expectedVersion: template.rowVersion }),
    })
  }
  return apiRequest(`/templates/${template.id}/versions`, identity, { method: 'POST', body: JSON.stringify({ configuration }) })
}

export async function publishEnterpriseTemplate(identity: ApiIdentity, templateId: string, versionId: string) {
  return apiRequest(`/templates/${templateId}/versions/${versionId}/publish`, identity, {
    method: 'POST', body: JSON.stringify({ effectiveFrom: new Date().toISOString() }),
  })
}

export type TaskListQuery = {
  view?: 'mine' | 'team' | 'review' | 'all'
  status?: string
  orgUnitId?: string
}

export async function loadTasks(identity: ApiIdentity, options: TaskListQuery = {}) {
  return withFallback(async () => {
    const query = new URLSearchParams({ view: options.view ?? 'mine', page: '0', size: '100' })
    if (options.status) query.set('status', options.status)
    if (options.orgUnitId) query.set('orgUnitId', options.orgUnitId)
    const payload = await apiRequest<unknown>(`/tasks?${query.toString()}`, identity)
    return asList<JsonObject>(payload).map(normalizeTask)
  }, () => demoValue<ManagementTask[]>('demoTasks'))
}

export async function loadTask(identity: ApiIdentity, id: string, fallback?: ManagementTask) {
  return withFallback(async () => {
    const payload = await apiRequest<unknown>(`/tasks/${id}`, identity)
    const task = normalizeTask(object(payload))
    if (!task.timeline) {
      const timeline = await apiRequest<unknown>(`/tasks/${id}/timeline`, identity).catch(() => [])
      task.timeline = normalizeTimeline(timeline)
    }
    return task
  }, fallback ?? normalizeTask({ id }))
}

export async function createTaskEvaluation(identity: ApiIdentity, task: ManagementTask) {
  if (!task.targetOrgUnitId || !task.standardVersionId) {
    throw new Error('任务缺少目标组织或绑定标准，不能进入验收评价。')
  }
  return apiRequest('/standard-evaluations', identity, {
    method: 'POST',
    headers: { 'Idempotency-Key': crypto.randomUUID() },
    body: JSON.stringify({
      subjectType: 'TASK',
      subjectId: task.id,
      orgUnitId: task.targetOrgUnitId,
      positionAssignmentId: task.assigneeAssignmentId,
      standardVersionId: task.standardVersionId,
      inputSnapshot: task.resultSnapshot ?? {},
    }),
  })
}

function normalizeMetric(item: JsonObject): DashboardMetric {
  return {
    code: text(item, ['code']),
    name: text(item, ['name'], text(item, ['code'])),
    unit: text(item, ['unit'], ''),
    value: number(item, ['value']),
    businessDate: text(item, ['businessDate', 'business_date'], '') || undefined,
  }
}

function normalizeRisk(item: JsonObject, index: number): DashboardRisk {
  const sourceId = text(item, ['sourceId', 'source_id'], '') || undefined
  return {
    id: text(item, ['id'], sourceId ?? `risk-${index}`),
    sourceId,
    orgUnitId: text(item, ['orgUnitId', 'org_unit_id'], '') || undefined,
    title: text(item, ['title', 'name', 'summary'], '待处理风险事项'),
    type: text(item, ['type', 'riskType', 'risk_type'], 'MANAGEMENT_RISK'),
    severity: text(item, ['severity', 'priority'], 'MEDIUM'),
    status: text(item, ['status'], 'OPEN'),
    source: text(item, ['source', 'sourceType', 'source_type'], '') || undefined,
    ownerName: text(item, ['ownerName', 'owner_name', 'assigneeName'], '') || undefined,
    occurredAt: text(item, ['occurredAt', 'occurred_at', 'createdAt', 'created_at'], '') || undefined,
  }
}

export async function loadHotelDashboard(identity: ApiIdentity, hotelId: string) {
  return withFallback(async () => {
    const [dashboardPayload, templatePayload] = await Promise.all([
      apiRequest<unknown>(dashboardApiPaths.hotel(hotelId), identity),
      apiRequest<unknown>('/templates?type=HOTEL_DASHBOARD', identity).catch(() => []),
    ])
    const raw = object(dashboardPayload)
    const template = asList<JsonObject>(templatePayload).find((row) => text(row, ['lifecycleStatus', 'lifecycle_status']) === 'PUBLISHED' || Boolean(value(row, 'publishedVersionId', 'published_version_id')))
    const configuration = object(jsonColumn(template
      ? text(template, ['lifecycleStatus', 'lifecycle_status']) === 'PUBLISHED'
        ? value(template, 'configuration')
        : value(template, 'publishedConfiguration', 'published_configuration')
      : {}))
    const metricCodes = new Set(asList<string>(configuration.metricCodes))
    const riskLimit = Number(configuration.riskLimit ?? 10)
    const taskLimit = Number(configuration.taskLimit ?? 20)
    const hotel = object(raw.hotel)
    return {
      hotel: {
        id: text(hotel, ['id'], hotelId),
        name: text(hotel, ['name'], '当前门店'),
        city: text(hotel, ['city'], '') || undefined,
        roomCount: value(hotel, 'roomCount', 'room_count') === undefined ? undefined : number(hotel, ['roomCount', 'room_count']),
      },
      activeEmployeeCount: number(raw, ['activeEmployeeCount', 'active_employee_count']),
      todayWorkSubmissionCount: number(raw, ['todayWorkSubmissionCount', 'today_work_submission_count']),
      latestMetrics: asList<JsonObject>(value(raw, 'latestMetrics', 'latest_metrics')).map(normalizeMetric).filter((metric) => !metricCodes.size || metricCodes.has(metric.code)),
      risks: asList<JsonObject>(value(raw, 'risks', 'riskItems', 'risk_items')).map(normalizeRisk).slice(0, riskLimit),
      incompleteTasks: asList<JsonObject>(value(raw, 'incompleteTasks', 'incomplete_tasks')).map(normalizeTask).slice(0, taskLimit),
      templateSections: asList<string>(configuration.sections),
    } satisfies HotelDashboard
  }, () => demoValue<HotelDashboard>('demoHotelDashboard'))
}

export async function loadAccessibleHotels(identity: ApiIdentity) {
  return withFallback(async () => {
    const raw = object(await apiRequest<unknown>(dashboardApiPaths.accessibleHotels, identity))
    return {
      hotels: asList<JsonObject>(raw.hotels).map((hotel) => ({
        id: text(hotel, ['id']),
        code: text(hotel, ['code'], '') || undefined,
        name: text(hotel, ['name'], '门店'),
        city: text(hotel, ['city'], '') || undefined,
        roomCount: value(hotel, 'roomCount', 'room_count') === undefined
          ? undefined
          : number(hotel, ['roomCount', 'room_count']),
      })),
    } satisfies AccessibleHotels
  }, async () => {
    const demoHotel = (await demoValue<HotelDashboard>('demoHotelDashboard')).hotel
    return { hotels: demoHotel ? [demoHotel] : [] } satisfies AccessibleHotels
  })
}

function normalizeOperationsHotel(item: JsonObject): OperationsHotel {
  return {
    id: text(item, ['id']),
    name: text(item, ['name'], '门店'),
    city: text(item, ['city'], '') || undefined,
    roomCount: value(item, 'roomCount', 'room_count') === undefined ? undefined : number(item, ['roomCount', 'room_count']),
    openTaskCount: number(item, ['openTaskCount', 'open_task_count']),
    overdueTaskCount: number(item, ['overdueTaskCount', 'overdue_task_count']),
    failedEvaluationCount: number(item, ['failedEvaluationCount', 'failed_evaluation_count']),
    missedWorkCount: number(item, ['missedWorkCount', 'missed_work_count']),
    todaySubmissionCount: number(item, ['todaySubmissionCount', 'today_submission_count']),
  }
}

export async function loadOperationsDashboard(identity: ApiIdentity) {
  return withFallback(async () => {
    const raw = object(await apiRequest<unknown>(dashboardApiPaths.operations, identity))
    return { hotels: asList<JsonObject>(raw.hotels).map(normalizeOperationsHotel) } satisfies OperationsDashboard
  }, () => demoValue<OperationsDashboard>('demoOperationsDashboard'))
}

function normalizeEvaluation(item: JsonObject): StandardEvaluation {
  return {
      id: text(item, ['id']), subjectType: text(item, ['subjectType', 'subject_type']),
      subjectTitle: text(item, ['subjectTitle', 'subject_title', 'subject_id'], '评价对象'),
      standardCode: text(item, ['standardCode', 'standard_code', 'standard_version_id']), standardTitle: text(item, ['standardTitle', 'standard_title'], '已发布标准版本'),
      standardVersion: number(item, ['standardVersion', 'versionNo', 'version_no'], 1), outcome: text(item, ['outcome'], 'PENDING'),
      score: value(item, 'score') === undefined ? undefined : number(item, ['score']), severity: text(item, ['severity'], 'LOW'),
      executionStatus: text(item, ['executionStatus', 'execution_status'], 'PENDING'),
      evaluatedAt: text(item, ['evaluatedAt', 'evaluated_at', 'completed_at', 'createdAt', 'created_at'], '') || undefined,
      targetOrgName: text(item, ['targetOrgName', 'orgUnitName', 'org_unit_name', 'org_unit_id'], '当前组织'),
      targetOrgUnitId: text(item, ['targetOrgUnitId', 'orgUnitId', 'org_unit_id'], '') || undefined,
      assignmentId: text(item, ['positionAssignmentId', 'position_assignment_id'], '') || undefined,
      items: Array.isArray(item.items) ? item.items.map((raw) => {
        const row = object(raw)
        return { id: text(row, ['id']), itemCode: text(row, ['itemCode', 'item_code', 'code']), itemName: text(row, ['itemName', 'item_name', 'item_code', 'name']), outcome: text(row, ['outcome']), expected: text(row, ['expected', 'expected_value'], '') || undefined, actual: text(row, ['actual', 'actual_value'], '') || undefined, score: value(row, 'score') === undefined ? undefined : number(row, ['score']), reason: text(row, ['reason'], '') || undefined }
      }) : undefined,
    }
}

export async function loadEvaluations(identity: ApiIdentity, options: { orgUnitId?: string; outcome?: string } = {}) {
  return withFallback(async () => {
    const query = new URLSearchParams({ page: '0', size: '100' })
    if (options.orgUnitId) query.set('orgUnitId', options.orgUnitId)
    if (options.outcome) query.set('outcome', options.outcome)
    const payload = await apiRequest<unknown>(`/standard-evaluations?${query.toString()}`, identity)
    return asList<JsonObject>(payload).map(normalizeEvaluation)
  }, () => demoValue<StandardEvaluation[]>('demoEvaluations'))
}

export async function loadEvaluation(identity: ApiIdentity, id: string, fallback?: StandardEvaluation) {
  return withFallback(async () => normalizeEvaluation(object(await apiRequest<unknown>(`/standard-evaluations/${id}`, identity))), fallback ?? normalizeEvaluation({ id }))
}

export async function loadNotifications(identity: ApiIdentity) {
  return withFallback(async () => {
    const payload = await apiRequest<unknown>('/notifications?page=0&size=100', identity)
    return asList<JsonObject>(payload).map((item): NotificationItem => ({
      id: text(item, ['id']), type: text(item, ['type', 'notification_type']), title: text(item, ['title']), content: text(item, ['content', 'message']),
      sourceType: text(item, ['sourceType', 'source_type'], '') || undefined,
      sourceId: text(item, ['sourceId', 'source_id'], '') || undefined,
      recipientAssignmentId: text(item, ['recipientAssignmentId', 'recipient_assignment_id'], '') || undefined,
      createdAt: text(item, ['createdAt', 'created_at', 'delivered_at'], ''), readAt: text(item, ['readAt', 'read_at'], '') || undefined,
      version: number(item, ['version', 'row_version'], 0),
    }))
  }, () => demoValue<NotificationItem[]>('demoNotifications'))
}
