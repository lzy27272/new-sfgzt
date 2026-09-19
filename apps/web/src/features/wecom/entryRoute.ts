const WECOM_ENTRY_PATH = '/wecom-auth'
const UNSAFE_CREDENTIAL_PARAMS = ['access_token', 'token', 'jwt', 'secret', 'corpsecret', 'corp_secret', 'wecom_secret'] as const
const CODE_PATTERN = /^[A-Za-z0-9._~-]{16,512}$/
const TASK_ID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const DAILY_REPORT_PATH_PATTERN = /^\/daily-reports\/([0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})$/i
const LOGIN_ERRORS: Record<string, string> = {
  MEMBER_UNBOUND: '当前企业微信成员尚未绑定有效中台账号。已有中台账号的员工请联系管理员发起该账号的企微绑定；无需重复注册账号。',
  ACCOUNT_UNAVAILABLE: '中台账号或任职当前不可用，请联系管理员检查账号启用状态、任职及岗位配置。',
  ASSIGNMENT_INACTIVE: '企微绑定的默认任职已失效，请联系管理员重新确认有效任职后再登录。',
  SESSION_INVALID: '本次企微身份验证已失效，请点击“重新通过企业微信验证”重新进入。',
  VERIFICATION_FAILED: '企微身份验证暂未完成，请重新验证；如仍失败，请联系管理员检查账号绑定及企微连接。',
}

export type WecomTaskEntry = {
  code?: string
  securityError?: string
}

export function consumeWecomSessionBootstrap(
  establishSession: () => void,
  location: Location = window.location,
): boolean {
  const url = new URL(location.href)
  const markers = url.searchParams.getAll('wecom_session')
  if (!markers.length) return false

  url.searchParams.delete('wecom_session')
  const remainingQuery = url.searchParams.toString()
  window.history.replaceState(null, '', `${url.pathname}${remainingQuery ? `?${remainingQuery}` : ''}${url.hash}`)
  if (markers.length !== 1 || markers[0] !== '1') return false
  establishSession()
  return true
}

let consumed = false
let cachedEntry: WecomTaskEntry | undefined

/**
 * Reads a WeCom callback exactly once and removes credentials from the address bar
 * before React performs the exchange request. Both history and storage stay free of
 * the one-time code.
 */
export function consumeWecomTaskEntry(location: Location = window.location): WecomTaskEntry | undefined {
  if (consumed) return cachedEntry
  consumed = true

  const url = new URL(location.href)
  const cleanPath = url.pathname.replace(/\/$/, '')
  const fromPath = cleanPath === WECOM_ENTRY_PATH
  if (!fromPath) return undefined

  const params = url.searchParams
  const code = params.get('exchange_code')?.trim() || undefined
  const errorCode = params.get('error_code')
  const unsafeParam = UNSAFE_CREDENTIAL_PARAMS.find((key) => params.has(key))

  window.history.replaceState(null, '', `${url.pathname}${url.hash}`)

  if (unsafeParam) {
    cachedEntry = { securityError: '链接包含禁止出现在地址栏中的敏感凭据，系统已拒绝使用并清除。请从企微重新打开任务。' }
  } else if ([...params.keys()].some((key) => !['exchange_code', 'error_code'].includes(key))
    || params.getAll('exchange_code').length > 1 || params.getAll('error_code').length > 1
    || (params.has('exchange_code') && params.has('error_code'))) {
    cachedEntry = { securityError: '企微登录链接包含重复或非预期参数，请从企业微信重新验证。' }
  } else if (errorCode) {
    cachedEntry = { securityError: Object.hasOwn(LOGIN_ERRORS, errorCode) ? LOGIN_ERRORS[errorCode] : LOGIN_ERRORS.VERIFICATION_FAILED }
  } else if (!code || !CODE_PATTERN.test(code)) {
    cachedEntry = { securityError: '企微一次性登录凭证缺失或格式无效。凭证可能已过期，请返回企微重新打开任务。' }
  } else {
    cachedEntry = { code }
  }
  return cachedEntry
}

export function wecomLoginRestartPath(): string {
  // Fixed same-origin OAuth entry, never reuse a consumed credential or trust a return URL from the address bar.
  return '/api/v1/integrations/wecom/oauth/start?' + new URLSearchParams({ returnTo: '#/workbench' })
}

export function buildTaskDeepLink(taskId: string): string {
  if (!TASK_ID_PATTERN.test(taskId)) throw new Error('无效的任务编号。')
  const query = new URLSearchParams({ view: 'mine', taskId })
  return `#/tasks?${query.toString()}`
}

export function buildWorkExpectationDeepLink(expectationId: string): string {
  if (!TASK_ID_PATTERN.test(expectationId)) throw new Error('无效的岗位事项编号。')
  const query = new URLSearchParams({ expectationId })
  return `#/my-work?${query.toString()}`
}

export function buildAppHashLocation(hashRoute: string, baseUrl: string): string {
  const safeBase = baseUrl.startsWith('/') && !baseUrl.startsWith('//') && !/[\\\u0000-\u001f\u007f]/.test(baseUrl)
    ? baseUrl
    : '/'
  const normalizedBase = safeBase.endsWith('/') ? safeBase : `${safeBase}/`
  const normalizedHash = hashRoute.startsWith('#/') ? hashRoute : `#/${hashRoute.replace(/^#?\/?/, '')}`
  return `${normalizedBase}${normalizedHash}`
}

export function safeTaskDeepLink(returnTo: string | undefined): string {
  if (!returnTo) {
    throw new Error('服务端未返回本次企微登录绑定的任务地址。')
  }
  if (/[\\\u0000-\u001f\u007f]/.test(returnTo) || returnTo.includes('://') || returnTo.startsWith('//')) {
    throw new Error('服务端返回了不安全的任务地址，系统已拒绝跳转。')
  }

  const normalized = returnTo.startsWith('#/') ? returnTo.slice(1) : returnTo
  const target = new URL(normalized, window.location.origin)
  if (target.origin !== window.location.origin) {
    throw new Error('企微登录目标不是中台任务或日报页面，系统已拒绝跳转。')
  }

  const reportMatch = target.pathname.match(DAILY_REPORT_PATH_PATTERN)
  if (reportMatch) {
    if (target.search || target.hash) {
      throw new Error('企微日报目标包含非预期参数，系统已拒绝跳转。')
    }
    return `#/daily-reports/${reportMatch[1]}`
  }

  if (target.pathname === '/workbench') {
    if (target.search || target.hash) {
      throw new Error('企微工作台目标包含非预期参数，系统已拒绝跳转。')
    }
    return '#/workbench'
  }

  if (target.pathname === '/my-work') {
    const expectationIds = target.searchParams.getAll('expectationId')
    const queryKeys = [...target.searchParams.keys()]
    if (target.hash || queryKeys.length !== 1 || queryKeys[0] !== 'expectationId'
      || expectationIds.length !== 1 || !TASK_ID_PATTERN.test(expectationIds[0])) {
      throw new Error('企微登录目标缺少有效的岗位事项编号，系统已拒绝跳转。')
    }
    return buildWorkExpectationDeepLink(expectationIds[0])
  }

  if (target.pathname !== '/tasks') {
    throw new Error('企微登录目标不是允许的中台页面，系统已拒绝跳转。')
  }
  const taskId = target.searchParams.get('taskId')?.trim()
  if (!taskId || !TASK_ID_PATTERN.test(taskId)) {
    throw new Error('企微登录目标缺少有效的任务编号，系统已拒绝跳转。')
  }
  return buildTaskDeepLink(taskId)
}
