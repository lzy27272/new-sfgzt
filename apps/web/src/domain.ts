import type { ApiIdentity } from './api/client'

export type ViewId =
  | 'workbench'
  | 'all-functions'
  | 'account-self-service'
  | 'hotel-dashboard'
  | 'operations-dashboard'
  | 'work-packages'
  | 'my-work'
  | 'team-work'
  | 'rules'
  | 'tasks'
  | 'evaluations'
  | 'notifications'
  | 'templates'
  | 'organization'
  | 'store-resources'
  | 'finance'
  | 'procurement'
  | 'data-retention'
  | 'ai-data'
  | 'wecom-webhooks'
  | 'wecom-bindings'
  | 'wecom-onboarding'

export type RouteParams = Record<string, string>
export type NavigationParams = Record<string, string | undefined>
export type Navigate = (view: ViewId, params?: NavigationParams) => void

export type RoleContext = ApiIdentity & {
  key: string
  label: string
  userName: string
  orgName: string
  focus: string
  employeeId?: string
  assignmentOrgUnitId?: string
}

export type IdentityAssignment = {
  id: string
  orgUnitId: string
  orgName: string
  positionId: string
  positionCode: string
  positionName: string
  primary: boolean
  assignmentType: string
  permissionCodes?: string[]
  authorizationScopeType?: 'SELF' | 'ORG_UNIT' | 'ORG_TREE' | 'TENANT'
}

export type IdentitySnapshot = {
  accountId: string
  displayName: string
  employeeId?: string
  employeeName?: string
  primaryRoleCode: string
  roleCodes: string[]
  permissions: string[]
  tenantScope: boolean
  orgScopes: string[]
  assignments: IdentityAssignment[]
  businessActorAssignmentId?: string
  capabilities: {
    groupManagement: {
      workPlansEnabled: boolean
      executiveTasksEnabled: boolean
    }
  }
}

export type WorkPackage = {
  id: string
  code: string
  name: string
  positionName: string
  versionNo: number
  lifecycleStatus: string
  scopeName: string
  completionRate?: number
  itemCount?: number
  updatedAt?: string
}

export type WorkExpectation = {
  boardStatus?: 'PENDING' | 'OVERDUE' | 'REVIEW' | 'COMPLETED'
  latestRecordStatus?: string
  reviewMode?: string
  departmentOrgUnitId?: string
  id: string
  itemCode?: string
  title: string
  packageName: string
  itemName: string
  status: string
  businessDate: string
  periodKey?: string
  periodType?: string
  availableAt?: string
  dueAt?: string
  targetOrgName: string
  hotelOrgUnitId?: string
  hotelName?: string
  departmentName?: string
  assigneeName: string
  positionName?: string
  positionJobFamily?: string
  assignmentId?: string
  orgUnitId?: string
  employeeId?: string
  formVersionId?: string
  formCode?: string
  formName?: string
  formSchema?: {
    type?: string
    required?: string[]
    properties?: Record<string, {
      type?: string
      title?: string
      description?: string
      enum?: Array<string | number>
      minimum?: number
      maximum?: number
      minItems?: number
      maxItems?: number
      uniqueItems?: boolean
      items?: { type?: string; minLength?: number; maxLength?: number }
    }>
  }
  workPackageVersionId?: string
  workPackageItemId?: string
  recordId?: string
  firstSubmittedAt?: string
  boardLate?: boolean
  latestSubmittedAt?: string
  rowVersion?: number
  evaluationOutcome?: string
  standards?: WorkStandardReference[]
  records?: WorkRecordSummary[]
  submissionPolicy?: SubmissionPolicy
  reminderPolicy?: Record<string, unknown>
  reportPolicy?: Record<string, unknown>
  applicabilityPolicy?: Record<string, unknown>
  executionPolicy?: {
    managerCycle?: boolean
    sharedPerOrg?: boolean
    allowedPositionCodes?: string[]
    delegationAllowed?: boolean
    ownerRestOption?: boolean
    oneClickRestTransfer?: boolean
    requiredRoomCountFormula?: string
    repeatableDailyRecords?: boolean
    registrationMode?: 'WHEN_OCCURRED'
  }
  guestRoomFloorCount?: number
  operationalFeatureFlags?: Record<string, boolean>
  evidenceInstanceConfiguration?: {
    facilityInstances: Record<string, string[]>
    assetInstances: Record<string, string[]>
  }
  delegateAssignmentId?: string
  delegatedEmployeeName?: string
  ownerResting?: boolean
}

export type WorkCompletionMetric = {
  expected: number
  completed: number
  onTimeCompleted: number
  lateSubmitted: number
  pending: number
  overdue: number
  completionRate: number
}

