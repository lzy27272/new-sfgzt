import { ApiError, apiBase } from '../../api/client'
import { withRequestDeadline } from '../../api/requestDeadline'

const EXCHANGE_PATH = import.meta.env.VITE_WECOM_EXCHANGE_PATH ?? '/integrations/wecom/oauth/exchange'
const exchangeRequests = new Map<string, Promise<WecomExchangeResult>>()

type WecomSessionResponse = {
  authenticated?: boolean
  accessToken?: string
  tokenType?: string
  expiresAt?: string
  returnTo?: string
}

export type WecomExchangeResult = {
  accessToken?: string
  expiresAt?: string
  returnTo?: string
}

async function performExchange(code: string, signal: AbortSignal): Promise<WecomExchangeResult> {
  const response = await fetch(`${apiBase}${EXCHANGE_PATH.startsWith('/') ? EXCHANGE_PATH : `/${EXCHANGE_PATH}`}`, {
    method: 'POST',
    signal,
    credentials: 'include',
    cache: 'no-store',
    redirect: 'error',
    referrerPolicy: 'no-referrer',
    headers: {
      Accept: 'application/json',
      'Content-Type': 'application/json',
      'Cache-Control': 'no-store',
      'X-Correlation-Id': crypto.randomUUID(),
    },
    body: JSON.stringify({ exchangeCode: code }),
  })

  if (!response.ok) {
    const problem = await response.json().catch(() => ({ detail: response.statusText })) as Record<string, unknown>
    const fallback = response.status === 401 || response.status === 410
      ? '企微一次性凭证已失效或已被使用，请返回企微重新打开任务。'
      : response.status === 403
        ? '当前企微成员未绑定有效的中台账号或岗位，无法打开此任务。'
        : '企微身份交换失败，请稍后从企微重新打开任务。'
    throw new ApiError(response.status, String(problem.detail ?? problem.message ?? fallback), problem)
  }

  const session = await response.json().catch(() => ({})) as WecomSessionResponse
  if (session.accessToken !== undefined && (typeof session.accessToken !== 'string' || !session.accessToken.trim())) {
    throw new ApiError(502, '服务端返回了无效的用户会话，系统已拒绝登录。')
  }
  if (session.accessToken && session.tokenType && session.tokenType.toLowerCase() !== 'bearer') {
    throw new ApiError(502, '服务端返回了不受支持的会话类型，系统已拒绝登录。')
  }
  if (!session.accessToken && session.authenticated !== true) {
    throw new ApiError(502, '服务端未确认企微会话已经建立，系统未进入任务页面。')
  }

  return { accessToken: session.accessToken, expiresAt: session.expiresAt, returnTo: session.returnTo }
}

export function exchangeWecomCode(code: string): Promise<WecomExchangeResult> {
  const pending = exchangeRequests.get(code)
  if (pending) return pending
  const request = withRequestDeadline((signal) => performExchange(code, signal),
    '企微身份验证超时，请检查网络后重新通过企业微信验证。请勿重复使用旧的一次性凭证。')
  exchangeRequests.set(code, request)
  return request
}
