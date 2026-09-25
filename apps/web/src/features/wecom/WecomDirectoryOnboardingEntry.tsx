import { useEffect, useMemo, useState } from 'react'
import { product } from '../../product'
import type { WecomBindingEntry } from './bindingEntryRoute'
import {
  exchangeDirectoryOnboarding,
  loadDirectoryOnboardingContext,
  startDirectoryOnboarding,
  submitDirectoryOnboarding,
  type DirectoryOnboardingContext,
} from './directoryOnboardingApi'
import { validateDirectoryOnboardingRegistration } from './directoryOnboardingRegistration'
import { SelfRegistrationButton } from './SelfRegistrationButton'

type Selection = { orgUnitId: string; positionId: string }

const oauthErrors = {
  ACCOUNT_READY: { title: '已有中台账号', message: '该企业微信身份已关联中台账号，无需重复注册。请返回使用账号密码登录。' },
  MEMBER_UNAVAILABLE: { title: '身份暂不可用', message: '该成员存在停用或解绑记录，不能自行重新注册。请联系人事核对后恢复。' },
  OAUTH_SESSION_INVALID: {
    title: '验证会话已失效',
    message: '本次入职邀请已过期或验证会话无效。请从企业微信重新打开仍在有效期内的邀请。',
  },
  OAUTH_IDENTITY_MISMATCH: {
    title: '企业微信身份不匹配',
    message: '本次验证身份与邀请对应人员不一致，系统未创建绑定。请联系管理员核对人员与企业身份。',
  },
  OAUTH_PROVIDER_UNAVAILABLE: {
    title: '企业微信暂时不可用',
    message: '企业微信身份服务暂时无法完成验证。请稍后从原邀请重新进入；持续失败时联系管理员重试。',
  },
  OAUTH_VERIFICATION_FAILED: {
    title: '身份验证未完成',
    message: '企业微信未能完成本次身份验证，系统未创建绑定。请从原邀请重试或联系管理员。',
  },
} as const

