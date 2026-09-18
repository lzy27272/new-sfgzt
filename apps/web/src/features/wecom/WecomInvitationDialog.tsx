import { useEffect, useState } from 'react'
import QRCode from 'qrcode'
import type { Invitation } from './bindingAdminApi'
import { invitationExpiryLabel, invitationValidity, type InvitationMode } from './invitationValidity'
import styles from './wecomBinding.module.css'

type Props = {
  invitation: Invitation
  mode: InvitationMode
  regenerating: boolean
  error?: string
  onClose: () => void
  onRegenerate?: () => void
}

export function WecomInvitationDialog({ invitation, mode, regenerating, error, onClose, onRegenerate }: Props) {
  const [now, setNow] = useState(Date.now)
  const [qrData, setQrData] = useState<string>()
  const [feedback, setFeedback] = useState<string>()
  const [localError, setLocalError] = useState<string>()
  const validity = invitationValidity(invitation.expiresAt, mode, now)

  useEffect(() => {
    const update = () => setNow(Date.now())
    const timer = window.setInterval(update, 1000)
    window.addEventListener('focus', update)
    window.addEventListener('pageshow', update)
    document.addEventListener('visibilitychange', update)
    return () => {
      window.clearInterval(timer)
      window.removeEventListener('focus', update)
      window.removeEventListener('pageshow', update)
      document.removeEventListener('visibilitychange', update)
    }
  }, [])

  useEffect(() => {
    let active = true
    void QRCode.toDataURL(invitation.enrollmentUrl, { width: 232, margin: 2, errorCorrectionLevel: 'M' })
      .then((value) => { if (active) setQrData(value) })
      .catch(() => { if (active) setLocalError('二维码生成失败，可复制邀请链接继续') })
    return () => { active = false }
  }, [invitation.enrollmentUrl])

  const canSendNow = () => {
    if (regenerating) return false
    if (invitationValidity(invitation.expiresAt, mode).expired) {
      setNow(Date.now())
      setLocalError('邀请已过期，请重新生成后再发送。')
      return false
    }
    setLocalError(undefined); setFeedback(undefined)
    return true
  }
  const copy = async () => {
    if (!canSendNow()) return
    try {
      await navigator.clipboard.writeText(invitation.enrollmentUrl)
      setFeedback('邀请链接已复制，请发送新链接给员工。')
    } catch { setLocalError('复制失败，请手动选择链接后复制。') }
  }
  const share = async () => {
    if (!canSendNow()) return
    if (!navigator.share) { await copy(); return }
    try {
      await navigator.share({
        title: mode === 'onboarding' ? '四方馆中台新员工注册邀请' : '四方馆中台企业微信绑定邀请',
        text: '请使用企业微信打开链接完成身份验证。有效期至 ' + invitationExpiryLabel(invitation.expiresAt) + '（北京时间）。',
        url: invitation.enrollmentUrl,
      })
    } catch (reason) {
      if (reason instanceof DOMException && reason.name === 'AbortError') return
      setLocalError('系统分享未完成，可改用复制链接发送。')
    }
  }

  return <div className="modal-backdrop"><section className={`modal ${styles.invitation}`} role="dialog" aria-modal="true" aria-labelledby="wecom-invitation-title">
    <header><div><span className="panel-kicker">{mode === 'onboarding' ? 'SHARED INVITATION' : 'PERSONAL INVITATION'}</span><h2 id="wecom-invitation-title">{mode === 'onboarding' ? '员工一键邀请' : '员工绑定邀请'}</h2></div><button className="close" disabled={regenerating} onClick={onClose} aria-label="关闭邀请">×</button></header>
    <div className={styles.invitationBody}>
      <div className={styles.invitationValidity} data-tone={validity.expired ? 'expired' : validity.expiringSoon ? 'warning' : 'valid'} role="status">
        <strong>{validity.expired ? '邀请已过期，请重新生成' : validity.expiringSoon ? '邀请即将到期' : '邀请有效'}</strong>
        <span>{validity.remainingText}</span>
        <small>有效期至 {invitationExpiryLabel(invitation.expiresAt)}（北京时间）</small>
      </div>
      {validity.expired || regenerating ? <div className={styles.expiredQr}><strong>{regenerating ? '正在生成新邀请…' : '此二维码已停止展示'}</strong><span>{regenerating ? '请稍候，生成完成后发送新码。' : '请重新生成并发送新二维码，勿继续使用旧截图。'}</span></div>
        : qrData ? <img src={qrData} alt={mode === 'onboarding' ? '企业微信员工邀请二维码' : '企业微信人员绑定二维码'}/> : <div className="spinner"/>}
      <strong>请员工使用企业微信扫码或打开链接</strong>
      <small>{mode === 'onboarding' ? '同一二维码在有效期内可供多名员工分别使用；每位员工验证企业微信身份后，独立填写手机号、姓名、账号、密码、集团总部/门店和岗位。' : '该邀请已指定现有中台账号，请员工核对本人身份并完成绑定，不需要重新注册。'}</small>
      <small>{mode === 'onboarding' ? '员工提交不会使二维码失效；仅在超过有效时间后失效。重新生成只产生新码，旧码仍按原时间到期，不会自动延长。' : '个人绑定邀请到期后，请关闭此窗口并从员工对应的绑定操作重新发起。'}</small>
      <input readOnly value={validity.expired || regenerating ? '' : invitation.enrollmentUrl} disabled={validity.expired || regenerating} aria-label="企业微信邀请链接" placeholder="重新生成后显示新链接"/>
      {(error || localError) && <div className="inline-error" role="alert">{error || localError}</div>}
      {feedback && <div className="inline-success" role="status">{feedback}</div>}
      <div className={styles.invitationActions}>
        <button className="secondary" disabled={validity.expired || regenerating} onClick={() => void copy()}>复制链接</button>
        <button className="primary" disabled={validity.expired || regenerating} onClick={() => void share()}>发送给员工</button>
        {onRegenerate && <button className="secondary" disabled={regenerating} onClick={() => { setLocalError(undefined); setFeedback(undefined); onRegenerate() }}>{regenerating ? '正在生成…' : '重新生成邀请'}</button>}
      </div>
    </div>
  </section></div>
}
