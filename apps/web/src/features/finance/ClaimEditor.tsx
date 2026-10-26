import { useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { financeRequest, errorMessage } from './api'
import { loadFinanceDraft, removeFinanceDraft, saveFinanceDraft } from './encryptedDrafts'
import { canWithdrawClaim, cents, claimLineAmount, claimStatusLabel, differs, draftFromClaim, draftValidation, evidenceLabels, financeScope, invoiceTotal, money, type Claim, type ClaimLine, type DraftForm, type Evidence, type EvidenceKind, type FinanceContext, type LocalDraft } from './model'
import { exactDecimalTotal } from './unifiedModel'
import { EvidencePreview } from './EvidencePreview'
import { ReviewHistory } from './ReviewHistory'
import { ProjectPicker } from './ProjectPicker'
import s from './finance.module.css'

export function ClaimEditor({ initial, context, identity, onChange, onBack }: { initial: Claim; context: FinanceContext; identity: ApiIdentity; onChange: () => void; onBack: () => void }) {
  const scope = financeScope(identity)
  const [claim, setClaim] = useState(initial)
  const [local, setLocal] = useState<LocalDraft>({ form: draftFromClaim(initial), files: [], dirty: false })
  const current = useRef(local); const [ready, setReady] = useState(false); const [online, setOnline] = useState(navigator.onLine)
  const [busy, setBusy] = useState(''); const lock = useRef(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const [localTime, setLocalTime] = useState<number>(); const [localError, setLocalError] = useState(''); const [conflict, setConflict] = useState(false)
  const canPersist = useRef(false)
  const syncRef = useRef<() => void>(() => undefined)
  const retain = async (value: LocalDraft) => {
    if (!canPersist.current) { setLocalError('本机旧草稿尚未成功读取，已停止覆盖本机存储。请保持页面打开并保存云端，或明确选择使用云端版本。'); return }
    try { const time = await saveFinanceDraft(scope, claim.id, value); setLocalTime(time); setLocalError('') }
    catch (e) { setLocalError(`本机草稿未保存：${errorMessage(e)}。请勿关闭页面。`) }
  }
  const update = (value: LocalDraft) => { current.current = value; setLocal(value); void retain(value) }
  const change = (patch: Partial<DraftForm>) => update({ ...current.current, dirty: true, form: { ...current.current.form, ...patch } })
  useEffect(() => {
    let alive = true
    void loadFinanceDraft(scope, claim.id).then(saved => {
      if (!alive) return
      canPersist.current = true
      if (!saved || !claim.canEdit) return
      if (saved.dirty) {
        current.current = saved; setLocal(saved); setLocalTime(saved.savedAt)
        setConflict(saved.form.expectedVersion !== claim.version)
        setNotice(saved.form.expectedVersion === claim.version ? '已恢复本机未完成内容和待上传凭证。' : '云端已有更新，本机草稿已保留。请核对后选择版本，系统不会自动覆盖。')
      }
    }).catch(e => { if (alive) setLocalError(errorMessage(e)) }).finally(() => { if (alive) setReady(true) })
    return () => { alive = false }
  }, [scope, claim.id])
  useEffect(() => {
    const reconnect = () => { setOnline(true); syncRef.current() }
    const disconnect = () => setOnline(false)
    const warn = (event: BeforeUnloadEvent) => { if (current.current.dirty) { event.preventDefault(); event.returnValue = '' } }
    window.addEventListener('online', reconnect); window.addEventListener('offline', disconnect); window.addEventListener('beforeunload', warn)
    return () => { window.removeEventListener('online', reconnect); window.removeEventListener('offline', disconnect); window.removeEventListener('beforeunload', warn) }
  }, [])
  const sync = async (submit = false): Promise<Claim | undefined> => {
    if (lock.current || !ready || conflict || !claim.canEdit) return
    if (!navigator.onLine) { setError('当前离线：内容仅保存在本机，联网后才可保存到云端或提交审核。'); return }
    lock.current = true; setBusy('正在保存云端草稿…'); setError(''); setNotice('')
    try {
      // Save locally before any network action; network failure must not discard selected File objects.
      await retain(current.current)
      for (const file of [...current.current.files]) {
        if (file.kind === 'INVOICE' && current.current.form.invoices.length >= 30 && !current.current.form.invoices.some(i => i.attachmentId === file.id)) throw new Error('每单最多30张发票明细，请移除多余发票后分单申请。待上传原件仍保留在本机。')
        setBusy(`正在上传 ${evidenceLabels[file.kind]}：${file.file.name}`)
        const body = new FormData(); body.append('file', file.file)
        const attached = await financeRequest<Evidence>(identity, `/claims/${claim.id}/attachments/${file.id}?kind=${file.kind}`, 'POST', body)
        setClaim(c => ({ ...c, attachments: [...c.attachments.filter(a => a.id !== attached.id), attached] }))
        let invoices = current.current.form.invoices
        if (file.kind === 'INVOICE') {
          const candidate = attached.extraction
          const found = invoices.some(i => i.attachmentId === file.id)
          if (found) invoices = invoices.map(i => i.attachmentId === file.id ? { ...i, invoiceCode: i.invoiceCode || candidate.invoiceCode || '', invoiceNumber: i.invoiceNumber || candidate.invoiceNumber || '', amount: i.amount || candidate.amount || '' } : i)
          else invoices = [...invoices, { id: crypto.randomUUID(), attachmentId: file.id, invoiceCode: candidate.invoiceCode ?? '', invoiceNumber: candidate.invoiceNumber ?? '', amount: candidate.amount ?? '', buyerTaxNumber: '', confirmed: false }]
        }
        update({ ...current.current, form: { ...current.current.form, invoices }, files: current.current.files.filter(f => f.id !== file.id), dirty: true })
      }
      const validation = submit ? draftValidation(current.current.form) : [current.current.form.transactionAmount, current.current.form.paymentAmount, current.current.form.requestedAmount].some(value => cents(value) === null) ? '金额请填写非负数字，最多两位小数' : undefined
      if (validation) { setError(validation); setNotice('已成功上传的凭证保留在云端；未完成的表单内容保留在本机。请核对识别候选值并补充明细。'); return }
      setBusy(submit ? '正在保存并提交审核…' : '正在保存云端草稿…')
      const saved = await financeRequest<Claim>(identity, `/claims/${claim.id}`, 'PUT', { ...current.current.form, invoices: current.current.form.invoices.map(invoice => ({ ...invoice, amount: invoice.amount === '' ? null : invoice.amount })), items: current.current.form.items?.map(item => ({ ...item, quantity: item.quantity === '' ? null : item.quantity, unitPrice: item.unitPrice === '' ? null : item.unitPrice, amount: item.amount === '' ? null : item.amount })) })
      setClaim(saved); update({ form: draftFromClaim(saved), files: [], dirty: false })
      if (submit) {
        const sent = await financeRequest<Claim>(identity, `/claims/${claim.id}/submit`, 'POST', { expectedVersion: saved.version })
        setClaim(sent); current.current = { form: draftFromClaim(sent), files: [], dirty: false }; setLocal(current.current)
        await removeFinanceDraft(scope, claim.id); setNotice('已提交：初审 → 门店财务 → 财务总监 → CEO终审依次审核。'); onChange(); return sent
      }
      setNotice('草稿已保存到云端，尚未提交审核。'); onChange(); return saved
    } catch (e) {
      setError(errorMessage(e))
      if (typeof e === 'object' && e && 'status' in e && e.status === 409) setNotice('操作冲突时不会覆盖旧单据；请刷新云端记录核对，本机草稿仍保留。')
    } finally { lock.current = false; setBusy('') }
  }
  syncRef.current = () => { if (current.current.dirty && !conflict) void sync(false) }
  const addFiles = (kind: EvidenceKind, selected: FileList | null) => {
    if (!selected) return
    const files = Array.from(selected)
    if (files.some(f => f.size <= 0 || f.size > 20 * 1024 * 1024 || !/\.(pdf|ofd|xml|jpe?g|png)$/i.test(f.name))) { setError('仅支持20MB以内的PDF、OFD、XML、JPG和PNG；本次选择尚未添加。'); return }
    if (claim.attachments.length + current.current.files.length + files.length > 40) { setError('每单最多40份凭证。'); return }
    if (kind === 'INVOICE') {
      const referenced = new Set(current.current.form.invoices.map(i => i.attachmentId))
      const missing = current.current.files.filter(f => f.kind === 'INVOICE' && !referenced.has(f.id)).length
      if (current.current.form.invoices.length + missing + files.length > 30) { setError('每单最多30张发票明细，请分单申请。'); return }
    }
    if ([...files, ...current.current.files.map(f => f.file)].reduce((sum, f) => sum + f.size, 0) > 100 * 1024 * 1024) { setError('本机待上传凭证不能超过100MB，请先联网保存。'); return }
    update({ ...current.current, dirty: true, files: [...current.current.files, ...files.map(file => ({ id: crypto.randomUUID(), kind, file }))] }); setError('')
  }
  const addInvoice = (attachmentId: string) => {
    if (current.current.form.invoices.length >= 30) { setError('每单最多30张发票明细，请分单申请。'); return }
    const candidate = claim.attachments.find(a => a.id === attachmentId)?.extraction
    change({ invoices: [...current.current.form.invoices, { id: crypto.randomUUID(), attachmentId, invoiceCode: candidate?.invoiceCode ?? '', invoiceNumber: candidate?.invoiceNumber ?? '', amount: candidate?.amount ?? '', buyerTaxNumber: '', confirmed: false }] })
  }
  const discardLocal = async () => {
    if (!window.confirm('使用云端版本将清除此单本机未同步内容和待上传文件，确定吗？')) return
    setBusy('读取云端版本…')
    try { const latest = await financeRequest<Claim>(identity, `/claims/${claim.id}`); await removeFinanceDraft(scope, claim.id); canPersist.current = true; setLocalError(''); setClaim(latest); current.current = { form: draftFromClaim(latest), files: [], dirty: false }; setLocal(current.current); setConflict(false); setLocalTime(undefined); setNotice('已载入云端版本。') }
    catch (e) { setError(errorMessage(e)) } finally { setBusy('') }
  }
  const f = local.form
  const updateItems = (items: ClaimLine[]) => {
    const calculated = items.map(item => ({ ...item, amount: claimLineAmount(item.quantity, item.unitPrice) ?? '' }))
    change({ items: calculated, requestedAmount: exactDecimalTotal(calculated.map(item => item.amount)) ?? f.requestedAmount, categoryId: calculated[0]?.categoryId ?? f.categoryId })
  }
  const removeUploaded = async (id: string) => {
    if (current.current.form.invoices.some(i => i.attachmentId === id)) { setError('请先移除该文件的发票明细，再保存云端草稿，之后移除附件。'); return }
    if (!window.confirm('从草稿中移除此文件？已归档的原件不会从服务器删除。')) return
    const saved = await sync(false); if (!saved) return
    setBusy('移除草稿附件…')
    try { const latest = await financeRequest<Claim>(identity, `/claims/${claim.id}/attachments/${id}?expectedVersion=${saved.version}`, 'DELETE'); setClaim(latest); update({ form: draftFromClaim(latest), files: [], dirty: false }) }
    catch (e) { setError(errorMessage(e)) } finally { setBusy('') }
  }
  const withdraw = async () => {
    if (!window.confirm('撤回将结束此报销并释放发票占用，不能再提交此单。确定吗？')) return
    setBusy('撤回申请…')
    try { const latest = await financeRequest<Claim>(identity, `/claims/${claim.id}/withdraw`, 'POST', { expectedVersion: claim.version }); setClaim(latest); current.current = { form: draftFromClaim(latest), files: [], dirty: false }; setLocal(current.current); await removeFinanceDraft(scope, claim.id); setNotice('已撤回，发票可重新用于新的申请。'); onChange() }
    catch (e) { setError(errorMessage(e)) } finally { setBusy('') }
  }
  const invoiceSources = [...claim.attachments.filter(a => a.kind === 'INVOICE').map(a => ({ id: a.id, name: a.original_name })), ...local.files.filter(a => a.kind === 'INVOICE').map(a => ({ id: a.id, name: a.file.name }))]
  return <section className={s.editor}>
    <div className={s.heading}><div><small>REIMBURSEMENT</small><h2>{claim.canEdit ? '报销申请' : claim.title || '报销详情'}</h2></div><button type="button" onClick={onBack}>返回列表</button></div>
    <div className={s.identity}><strong>{claim.applicant_name} · {claim.hotel_name}</strong><span>{claim.company_name}</span><small>税号 {claim.company_tax_number} · {claimStatusLabel(claim)}</small></div>
    {claim.configurationIssues && claim.configurationIssues.length > 0 && <p className={s.warning}>待配置草稿：{claim.configurationIssues.join('；')}。可先保存资料，补齐配置后才能正式提交。</p>}
    {!online && <p className={s.warning}>离线模式 · 可继续填写和选择凭证。重新联网仅同步草稿，不会自动提交。</p>}
    {notice && <p role="status" className={s.info}>{notice}</p>}
    {error && <p role="alert" className={s.error}>{error}</p>}
    {conflict && <p className={s.warning}>本机与云端版本不一致，已暂停同步。<button type="button" onClick={() => void discardLocal()}>使用云端版本</button></p>}
    <fieldset disabled={!ready || Boolean(busy) || !claim.canEdit} className={s.form}>
      <label>报销事由<input value={f.title} maxLength={160} placeholder="例如：客房用品采购" onChange={e => change({ title: e.target.value })} /></label>
      <div className={s.pair}><label>业务日期<input type="date" value={f.businessDate} max={context.today} onChange={e => change({ businessDate: e.target.value })} /></label><label>费用类别<select value={f.categoryId ?? ''} onChange={e => change({ categoryId: e.target.value || null })}><option value="">请选择费用类别</option>{context.categories.filter(c => c.hotel_id === claim.hotel_id && c.enabled).map(c => <option key={c.id} value={c.id}>{c.name} · {treatmentLabel(c.treatment)}</option>)}</select></label></div>
      <div className={s.pair}><label>交易记录金额<input inputMode="decimal" value={f.transactionAmount} onChange={e => change({ transactionAmount: e.target.value })} /></label><label>实际支付金额<input inputMode="decimal" value={f.paymentAmount} onChange={e => change({ paymentAmount: e.target.value })} /></label></div>
      <label>申请报销金额<input inputMode="decimal" readOnly={!!f.items?.length} value={f.requestedAmount} onChange={e => change({ requestedAmount: e.target.value })} />{!!f.items?.length && <small>由费用明细精确合计，不可单独覆盖。</small>}</label>
      <h3>费用明细与归属</h3><p className={s.muted}>一单可填写多个类别。数量与单价最多四位小数，行金额按四舍五入保留两位，申请总额由明细合计。</p>
      {(f.items ?? []).map((line, index) => <div className={s.invoice} key={line.id}><div className={s.heading}><strong>费用明细 {index + 1} · {money(line.amount)}</strong><button type="button" onClick={() => updateItems((f.items ?? []).filter(item => item.id !== line.id))}>移除明细</button></div><label>费用说明<input value={line.description} maxLength={500} onChange={e => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, description: e.target.value } : item))} /></label><div className={s.pair}><label>费用类别<select value={line.categoryId ?? ''} onChange={e => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, categoryId: e.target.value || null } : item))}><option value="">请选择类别</option>{context.categories.filter(category => category.hotel_id === claim.hotel_id && category.enabled).map(category => <option key={category.id} value={category.id}>{category.name} · {treatmentLabel(category.treatment)}</option>)}</select></label>{(['quantity', 'unitPrice'] as const).map(key => <label key={key}>{key === 'quantity' ? '数量' : '单价'}<input inputMode="decimal" value={line[key]} onChange={e => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, [key]: e.target.value } : item))} /></label>)}<label>归属部门ID（可选）<input value={line.departmentId ?? ''} onChange={e => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, departmentId: e.target.value || null } : item))} /></label></div><label>关联本单发票（可多选）<select multiple value={line.invoiceIds ?? []} onChange={e => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, invoiceIds: Array.from(e.target.selectedOptions).map(option => option.value) } : item))}>{f.invoices.map(invoice => <option key={invoice.id} value={invoice.id}>{invoice.invoiceNumber || '号码待补'} · {money(invoice.amount)}</option>)}</select></label></div>)}
      {(f.items ?? []).map((line, index) => <div key={`project:${line.id}`}><strong>明细 {index + 1} · {line.description || '未填事由'} 的项目</strong><ProjectPicker identity={identity} hotelId={claim.hotel_id} kind={line.projectKind} projectId={line.projectId} onChange={(projectKind, projectId) => updateItems((f.items ?? []).map(item => item.id === line.id ? { ...item, projectKind, projectId } : item))} /></div>)}
      <button type="button" onClick={() => updateItems([...(f.items ?? []), { id: crypto.randomUUID(), categoryId: f.categoryId, description: '', quantity: '1', unitPrice: (f.items ?? []).length ? '0.00' : f.requestedAmount, amount: '0.00', departmentId: null, allocationMonth: null, invoiceIds: [], projectKind: 'NONE', projectId: null }])}>＋ 增加费用明细</button>
      <h3>上传凭证</h3><p className={s.muted}>三类原件分别保存，每份≤20MB。电子PDF可提取文字；照片需人工核对，不代表税务验真。</p>
      {(Object.keys(evidenceLabels) as EvidenceKind[]).map(kind => <div className={s.uploadGroup} key={kind}>
        <div className={s.heading}><strong>{evidenceLabels[kind]} <span className={s.muted}>必填</span></strong><label className={s.uploadButton}>＋ 添加文件<input type="file" accept="application/pdf,application/ofd,.ofd,application/xml,text/xml,.xml,image/jpeg,image/png" multiple onChange={e => { addFiles(kind, e.target.files); e.target.value = '' }} /></label></div>
        {claim.attachments.filter(a => a.kind === kind).map(a => <div className={s.fileLine} key={a.id}><span>{a.original_name} · 已上传云端</span>{kind === 'INVOICE' && claim.canEdit && <button type="button" onClick={() => addInvoice(a.id)}>添加发票明细</button>}{claim.canEdit && <button type="button" disabled={!online} onClick={() => void removeUploaded(a.id)}>移除附件</button>}</div>)}
        {local.files.filter(a => a.kind === kind).map(a => <div className={s.fileLine} key={a.id}><span>{a.file.name} · 待上传</span>{kind === 'INVOICE' && <button type="button" onClick={() => addInvoice(a.id)}>添加发票明细</button>}<button type="button" aria-label={`移除待上传${a.file.name}`} onClick={() => update({ dirty: true, files: current.current.files.filter(x => x.id !== a.id), form: { ...current.current.form, invoices: current.current.form.invoices.filter(i => i.attachmentId !== a.id) } })}>移除</button></div>)}
      </div>)}
      <h3>发票明细 <span className={s.muted}>{f.invoices.length}张 · 合计 {money(invoiceTotal(f.invoices))}</span></h3>
      {f.invoices.length === 0 && <p className={s.muted}>添加发票原件后，点击“添加发票明细”。发票号码必须完整，不允许省略前导0。</p>}
      {f.invoices.map((invoice, index) => {
        const edit = (patch: Partial<typeof invoice>) => change({ invoices: current.current.form.invoices.map(i => i.id === invoice.id ? { ...i, ...patch } : i) })
        return <div className={s.invoice} key={invoice.id}><div className={s.heading}><strong>发票 {index + 1}</strong><button type="button" onClick={() => change({ invoices: f.invoices.filter(i => i.id !== invoice.id) })}>移除明细</button></div>
          <small>关联原件：{invoiceSources.find(a => a.id === invoice.attachmentId)?.name ?? '原件暂不可见'}</small>
          <label>发票号码<input inputMode="numeric" value={invoice.invoiceNumber} maxLength={24} onChange={e => edit({ invoiceNumber: e.target.value, confirmed: false })} /></label>
          <label>传统发票代码（数电票留空）<input inputMode="numeric" value={invoice.invoiceCode} maxLength={20} onChange={e => edit({ invoiceCode: e.target.value, confirmed: false })} /></label>
          <div className={s.pair}><label>价税合计<input inputMode="decimal" value={invoice.amount} onChange={e => edit({ amount: e.target.value, confirmed: false })} /></label><label>购买方税号<input value={invoice.buyerTaxNumber} maxLength={32} onChange={e => edit({ buyerTaxNumber: e.target.value, confirmed: false })} /></label></div>
          <label className={s.check}><input type="checkbox" checked={invoice.confirmed} onChange={e => edit({ confirmed: e.target.checked })} />已对照原件确认号码、金额及抬头</label>
        </div>
      })}
      {differs(f) && <p className={s.warning}>金额存在差异：发票 {money(invoiceTotal(f.invoices))} / 交易 {money(f.transactionAmount)} / 实付 {money(f.paymentAmount)}。可说明平台补贴等原因，由财务核实，不直接拒绝。</p>}
      <label>差异或补充说明<textarea maxLength={2000} rows={3} value={f.differenceNote} placeholder="例如：平台补贴10元，实际支付90元" onChange={e => change({ differenceNote: e.target.value })} /></label>
    </fieldset>
    {claim.canEdit && <><div className={s.saveState} aria-live="polite"><span>本机加密草稿：{localTime ? new Date(localTime).toLocaleTimeString('zh-CN') : '尚未保存'}</span><span>云端：{new Date(claim.updated_at).toLocaleString('zh-CN')} {local.dirty && '· 有内容未同步'}</span><small>限当前账号与任职恢复，有效期7天；请使用可信设备。本机草稿不等于已报销。</small></div>
      {localError && <p className={s.error} role="alert">{localError}</p>}
      <div className={s.actions}><button type="button" disabled={!!busy || !ready} onClick={() => void retain(current.current)}>保存本机草稿</button><button type="button" disabled={!!busy || !ready || !online || conflict} onClick={() => void sync()}>保存云端草稿</button><button type="button" className={s.primary} disabled={!!busy || !ready || !online || conflict || (cents(f.requestedAmount) ?? 0n) <= 0n} onClick={() => void sync(true)}>提交审核</button></div>
      {busy && <p role="status" className={s.info}>{busy}</p>}
      {online && <button type="button" className={s.textButton} disabled={!!busy} onClick={() => void discardLocal()}>刷新云端 / 处理版本冲突</button>}
    </>}
    {canWithdrawClaim(claim.status) && <button className={s.textButton} disabled={!online || !!busy} onClick={() => void withdraw()}>撤回并结束此报销</button>}
    {claim.attachments.length > 0 && <details><summary>已保存原始凭证（{claim.attachments.length}）</summary><div className={s.evidenceGrid}>{claim.attachments.map(a => <EvidencePreview key={a.id} identity={identity} claimId={claim.id} evidence={a} />)}</div></details>}
    {claim.reviews.length > 0 && <section><h3>审核记录</h3><ReviewHistory records={claim.reviews} identity={identity} claimId={claim.id} /></section>}
  </section>
}
function treatmentLabel(treatment: string) { return ({ EXPENSE: '费用', INVENTORY: '库存', ASSET: '资产', PREPAID: '预付', ALREADY_RECORDED: '不重复计成本' } as Record<string, string>)[treatment] }
