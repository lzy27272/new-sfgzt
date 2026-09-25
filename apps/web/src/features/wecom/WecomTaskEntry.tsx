import { useEffect, useState } from 'react'
import { establishFederatedSession } from '../../api/client'
import { product } from '../../product'
import { SelfRegistrationButton } from './SelfRegistrationButton'
import { exchangeWecomCode } from './api'
import { buildAppHashLocation, safeTaskDeepLink, wecomLoginRestartPath, type WecomTaskEntry } from './entryRoute'

type Props = {
  entry: WecomTaskEntry
  onAuthenticated: () => void
  onCancel: () => void
}

export function WecomTaskEntryPage({ entry, onAuthenticated, onCancel }: Props) {
  const [error, setError] = useState(entry.securityError)

  useEffect(() => {
    if (entry.securityError || !entry.code) return
    let active = true
    void exchangeWecomCode(entry.code)
      .then((session) => {
        if (!active) return
        const target = safeTaskDeepLink(session.returnTo)
        establishFederatedSession(session.accessToken, session.expiresAt)
        window.history.replaceState(null, '', buildAppHashLocation(target, import.meta.env.BASE_URL))
        onAuthenticated()
      })
      .catch((reason) => {
        if (!active) return
        setError(reason instanceof Error ? reason.message : '企微身份交换失败，请从企业微信重新打开中台。')
      })
    return () => { active = false }
  }, [entry, onAuthenticated])

  return <main className="login-screen wecom-entry-screen">
    <section className="login-brand"><div className="login-logo">四</div><div><span className="eyebrow">WECOM SECURE ENTRY</span><h1>{product.name}</h1><p>正在通过企业微信确认成员身份。一次性凭证不会保存在浏览器地址、历史记录或本地存储中。</p></div></section>
    <section className="login-card wecom-entry-card" aria-live="polite">
      <header><span className="panel-kicker">企业微信安全入口</span><h2>{error ? '无法安全打开中台' : '正在验证身份'}</h2><p>{error ? '系统已停止登录和页面跳转。' : '验证成功后将直接进入对应的中台页面。'}</p></header>
      {error ? <>
        <div className="inline-error">{error}</div>
        <a className="primary wecom-entry-action" href={wecomLoginRestartPath()}>重新通过企业微信验证</a>
        <button className="secondary wecom-entry-action" type="button" onClick={onCancel}>返回中台登录</button>
        <SelfRegistrationButton />
        <small>请勿转发包含一次性凭证的链接，也不要向任何人提供企业微信验证码。</small>
      </> : <>
        <div className="wecom-entry-progress"><div className="spinner" /><strong>正在建立安全会话</strong><span>请保持页面打开，无需输入中台密码。</span></div>
        <button className="secondary wecom-entry-action" type="button" onClick={onCancel}>使用账号密码登录</button>
        <small>一次性凭证使用后立即失效；权限仍由中台组织、岗位和角色校验。</small>
      </>}
    </section>
  </main>
}
