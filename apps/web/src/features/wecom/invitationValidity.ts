export type InvitationMode = 'binding' | 'onboarding'

export function invitationValidity(expiresAt: string, mode: InvitationMode, now = Date.now()) {
  const remainingMs = Date.parse(expiresAt) - now
  if (!Number.isFinite(remainingMs) || remainingMs <= 0) {
    return { expired: true, expiringSoon: false, remainingText: '邀请已过期或有效期无效' }
  }
  const minutes = Math.ceil(remainingMs / 60_000)
  const days = Math.floor(minutes / 1440)
  const hours = Math.floor((minutes % 1440) / 60)
  const restMinutes = minutes % 60
  const duration = [days && days + '天', hours && hours + '小时', restMinutes && restMinutes + '分钟'].filter(Boolean).join('')
  return {
    expired: false,
    expiringSoon: remainingMs <= (mode === 'onboarding' ? 24 * 60 : 30) * 60_000,
    remainingText: remainingMs < 60_000 ? '剩余不足1分钟' : '剩余' + duration,
  }
}

export function invitationExpiryLabel(expiresAt: string) {
  const date = new Date(expiresAt)
  if (!Number.isFinite(date.getTime())) return '有效期无效'
  return new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  }).format(date)
}