export type OrganizationWorkbenchMetric = {
  id: string
  name: string
  today: WorkCompletionMetric
  monthToDate: WorkCompletionMetric
  departments: DepartmentWorkbenchMetric[]
}

export type HotelWorkbenchMetric = OrganizationWorkbenchMetric

export type DepartmentWorkbenchMetric = {
  id: string
  name: string
  today: WorkCompletionMetric
  monthToDate: WorkCompletionMetric
  employees: EmployeeWorkbenchMetric[]
}

export type EmployeeWorkbenchMetric = {
  id: string
  assignmentId: string
  employeeId: string
  name: string
  positionName: string
  today: WorkCompletionMetric
  monthToDate: WorkCompletionMetric
}

export type WorkbenchSummary = {
  asOfDate: string
  monthStart: string
  today: WorkCompletionMetric
  monthToDate: WorkCompletionMetric
  hotels: HotelWorkbenchMetric[]
  /** Authorized non-hotel work, including group and headquarters assignments. */
  organizations?: OrganizationWorkbenchMetric[]
}

export type EvidenceRequirement = {
  checkpointCode: string
  label: string
  captureSource?: 'CAMERA' | 'FILE_PICKER'
  mediaTypes?: string[]
  allowedKinds?: Array<'IMAGE' | 'DOCUMENT' | 'PDF' | 'WORD' | 'EXCEL'>
  minimum?: number
  recommendedMaximum?: number
  minimumPerHotelFloor?: number
  recommendedMaximumPerHotelFloor?: number
  requiredInstances?: number
  instanceField?: string
  instanceLabel?: string
  minimumPerInstance?: number
  minimumPerConfiguredFacility?: number
  recommendedMaximumPerConfiguredFacility?: number
  minimumPerConfiguredAsset?: number
  configuredFacilityInstanceField?: string
  configuredAssetInstanceField?: string
  conditionalFacility?: string
  unlimited?: boolean
  allowReference?: boolean
  requiredWhen?: { field: string; equals: unknown }
}

export type DelegationCandidate = {
  assignmentId: string
  employeeName: string
  positionName: string
}

export type SubmissionPolicy = {
  inventoryUsage?: boolean
  completionStatementRequired: boolean
  exceptionStatementRequired: boolean
  nextActionRequired: boolean
  attachmentRequired: boolean
  maxAttachments: number
  attachmentCountUnlimited?: boolean
  maxFileSizeBytes: number
  allowedExtensions: string[]
  evidenceRequirements: EvidenceRequirement[]
}

export type WorkStandardReference = {
  standardVersionId: string
  usageType: string
  standardCode: string
  title: string
  versionNo: number
}

export type WorkRecordSummary = {
  id: string
  status: string
  attemptNo: number
  seriesEntryNo?: number
  repeatableEntry?: boolean
  submittedAt?: string
  reviewedAt?: string
  reviewReason?: string
}

export type WorkRecordAttachment = {
  id: string
  objectKey?: string
  originalName: string
  mediaType: string
  sizeBytes: number
  sha256?: string
  scanStatus: string
  captureSource?: string
  checkpointCode?: string
  evidenceInstanceKey?: string
  capturedAtClient?: string
  receivedAt?: string
  sourceSha256?: string
  displayOrientation?: 'LANDSCAPE' | 'PORTRAIT' | 'SQUARE'
  normalizedWidth?: number
  normalizedHeight?: number
  createdAt?: string
}

export type WorkRecordDetail = {
  id: string
  inventoryUsage?: import('./features/storeResources/workUsage').UsageDetail
  status: string
  rowVersion: number
  businessDate?: string
  payload: Record<string, unknown>
  employeeName: string
  positionName: string
  formName: string
  targetOrgName: string
  targetOrgUnitId?: string
  positionAssignmentId?: string
  workExpectationId?: string
  completionStatement?: string
  exceptionStatement?: string
  nextAction?: string
  reviewReason?: string
  submittedAt?: string
  reviewedAt?: string
  attachments: WorkRecordAttachment[]
  supplements: WorkRecordSupplement[]
}

export type WorkRecordSupplement = {
  id: string
  submittedByAssignmentId: string
  submittedByName: string
  content: string
  createdAt?: string
}

export type TeamWorkCase = {
  expectation: WorkExpectation
  record?: WorkRecordDetail
}

export type ManagementRule = {
  id: string
  code: string
  name: string
  status: string
  versionNo: number
  eventType: string
  scopeName: string
  hitCount?: number
  latestVersionId?: string
  rowVersion?: number
}