export function WecomDirectoryOnboardingEntry({ entry, onReturn }: { entry: WecomBindingEntry; onReturn: () => void }) {
  const [sessionToken, setSessionToken] = useState<string>()
  const [context, setContext] = useState<DirectoryOnboardingContext>()
  const [organizationId, setOrganizationId] = useState('')
  const [selection, setSelection] = useState<Selection>({ orgUnitId: '', positionId: '' })
  const [displayName, setDisplayName] = useState('')
  const [mobile, setMobile] = useState('')
  const [loginName, setLoginName] = useState('')
  const [password, setPassword] = useState('')
  const [passwordConfirmation, setPasswordConfirmation] = useState('')
  const [validationAttempted, setValidationAttempted] = useState(false)
  const [submittedStatus, setSubmittedStatus] = useState<'PENDING_APPROVAL' | 'CONFLICT'>()
  const [busy, setBusy] = useState(Boolean(entry.exchangeCode))
  const [error, setError] = useState(entry.securityError)
  const oauthError = entry.errorCode ? oauthErrors[entry.errorCode] : undefined

  useEffect(() => {
    if (!entry.exchangeCode) return
    let active = true
    setBusy(true); setError(undefined)
    void exchangeDirectoryOnboarding(entry.exchangeCode)
      .then(async (exchange) => ({ exchange, context: await loadDirectoryOnboardingContext(exchange.sessionToken) }))
      .then(({ exchange, context: value }) => {
        if (!active) return
        setSessionToken(exchange.sessionToken)
        setContext(value)
        setDisplayName(value.displayName ?? '')
        setLoginName(value.loginName ?? '')
        const firstOrganization = value.hotels[0]
        setOrganizationId(firstOrganization?.id ?? '')
      })
      .catch((reason) => { if (active) setError(reason instanceof Error ? reason.message : '企业微信身份验证失败') })
      .finally(() => { if (active) setBusy(false) })
    return () => { active = false }
  }, [entry.exchangeCode])

  const organization = context?.hotels.find((item) => item.id === organizationId)
  const positionOptions = useMemo(() => (organization?.departments ?? []).flatMap((department) => department.positions.map((position) => ({
    orgUnitId: department.id,
    positionId: position.id,
    label: department.id === organization?.id ? position.name : `${department.name} · ${position.name}`,
    selectable: position.selectable,
  }))).filter((position) => position.selectable), [organization])
  const hasOrganizationOptions = Boolean(context?.hotels.length)
  const registrationValidation = validateDirectoryOnboardingRegistration({
    requiresAccountRegistration: Boolean(context?.requiresAccountRegistration),
    displayName, mobile, loginName, secret: password, secretConfirmation: passwordConfirmation,
    orgUnitId: selection.orgUnitId,
  })

  const start = async () => {
    if (!entry.token) return
    setBusy(true); setError(undefined)
    try { window.location.assign(await startDirectoryOnboarding(entry.token)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '无法打开企业微信身份验证'); setBusy(false) }
  }

  const submit = async () => {
    setValidationAttempted(true)
    if (!context || !sessionToken) {
      setError('验证会话已失效，请从最新邀请重新进入')
      return
    }
    if (!registrationValidation.valid) return
    setBusy(true); setError(undefined)
    try {
      const result = await submitDirectoryOnboarding(
        sessionToken, displayName.trim(), registrationValidation.normalizedMobile, loginName.trim(), password, passwordConfirmation,
        selection.orgUnitId, selection.positionId || undefined, context.rowVersion,
      )
      setPassword(''); setPasswordConfirmation('')
      setSubmittedStatus(result.status === 'CONFLICT' ? 'CONFLICT' : 'PENDING_APPROVAL')
      setSessionToken(undefined)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '入职申请提交失败') }
    finally { setBusy(false) }
  }

  if (oauthError) return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card" aria-live="polite">
      <div className="inline-error"><strong>{oauthError.title}</strong><p>{oauthError.message}</p></div>
      <button className="secondary" onClick={onReturn}>返回中台登录</button>
      {entry.errorCode !== 'MEMBER_UNAVAILABLE' && entry.errorCode !== 'ACCOUNT_READY' ? <SelfRegistrationButton label="重新验证 / 查询申请进度" /> : null}
      <small className="onboarding-privacy">系统未创建人员绑定，也不会开启企业微信群推送；地址栏中的验证结果已清除。</small>
    </section>
  </main>

  if (submittedStatus || context?.status === 'PENDING_APPROVAL' || context?.status === 'CONFLICT') {
    const conflict = submittedStatus === 'CONFLICT' || context?.status === 'CONFLICT'
    return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card submitted" aria-live="polite">
      <div className="onboarding-success" aria-hidden="true">{conflict ? '!' : '✓'}</div><h1>申请已提交</h1><h2>{conflict ? '身份关联异常，等待管理员核对' : '等待管理员确认'}</h2>
      <p>{conflict ? '系统发现该企业微信身份已有受控关联记录，人事审核前不会启用；您无需重复提交。' : '行政人事审核通过后，注册账号、任职权限与企业微信绑定会同时启用。'}</p>
      <dl><div><dt>申请组织</dt><dd>{submittedStatus ? organization?.name ?? '—' : context?.requestedOrganizationName ?? '—'}</dd></div><div><dt>岗位</dt><dd>{submittedStatus ? (selection.positionId ? positionOptions.find((item) => item.positionId === selection.positionId)?.label ?? '—' : '岗位待分配') : context?.requestedPositionName ?? '岗位待分配'}</dd></div><div><dt>状态</dt><dd>{conflict ? '异常待核对' : '待审核'}</dd></div></dl>
      <SelfRegistrationButton label="重新验证并查询审核进度" />
      <button className="primary" onClick={onReturn}>返回账号密码登录</button>
    </section>
  </main>
  }

  return <main className="wecom-onboarding-shell">
    <header className="wecom-onboarding-brand"><span>四</span><strong>{product.name}</strong></header>
    <section className="wecom-onboarding-card" aria-live="polite">
      <h1>{context ? '完成入职绑定' : '企业微信入职验证'}</h1>
      <p>{context ? (context.requiresAccountRegistration ? '企业微信身份已验证，请填写手机号、注册账号并补充任职信息。' : '企业微信身份已验证，请补充任职信息。') : '系统只会核验您本人的企业微信身份，不公开其他员工信息。'}</p>
      {context && <>
        <div className="onboarding-person"><i aria-hidden="true">人</i><span><strong>{context.requiresAccountRegistration ? '新员工注册' : context.displayName}</strong><small>企业微信成员</small></span><b>● 身份已验证</b></div>
        {context.requiresAccountRegistration && <div className="onboarding-registration-fields">
          <label>个人姓名<input value={displayName} maxLength={120} autoComplete="name" aria-invalid={Boolean(registrationValidation.errors.displayName && (validationAttempted || displayName))} onChange={(event) => setDisplayName(event.target.value)} placeholder="请输入真实姓名" />{validationAttempted && registrationValidation.errors.displayName && <small className="field-error">{registrationValidation.errors.displayName}</small>}</label>
          <label>手机号<input type="tel" value={mobile} maxLength={20} inputMode="numeric" autoComplete="tel" aria-invalid={Boolean(registrationValidation.errors.mobile && (validationAttempted || mobile))} onChange={(event) => setMobile(event.target.value)} placeholder="请输入本人11位手机号" />{(validationAttempted || Boolean(mobile)) && registrationValidation.errors.mobile && <small className="field-error">{registrationValidation.errors.mobile}</small>}</label>
          <label>登录账号<input value={loginName} maxLength={120} autoCapitalize="none" autoComplete="username" aria-invalid={Boolean(registrationValidation.errors.loginName && (validationAttempted || loginName))} onChange={(event) => setLoginName(event.target.value)} placeholder="3位以上字母、数字、点、下划线或短横线" />{(validationAttempted || Boolean(loginName)) && registrationValidation.errors.loginName && <small className="field-error">{registrationValidation.errors.loginName}</small>}</label>
          <label>登录密码<input type="password" value={password} minLength={10} maxLength={128} autoComplete="new-password" aria-invalid={Boolean(registrationValidation.errors.credential && (validationAttempted || password))} onChange={(event) => setPassword(event.target.value)} placeholder="10至128位" />{(validationAttempted || Boolean(password)) && registrationValidation.errors.credential && <small className="field-error">{registrationValidation.errors.credential}</small>}</label>
          <label>确认密码<input type="password" value={passwordConfirmation} minLength={10} maxLength={128} autoComplete="new-password" aria-invalid={Boolean(registrationValidation.errors.passwordConfirmation && (validationAttempted || passwordConfirmation))} onChange={(event) => setPasswordConfirmation(event.target.value)} placeholder="请再次输入密码" />{(validationAttempted || Boolean(passwordConfirmation)) && registrationValidation.errors.passwordConfirmation && <small className="field-error">{registrationValidation.errors.passwordConfirmation}</small>}</label>
        </div>}
        {!hasOrganizationOptions && <div className="inline-error"><strong>暂无可申请组织</strong><p>当前没有启用中的集团总部或门店，请联系行政人事核对组织配置。</p></div>}
        <label>选择组织<select value={organizationId} disabled={!hasOrganizationOptions} onChange={(event) => { setOrganizationId(event.target.value); setSelection({ orgUnitId: '', positionId: '' }) }}><option value="">{hasOrganizationOptions ? '请选择集团总部或门店' : '暂无可申请组织'}</option>{context.hotels.map((item) => <option key={item.id} value={item.id}>{item.unitType === 'GROUP' ? '集团总部' : item.name}</option>)}</select></label>
        <label>选择岗位<select value={`${selection.orgUnitId}:${selection.positionId}`} disabled={!organizationId} aria-invalid={Boolean(validationAttempted && registrationValidation.errors.assignment)} onChange={(event) => { const [orgUnitId, positionId] = event.target.value.split(':'); setSelection({ orgUnitId, positionId }) }}><option value=":">请选择岗位</option>{organization && <option value={`${organization.id}:`}>岗位待分配</option>}{positionOptions.map((item) => <option key={`${item.orgUnitId}:${item.positionId}`} value={`${item.orgUnitId}:${item.positionId}`}>{item.label}</option>)}</select>{validationAttempted && registrationValidation.errors.assignment && <small className="field-error">{registrationValidation.errors.assignment}</small>}</label>
        {organizationId && <small className="onboarding-note">这里只展示后台已允许员工申请的岗位；如暂不确定，请选择“岗位待分配”，由审核员在审批时补充。集团受保护岗位不能自行申请，需由具备任职管理权限的管理员审核分配。</small>}
        <small className="onboarding-note">提交后由行政人事或行政人事主管审核；审核前账号不可登录，也不会开通岗位权限。</small>
      </>}
      {busy && !context && <div className="wecom-entry-progress"><div className="spinner"/><strong>正在验证企业微信身份</strong></div>}
      {validationAttempted && !registrationValidation.valid && <div className="inline-error" role="alert"><strong>提交信息尚未完整</strong><p>请按页面红色提示修正后再次提交。</p></div>}
      {error && <div className="inline-error">{error}</div>}
      {context ? <button className="primary" disabled={busy} onClick={() => void submit()}>{busy ? '正在提交…' : '提交审核'}</button>
        : entry.token && !busy ? <button className="primary" onClick={() => void start()}>使用企业微信验证身份</button> : null}
      {error && <button className="secondary" onClick={onReturn}>返回</button>}
      <small className="onboarding-privacy">绑定成功不会自动开启企业微信群推送；UserID不会在页面、通知或审计中显示。</small>
    </section>
  </main>
}
