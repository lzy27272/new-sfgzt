const TOKEN_PATTERN = /^[A-Za-z0-9_-]{32,512}$/
const OAUTH_ERROR_CODES = [
  'OAUTH_SESSION_INVALID',
  'OAUTH_IDENTITY_MISMATCH',
  'OAUTH_PROVIDER_UNAVAILABLE',
  'OAUTH_VERIFICATION_FAILED',
  'ACCOUNT_READY',
  'MEMBER_UNAVAILABLE',
] as const

export type WecomOAuthErrorCode = typeof OAUTH_ERROR_CODES[number]

export type WecomBindingEntry = {
  flow?: 'legacy' | 'directory'
  token?: string
  exchangeCode?: string
  errorCode?: WecomOAuthErrorCode
  result?: 'pending' | 'conflict' | 'failed' | 'expired'
  securityError?: string
}

let consumed = false
let cached: WecomBindingEntry | undefined

/** Consumes an enrollment token from the hash once, then clears it before any API call. */
export function consumeWecomBindingEntry(location: Location = window.location): WecomBindingEntry | undefined {
  if (consumed) return cached
  consumed = true
  const url = new URL(location.href)
  const fragment = url.hash.startsWith('#') ? url.hash.slice(1) : url.hash
  const route = new URL(fragment || '/', url.origin)

  if (route.pathname === '/wecom-onboarding') {
    const token = route.searchParams.get('token')?.trim()
    const exchangeCode = route.searchParams.get('exchange_code')?.trim()
    const errorCode = route.searchParams.get('error_code')?.trim()
    const unexpected = [...route.searchParams.keys()].find((key) => !['token', 'exchange_code', 'error_code'].includes(key))
    const suppliedCredentialCount = [token, exchangeCode, errorCode].filter(Boolean).length
    window.history.replaceState(null, '', `${url.pathname}${url.search}#/wecom-onboarding`)
    if (unexpected || suppliedCredentialCount !== 1) {
      cached = { flow: 'directory', securityError: '企业微信入职凭证缺失、重复或包含非预期参数，系统已清除地址栏信息。' }
    } else if (token && TOKEN_PATTERN.test(token)) {
      cached = { flow: 'directory', token }
    } else if (exchangeCode && /^[A-Za-z0-9._~-]{16,512}$/.test(exchangeCode)) {
      cached = { flow: 'directory', exchangeCode }
    } else if (errorCode && OAUTH_ERROR_CODES.includes(errorCode as WecomOAuthErrorCode)) {
      cached = { flow: 'directory', errorCode: errorCode as WecomOAuthErrorCode }
    } else {
      cached = { flow: 'directory', securityError: '企业微信入职凭证或结果无效，请从企业微信重新打开。' }
    }
    return cached
  }

  if (route.pathname === '/wecom-binding-result') {
    const status = route.searchParams.get('status')
    cached = status === 'conflict' ? { result: 'conflict' }
      : status === 'failed' ? { result: 'failed' }
        : status === 'expired' ? { result: 'expired' } : { result: 'pending' }
    return cached
  }
  if (route.pathname !== '/wecom-bind') return undefined

  const token = route.searchParams.get('token')?.trim()
  const unexpected = [...route.searchParams.keys()].find((key) => key !== 'token')
  window.history.replaceState(null, '', `${url.pathname}${url.search}#/wecom-bind`)
  if (unexpected || !token || !TOKEN_PATTERN.test(token)) {
    cached = { securityError: '绑定邀请缺失、格式无效或包含非预期参数。系统已清除地址栏中的邀请信息。' }
  } else {
    cached = { flow: 'legacy', token }
  }
  return cached
}