export type RuleScope = {
  scopeType: string
  brandId?: string
  orgUnitId?: string
  positionId?: string
}

export type RuleVersionDetail = {
  id: string
  versionNo: number
  lifecycleStatus: string
  conditionAst: Record<string, unknown>
  actions: Array<Record<string, unknown>>
  priority: number
  cooldownMinutes: number
  rowVersion: number
  effectiveFrom?: string
  effectiveTo?: string
  scopes: RuleScope[]
}

export type RuleDetail = {
  id: string
  code: string
  name: string
  eventType: string
  description?: string
  status: string
  versions: RuleVersionDetail[]
}

export type RuleVersionDraft = {
  conditionAst: Record<string, unknown>
  actions: Array<Record<string, unknown>>
  priority: number
  cooldownMinutes: number
  scopes: RuleScope[]
}

export type ManagementTask = {
  id: string
  code: string
  title: string
  status: string
  slaStatus: string
  priority: string
  assigneeName: string
  reviewerName: string
  targetOrgName: string
  sourceType: string
  creationSource?: string
  sourceTitle?: string
  description?: string
  dueAt?: string
  version: number
  targetOrgUnitId?: string
  standardVersionId?: string
  resultSnapshot?: Record<string, unknown>
  sourceSnapshot?: Record<string, unknown>
  evaluationId?: string
  assigneeAssignmentId?: string
  reviewerAssignmentId?: string
  createdByAssignmentId?: string
  timeline?: TaskTimelineItem[]
  evidence?: TaskEvidence[]
}

export type TaskEvidence = {
  id: string
  submittedByAssignmentId?: string
  evidenceType: string
  objectKey?: string
  originalName?: string
  mediaType?: string
  sizeBytes?: number
  scanStatus: string
  createdAt?: string
  structuredResult?: Record<string, unknown>
}

export type TaskTimelineItem = {
  id: string
  command: string
  fromStatus?: string
  toStatus: string
  actorName: string
  occurredAt: string
  remark?: string
}

export type StandardEvaluation = {
  id: string
  subjectType: string
  subjectTitle: string
  standardCode: string
  standardTitle: string
  standardVersion: number
  outcome: string
  score?: number
  severity: string
  executionStatus: string
  evaluatedAt?: string
  targetOrgName: string
  targetOrgUnitId?: string
  assignmentId?: string
  items?: EvaluationItem[]
}

export type EvaluationItem = {
  id: string
  itemCode: string
  itemName: string
  outcome: string
  expected?: string
  actual?: string
  score?: number
  reason?: string
}

export type NotificationItem = {
  id: string
  type: string
  title: string
  content: string
  sourceType?: string
  sourceId?: string
  createdAt: string
  readAt?: string
  version: number
  recipientAssignmentId?: string
  wecomDeliveryStatus?: string
}

export type DashboardMetric = {
  code: string
  name: string
  unit: string
  value: number
  businessDate?: string
}

export type DashboardRisk = {
  id: string
  sourceId?: string
  orgUnitId?: string
  title: string
  type: string
  severity: string
  status: string
  source?: string
  ownerName?: string
  occurredAt?: string
}

export type HotelDashboard = {
  hotel: { id: string; name: string; city?: string; roomCount?: number }
  activeEmployeeCount: number
  todayWorkSubmissionCount: number
  latestMetrics: DashboardMetric[]
  risks: DashboardRisk[]
  incompleteTasks: ManagementTask[]
  templateSections?: string[]
}

export type AccessibleHotel = {
  id: string
  code?: string
  name: string
  city?: string
  roomCount?: number
}

export type AccessibleHotels = {
  hotels: AccessibleHotel[]
}

export type OperationsHotel = {
  id: string
  name: string
  city?: string
  roomCount?: number
  openTaskCount: number
  overdueTaskCount: number
  failedEvaluationCount: number
  missedWorkCount: number
  todaySubmissionCount: number
}

export type OperationsDashboard = {
  hotels: OperationsHotel[]
}

export type EnterpriseTemplateType = 'TASK' | 'HOTEL_DASHBOARD'

export type EnterpriseTemplate = {
  id: string
  templateType: EnterpriseTemplateType
  code: string
  name: string
  description?: string
  positionName?: string
  ownerOrgName?: string
  latestVersionId?: string
  versionNo: number
  lifecycleStatus: string
  configuration: Record<string, unknown>
  publishedVersionId?: string
  publishedVersionNo?: number
  publishedConfiguration?: Record<string, unknown>
  rowVersion: number
}

export type ApiSource = 'api' | 'demo'
