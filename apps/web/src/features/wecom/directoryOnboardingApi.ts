import { apiBase, apiRequest, ApiError } from '../../api/client'
import { withRequestDeadline } from '../../api/requestDeadline'
import type { RoleContext } from '../../domain'

export type DirectoryOnboardingStatus = 'WAITING_PROFILE' | 'PENDING_APPROVAL' | 'CONFLICT' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | 'EXPIRED'
export type DirectoryPositionOption = {
  id: string
  name: string
  selectable: boolean
  unavailableReason?: string
}
export type DirectoryDepartmentOption = { id: string; name: string; positions: DirectoryPositionOption[] }
export type DirectoryHotelOption = {
  id: string
  name: string
  unitType: 'GROUP' | 'HOTEL'
  departments: DirectoryDepartmentOption[]
}
export type DirectoryOnboardingContext = {
  candidateId: string
  status: DirectoryOnboardingStatus
  displayName: string
  loginName?: string
  requiresAccountRegistration: boolean
  invitationSource?: 'DIRECTORY_EVENT' | 'MANUAL_LINK' | 'SELF_SERVICE'
  hotels: DirectoryHotelOption[]
  rowVersion: number
  requestedOrganizationName?: string
  requestedPositionName?: string
}
export type DirectoryOnboardingSubmitResponse = {
  candidateId: string
  status: DirectoryOnboardingStatus
  rowVersion: number
  message: string
}
export type DirectoryCandidate = {
  id: string
  maskedFingerprint: string
  displayName: string
  requestedLoginName?: string
  onboardingKind?: string
  requestedOrgUnitId?: string
  requestedHotelName?: string
  requestedDepartmentName?: string
  requestedPositionId?: string
  requestedPositionName?: string
  invitationSource?: 'DIRECTORY_EVENT' | 'MANUAL_LINK' | 'SELF_SERVICE'
  status: DirectoryOnboardingStatus
  failureCode?: string
  invitationExpiresAt?: string
  expiringSoon: boolean
  retryable: boolean
  canRegenerate: boolean
  suggestedAction?: string
  updatedAt: string
  rowVersion: number
}
export type DirectoryReviewAssignmentOption = {
  orgUnitId: string
  positionId: string
  name: string
}
export type DirectoryReviewOptions = {
  candidateId: string
  hotelId: string
  hotelName: string
  positions: DirectoryReviewAssignmentOption[]
}
export type DirectoryOpenInvitation = {
  candidateId: string
  enrollmentUrl: string
  expiresAt: string
  status: 'WAITING_PROFILE'
  rowVersion: number
  message: string
}
export type DirectoryApprovalResponse = {
  candidateId: string
  accountId?: string
  status: DirectoryOnboardingStatus
  bindingStatus?: string
  rowVersion: number
  message: string
}
export type DirectoryInvitationActionResponse = {
  candidateId: string
  status: DirectoryOnboardingStatus
  invitationExpiresAt: string
  rowVersion: number
  message: string
}
export type DirectoryEventRow = {
  id: string
  changeType: string
  status: 'DEAD_LETTER'
  lastErrorCode?: string
  errorMessage: string
  receivedAt: string
  rowVersion: number
  suggestedAction: string
}
export type DirectoryEventRetryResponse = {
  receiptId: string
  status: string
  rowVersion: number
  message: string
}

async function publicPost<T>(path: string, body: Record<string, unknown>): Promise<T> {
  return withRequestDeadline(async (signal) => {
    const response = await fetch(`${apiBase}${path}`, {
      method: 'POST', credentials: 'include', signal,
      headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
      body: JSON.stringify(body),
    })
    if (!response.ok) {
      const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
      throw new ApiError(response.status, String(problem.detail ?? problem.message ?? '企业微信入职处理失败'), problem)
    }
    return response.json() as Promise<T>
  }, '请求超时，请检查网络后重试；若已提交申请，请重新验证并查询进度，避免重复注册。')
}

export async function startDirectoryOnboarding(invitationToken: string): Promise<string> {
  const result = await publicPost<{ authorizationUri: string }>('/integrations/wecom/directory-onboarding/start', { invitationToken })
  return checkedAuthorizationUri(result.authorizationUri)
}

export async function startSelfServiceOnboarding(): Promise<string> {
  const result = await publicPost<{ authorizationUri: string }>('/integrations/wecom/directory-onboarding/self-service/start', {})
  return checkedAuthorizationUri(result.authorizationUri)
}

function checkedAuthorizationUri(uri: string): string {
  const target = new URL(uri)
  if (target.protocol !== 'https:' || target.hostname !== 'open.weixin.qq.com') throw new Error('服务端返回的企业微信授权地址无效，系统已停止跳转。')
  return target.toString()
}

