import { useState } from 'react'
import { startSelfServiceOnboarding } from './directoryOnboardingApi'

/** Starts a fresh, browser-bound OAuth check; URL error messages grant no identity. */
export function SelfRegistrationButton({ label = '申请注册 / 查询申请进度' }: { label?: string }) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const start = async () => {
    setBusy(true); setError(undefined)
    try { window.location.assign(await startSelfServiceOnboarding()) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '无法开始身份验证，请稍后重试'); setBusy(false) }
  }
  return <>
    <button type="button" className="secondary wecom-entry-action" disabled={busy} onClick={() => void start()}>{busy ? '正在打开企业微信验证…' : label}</button>
    {error ? <div className="inline-error" role="alert">{error}</div> : null}
    <small>新员工请在企业微信内申请注册；人事审核通过后，可用账号密码登录。</small>
  </>
}
