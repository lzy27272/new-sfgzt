import { shouldExpireAuthenticatedSession } from '../app/authBootstrap'

export type ApiIdentity = {
  tenantId: string
  actorId: string
  roleCode: string
  businessActorAssignmentId?: string
  orgScopes: string[]
}

export class ApiError extends Error {
  readonly status: number
  readonly problem?: Record<string, unknown>

  constructor(status: number, message: string, problem?: Record<string, unknown>) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

const API_BASE = (import.meta.env.VITE_API_BASE ?? '/api/v1').replace(/\/$/, '')
const AUTH_MODE = import.meta.env.VITE_AUTH_MODE ?? (import.meta.env.DEV ? 'dev-header' : 'server')
// Public traffic passes through Caddy, which promotes this non-standard header to
// Authorization before proxying to Core API. Keep the value configurable for
// direct deployments, but default to the production proxy contract so a release
// build cannot silently log users out after a successful login.
const BEARER_HEADER = import.meta.env.VITE_BEARER_HEADER ?? 'X-Hotel-AI-Authorization'
const UAT_ACCESS_TOKEN_STORAGE_KEY = 'hotel-ai-os-access-token'
const FEDERATED_SESSION_STORAGE_KEY = 'hotel-ai-os-federated-session'
const TENANT_ID = import.meta.env.VITE_TENANT_ID ?? '10000000-0000-0000-0000-000000000001'

// Federated entry tokens are intentionally scoped to the current page lifetime.
// Unlike the internal Pilot login token, they must never be written to browser storage.
let ephemeralAccessToken: string | undefined
let sessionExpiryNotified = false
let sessionGeneration = 0

const allowDemoQuery = import.meta.env.VITE_ALLOW_DEMO_QUERY === 'true' ||
  (import.meta.env.VITE_ALLOW_DEMO_QUERY === undefined && import.meta.env.DEV)

export const demoFallbackEnabled =
  import.meta.env.VITE_ENABLE_DEMO_FALLBACK === 'true' ||
  (allowDemoQuery && new URLSearchParams(window.location.search).get('demo') === '1')

export const demoOnlyEnabled = import.meta.env.VITE_DEMO_ONLY === 'true'

export const apiBase = API_BASE
export const authMode = AUTH_MODE
export const pilotTenantId = TENANT_ID

export type LoginResponse = {
  accessToken: string
  tokenType: string
  expiresAt: string
  accountId: string
  displayName: string
}

export function hasAccessToken(): boolean {
  // Internal account/password sessions intentionally live only in memory. A refresh,
  // reopened tab, or new browser session must always return to the login screen.
  try { window.localStorage.removeItem(UAT_ACCESS_TOKEN_STORAGE_KEY) } catch { /* storage may be disabled */ }
  if (ephemeralAccessToken) return true
  try {
    const raw = window.localStorage.getItem(FEDERATED_SESSION_STORAGE_KEY)
    if (raw) {
      const marker = JSON.parse(raw) as { version?: number; source?: string; expiresAt?: string }
      const expiresAt = marker.expiresAt ? Date.parse(marker.expiresAt) : Number.NaN
      if (marker.version === 1 && marker.source === 'wecom'
        && Number.isFinite(expiresAt) && expiresAt > Date.now()) return true
      window.localStorage.removeItem(FEDERATED_SESSION_STORAGE_KEY)
    }
  } catch { /* fall through to the page-scoped compatibility marker */ }
  try { return window.sessionStorage.getItem(FEDERATED_SESSION_STORAGE_KEY) === 'wecom' } catch { return false }
}

export function clearAccessToken(): void {
  ephemeralAccessToken = undefined
  sessionGeneration += 1
  try {
    window.localStorage.removeItem(UAT_ACCESS_TOKEN_STORAGE_KEY)
    window.localStorage.removeItem(FEDERATED_SESSION_STORAGE_KEY)
  } catch { /* storage may be disabled */ }
  try { window.sessionStorage.removeItem(FEDERATED_SESSION_STORAGE_KEY) } catch { /* storage may be disabled */ }
}

export function establishFederatedSession(accessToken?: string, expiresAt?: string): void {
  ephemeralAccessToken = accessToken || undefined
  sessionExpiryNotified = false
  sessionGeneration += 1
  try { window.localStorage.removeItem(UAT_ACCESS_TOKEN_STORAGE_KEY) } catch { /* storage may be disabled */ }
  const expiry = expiresAt ? Date.parse(expiresAt) : Number.NaN
  if (Number.isFinite(expiry) && expiry > Date.now()) {
    try {
      window.localStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, JSON.stringify({
        version: 1,
        source: 'wecom',
        expiresAt,
      }))
      window.sessionStorage.removeItem(FEDERATED_SESSION_STORAGE_KEY)
      return
    } catch { /* use the page-scoped compatibility marker below */ }
  }
  try {
    window.sessionStorage.setItem(FEDERATED_SESSION_STORAGE_KEY, 'wecom')
  } catch {
    // The HttpOnly cookie still carries authentication; storage is only a rendering hint.
  }
}