const enrollmentExchanges = new Map<string, Promise<{ sessionToken: string; expiresAt: string; status: DirectoryOnboardingStatus }>>()
export function exchangeDirectoryOnboarding(exchangeCode: string) {
  const existing = enrollmentExchanges.get(exchangeCode)
  if (existing) return existing
  const request = publicPost<{ sessionToken: string; expiresAt: string; status: DirectoryOnboardingStatus }>('/integrations/wecom/directory-onboarding/exchange', { exchangeCode })
  enrollmentExchanges.set(exchangeCode, request)
  return request
}

export function loadDirectoryOnboardingContext(sessionToken: string) {
  return publicPost<DirectoryOnboardingContext>('/integrations/wecom/directory-onboarding/context', { sessionToken })
}

export function submitDirectoryOnboarding(
  sessionToken: string,
  displayName: string,
  mobile: string,
  loginName: string,
  password: string,
  passwordConfirmation: string,
  orgUnitId: string,
  positionId: string | undefined,
  expectedVersion: number,
) {
  return publicPost<DirectoryOnboardingSubmitResponse>('/integrations/wecom/directory-onboarding/submit', {
    sessionToken, displayName, mobile, loginName, password, passwordConfirmation,
    orgUnitId, positionId: positionId || null, expectedVersion,
  })
}

const adminBase = '/integrations/wecom/directory-onboarding/candidates'

export function createDirectoryOnboardingInvitation(identity: RoleContext) {
  return apiRequest<DirectoryOpenInvitation>('/integrations/wecom/directory-onboarding/invitations', identity, {
    method: 'POST',
  })
}

export async function loadDirectoryCandidates(identity: RoleContext, status?: string): Promise<DirectoryCandidate[]> {
  const query = status ? `?status=${encodeURIComponent(status)}` : ''
  const payload = await apiRequest<{ items?: DirectoryCandidate[] } | DirectoryCandidate[]>(`${adminBase}${query}`, identity)
  return Array.isArray(payload) ? payload : payload.items ?? []
}

export function loadDirectoryCandidateReviewOptions(identity: RoleContext, candidate: DirectoryCandidate) {
  return apiRequest<DirectoryReviewOptions>(`${adminBase}/${candidate.id}/assignment-options`, identity)
}

export function approveDirectoryCandidate(
  identity: RoleContext,
  candidate: DirectoryCandidate,
  reason?: string,
  transferExistingBinding = false,
  assignment?: { orgUnitId: string; positionId: string },
  employeeType?: string,
) {
  return apiRequest<DirectoryApprovalResponse>(`${adminBase}/${candidate.id}/approve`, identity, {
    method: 'POST', body: JSON.stringify({
      expectedVersion: candidate.rowVersion,
      reason: reason || null,
      transferExistingBinding,
      orgUnitId: assignment?.orgUnitId || null,
      positionId: assignment?.positionId || null,
      employeeType: employeeType || null,
    }),
  })
}

export function rejectDirectoryCandidate(identity: RoleContext, candidate: DirectoryCandidate, reason: string) {
  return apiRequest(`${adminBase}/${candidate.id}/reject`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason }),
  })
}

export function retryDirectoryCandidate(identity: RoleContext, candidate: DirectoryCandidate, reason?: string) {
  return apiRequest<DirectoryInvitationActionResponse>(`${adminBase}/${candidate.id}/retry`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason: reason || null }),
  })
}

export function regenerateDirectoryInvitation(identity: RoleContext, candidate: DirectoryCandidate, reason?: string) {
  return apiRequest<DirectoryInvitationActionResponse>(`${adminBase}/${candidate.id}/regenerate-invitation`, identity, {
    method: 'POST', body: JSON.stringify({ expectedVersion: candidate.rowVersion, reason: reason || null }),
  })
}

export async function loadDirectoryEvents(identity: RoleContext): Promise<DirectoryEventRow[]> {
  const payload = await apiRequest<{ items?: DirectoryEventRow[] } | DirectoryEventRow[]>(
    '/integrations/wecom/directory-onboarding/directory-events?status=DEAD_LETTER',
    identity,
  )
  return Array.isArray(payload) ? payload : payload.items ?? []
}

export function retryDirectoryEvent(identity: RoleContext, event: DirectoryEventRow, reason?: string) {
  return apiRequest<DirectoryEventRetryResponse>(
    `/integrations/wecom/directory-onboarding/directory-events/${event.id}/retry`,
    identity,
    {
      method: 'POST',
      body: JSON.stringify({ expectedVersion: event.rowVersion, reason: reason || null }),
    },
  )
}
