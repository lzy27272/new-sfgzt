import { apiRequest, apiBlob, type ApiIdentity } from '../../api/client'

export function financeRequest<T>(identity: ApiIdentity, path: string, method = 'GET', body?: unknown): Promise<T> {
  return apiRequest<T>(`/finance${path}`, identity, { method, body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(body instanceof FormData ? 120000 : 25000) })
}
export async function financeDownload(identity: ApiIdentity, path: string, name: string) {
  const blob = await apiBlob(`/finance${path}`, identity, { signal: AbortSignal.timeout(30000) })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a'); anchor.href = url; anchor.download = name; anchor.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
export function errorMessage(error: unknown) { return error instanceof Error ? error.message : '操作未完成，请重试。已保存的本机草稿不会被清空。' }