export async function logout(): Promise<void> {
  clearAccessToken()
  await fetch(`${API_BASE}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
  }).catch(() => undefined)
}

export async function login(loginName: string, password: string): Promise<LoginResponse> {
  const response = await fetch(`${API_BASE}/auth/login`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
    body: JSON.stringify({ tenantId: TENANT_ID, loginName: loginName.trim(), password }),
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    throw new ApiError(response.status, String(problem.detail ?? problem.message ?? '登录失败'), problem)
  }
  const session = await response.json() as LoginResponse
  ephemeralAccessToken = session.accessToken
  sessionExpiryNotified = false
  sessionGeneration += 1
  try { window.sessionStorage.removeItem(FEDERATED_SESSION_STORAGE_KEY) } catch { /* storage may be disabled */ }
  window.localStorage.removeItem(UAT_ACCESS_TOKEN_STORAGE_KEY)
  return session
}

export async function changePassword(
  identity: ApiIdentity,
  currentPassword: string,
  newPassword: string,
): Promise<void> {
  await apiRequest<void>('/auth/password', identity, {
    method: 'POST',
    body: JSON.stringify({ currentPassword, newPassword }),
  })
}

export function createIdempotencyKey(prefix = 'web'): string {
  return `${prefix}-${crypto.randomUUID()}`
}

function authenticationHeaders(identity: ApiIdentity): HeadersInit {
  const assignmentHeader: Record<string, string> = {}
  if (identity.businessActorAssignmentId) {
    assignmentHeader['X-Assignment-Id'] = identity.businessActorAssignmentId
  }
  if (AUTH_MODE === 'dev-header') {
    return {
      'X-Tenant-Id': identity.tenantId,
      'X-Actor-Id': identity.actorId,
      ...assignmentHeader,
    }
  }
  if (AUTH_MODE === 'bearer') {
    const accessToken = ephemeralAccessToken
    return {
      ...(accessToken ? { [BEARER_HEADER]: `Bearer ${accessToken}` } : {}),
      ...assignmentHeader,
    }
  }
  // Cookie/SSO deployments still expose the local account login page as a
  // recovery path. When that endpoint returns a page-scoped JWT, use the
  // standard header while continuing to accept the HttpOnly cookie normally.
  return {
    ...(ephemeralAccessToken ? { Authorization: `Bearer ${ephemeralAccessToken}` } : {}),
    ...assignmentHeader,
  }
}

function expireAuthenticatedSession(requestSessionGeneration: number): void {
  // A request started under a previous login must not invalidate a newer
  // session if its delayed 401 arrives after the user signs in again.
  if (requestSessionGeneration !== sessionGeneration) return
  clearAccessToken()
  if (sessionExpiryNotified) return
  sessionExpiryNotified = true
  window.dispatchEvent(new Event('hotel-ai-os:auth-expired'))
  // The browser cannot delete an HttpOnly session cookie itself. Logout is a
  // best-effort same-origin cleanup and deliberately bypasses apiRequest so a
  // rejected session cannot recurse through this handler.
  void fetch(`${API_BASE}/auth/logout`, {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json', 'X-Correlation-Id': crypto.randomUUID() },
  }).catch(() => undefined)
}

export async function apiRequest<T>(path: string, identity: ApiIdentity, init: RequestInit = {}): Promise<T> {
  const correlationId = crypto.randomUUID()
  const requestSessionGeneration = sessionGeneration
  const hasJsonBody = Boolean(init.body) && !(init.body instanceof FormData)
  const response = await fetch(`${API_BASE}${path.startsWith('/') ? path : `/${path}`}`, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/json',
      ...(hasJsonBody ? { 'Content-Type': 'application/json' } : {}),
      'X-Correlation-Id': correlationId,
      ...authenticationHeaders(identity),
      ...init.headers,
    },
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    const message = String(problem.detail ?? problem.message ?? `请求失败（HTTP ${response.status}）`)
    if (shouldExpireAuthenticatedSession(AUTH_MODE, response.status)) {
      expireAuthenticatedSession(requestSessionGeneration)
    }
    throw new ApiError(response.status, message, problem)
  }

  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}

export async function apiBlob(path: string, identity: ApiIdentity, init: RequestInit = {}): Promise<Blob> {
  const requestSessionGeneration = sessionGeneration
  const hasJsonBody = Boolean(init.body) && !(init.body instanceof FormData)
  const response = await fetch(`${API_BASE}${path.startsWith('/') ? path : `/${path}`}`, {
    ...init,
    credentials: 'include',
    headers: {
      Accept: 'application/pdf,image/*,application/octet-stream',
      ...(hasJsonBody ? { 'Content-Type': 'application/json' } : {}),
      'X-Correlation-Id': crypto.randomUUID(),
      ...authenticationHeaders(identity),
      ...init.headers,
    },
  })
  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    if (shouldExpireAuthenticatedSession(AUTH_MODE, response.status)) {
      expireAuthenticatedSession(requestSessionGeneration)
    }
    throw new ApiError(response.status, String(problem.detail ?? problem.message ?? `请求失败（HTTP ${response.status}）`), problem)
  }
  return response.blob()
}

export async function apiCommand<T>(
  path: string,
  identity: ApiIdentity,
  body: unknown,
  expectedVersion?: number,
): Promise<T> {
  return apiMutation<T>(path, identity, {
    body,
    expectedVersion,
    idempotencyKey: createIdempotencyKey('command'),
  })
}

export type ApiMutationOptions = {
  method?: 'POST' | 'PUT' | 'PATCH' | 'DELETE'
  body?: unknown
  expectedVersion?: number
  idempotencyKey?: string
  signal?: AbortSignal
}

export async function apiMutation<T>(
  path: string,
  identity: ApiIdentity,
  options: ApiMutationOptions = {},
): Promise<T> {
  const body = options.body === undefined ? {} : options.body
  const payload = {
    ...(typeof body === 'object' && body !== null && !Array.isArray(body) ? body : { value: body }),
    ...(options.expectedVersion === undefined ? {} : { expectedVersion: options.expectedVersion }),
  }
  return apiRequest<T>(path, identity, {
    method: options.method ?? 'POST',
    headers: { 'Idempotency-Key': options.idempotencyKey ?? createIdempotencyKey('command') },
    body: JSON.stringify(payload),
    signal: options.signal,
  })
}

export function asList<T>(payload: unknown): T[] {
  if (Array.isArray(payload)) return payload as T[]
  if (payload && typeof payload === 'object') {
    const value = payload as Record<string, unknown>
    for (const key of ['items', 'content', 'data', 'records', 'results']) {
      if (Array.isArray(value[key])) return value[key] as T[]
    }
  }
  return []
}
