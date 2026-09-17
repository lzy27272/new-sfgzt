export type DailyReportStatus = 'DRAFT' | 'SUBMITTED' | 'ARCHIVED'
export type DailyReportReviewStatus = 'NOT_REQUIRED' | 'PENDING' | 'APPROVED' | 'REJECTED'
export type DailyReportRevisionStatus = 'DRAFT' | 'SUBMITTED' | 'APPROVED' | 'REJECTED'

export type DailyReportSummary = {
  id: string
  businessDate: string
  hotelName?: string
  departmentName?: string
  positionName: string
  employeeName?: string
  templateName: string
  templateVersionNo: number
  status: DailyReportStatus
  reviewStatus: DailyReportReviewStatus
  completionRate?: number
  missingRequiredCount?: number
  exceptionCount?: number
  evidenceCount?: number
  dueAt?: string
  updatedAt?: string
  rowVersion: number
}

export type DailyReportEvidence = {
  id: string
  fileName: string
  mediaType?: string
  sensitivity: 'NORMAL' | 'SENSITIVE'
  scanStatus?: string
  invalidatedAt?: string
}

export type DailyReportItemResult = {
  id: string
  templateItemId: string
  code: string
  label: string
  description?: string
  inputType: 'TEXT' | 'NUMBER' | 'BOOLEAN' | 'SELECT' | 'NARRATIVE' | 'EVIDENCE'
  required: boolean
  evidenceRequired: boolean
  dataSource: 'EMPLOYEE' | 'SYSTEM' | 'MIXED'
  resultStatus?: 'PENDING' | 'COMPLETED' | 'EXCEPTION' | 'NOT_APPLICABLE'
  systemValue?: string
  employeeValue?: string
  exception: boolean
  sourceLabels: string[]
  evidence: DailyReportEvidence[]
}

export type DailyReportSectionResult = {
  id: string
  name: string
  conditional: boolean
  applicable: boolean
  items: DailyReportItemResult[]
}

export type DailyReportRevision = {
  id: string
  revisionNo: number
  revisionType?: 'ORIGINAL' | 'CORRECTION'
  status: DailyReportRevisionStatus
  correctionReason?: string
  rowVersion: number
  sections: DailyReportSectionResult[]
  submittedAt?: string
  submittedByAccountId?: string
}

export type DailyReportAiRecommendation = {
  id: string
  facts: string
  analysis: string
  recommendation: string
  sourceLabels: string[]
  createdAt: string
}

export type DailyReportDetail = DailyReportSummary & {
  currentRevision: DailyReportRevision
  revisions: Array<Pick<DailyReportRevision, 'id' | 'revisionNo' | 'status' | 'submittedAt' | 'correctionReason'>>
  aiRecommendations: DailyReportAiRecommendation[]
  allowedActions: string[]
  blockedActionReasons: Record<string, string>
}

export type DailyReportDraftInput = {
  itemValues: Array<{ templateItemId: string; value?: string; exception: boolean; comment?: string }>
  narrative?: string
}
