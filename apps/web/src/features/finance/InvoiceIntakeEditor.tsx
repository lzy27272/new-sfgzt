import { useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeRequest } from './api'
import { loadPrivateFinanceForm, savePrivateFinanceForm, removePrivateFinanceForm } from './encryptedDrafts'
import { financeScope, type EvidenceExtraction } from './model'
import { acceptIntakeRecognition, validRecognitionValue, type IntakeCandidateValues, type IntakeRecognitionDraft } from './recognitionModel'
import { RecognitionReview } from './RecognitionReview'
import { rowText, type FinanceRow } from './unifiedModel'
import s from './finance.module.css'

type IntakeDraft = IntakeRecognitionDraft
const fieldLabels = { invoiceNumber: '完整发票号码（保留前导0）', invoiceCode: '传统发票代码（数电票留空）', amount: '价税合计', buyerTaxNumber: '购买方税号' }
const fromRow = (row: FinanceRow): IntakeCandidateValues => ({ invoiceNumber: rowText(row, 'invoiceNumber', ''), invoiceCode: rowText(row, 'invoiceCode', ''), amount: rowText(row, 'amount', ''), buyerTaxNumber: rowText(row, 'buyerTaxNumber', ''), confirmed: String(row.confirmed === true) })

export function InvoiceIntakeEditor({ identity, intake, onUpdated }: { identity: ApiIdentity; intake: FinanceRow; onUpdated: () => void }) {
  const id = rowText(intake, 'id'); const scope = financeScope(identity); const draftId = `invoice-intake:${id}`
  const [row, setRow] = useState(intake)
  const [draft, setDraft] = useState<IntakeDraft>({ values: fromRow(intake), version: Number(intake.version), touched: [] })
  const current = useRef(draft); current.current = draft
  const [ready, setReady] = useState(false); const [dirty, setDirty] = useState(false); const [busy, setBusy] = useState('')
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [localNotice, setLocalNotice] = useState('')
  const [conflict, setConflict] = useState(false); const lock = useRef(false)
  const update = (next: IntakeDraft) => { current.current = next; setDraft(next); setDirty(true) }
  const acceptResult = (result: FinanceRow, operation: 'RETRY' | 'UPLOAD') => {
    setRow(result)
    const accepted = acceptIntakeRecognition(current.current, result.version, result.extraction as EvidenceExtraction | undefined, operation)
    if (accepted.conflict) {
      setConflict(true); setNotice('识别结果已保留，但返回的云端业务版本与本机预期不一致。未合入候选、未升级本机版本或覆盖已填内容，请先核对是否有其他页面更新。'); onUpdated(); return false
    }
    update(accepted.draft)
    onUpdated()
    return true
  }
  useEffect(() => {
    let alive = true
    void loadPrivateFinanceForm(scope, draftId).then(saved => {
      if (!alive || !saved) return
      const values = { ...fromRow(intake), ...Object.fromEntries(Object.keys(fromRow(intake)).filter(key => typeof saved.values[key] === 'string').map(key => [key, saved.values[key]])) }
      let touched: string[] = []; try { const parsed: unknown = JSON.parse(saved.values._touched ?? '[]'); if (Array.isArray(parsed)) touched = parsed.filter(value => typeof value === 'string') } catch { /* Old drafts did not track touched fields. */ }
      const restored = { values, touched, version: Number(saved.baseVersion) }
      current.current = restored; setDraft(restored); setDirty(true)
      setConflict(saved.baseVersion !== String(intake.version))
      setLocalNotice(saved.baseVersion === String(intake.version) ? '已恢复本账号、当前任职的加密本机收票草稿。' : '本机与云端版本不同，已保留本机内容；请核对后载入云端版本。')
    }).catch(error => { if (alive) { setError(errorMessage(error)); setConflict(true) } }).finally(() => { if (alive) setReady(true) })
    return () => { alive = false }
  }, [scope, draftId])
  useEffect(() => {
    if (Number(intake.version) <= Number(row.version)) return
    if (busy) return
    if (dirty) { setConflict(true); return }
    setRow(intake); const next = { values: fromRow(intake), version: Number(intake.version), touched: [] }; current.current = next; setDraft(next)
  }, [intake, row.version, dirty, busy])
  useEffect(() => {
    if (!ready || !dirty || conflict) return
    let alive = true
    void savePrivateFinanceForm(scope, draftId, { values: { ...draft.values, _touched: JSON.stringify(draft.touched) }, baseVersion: String(draft.version) })
      .then(() => { if (alive) setLocalNotice('本机已加密暂存；尚未保存的候选和手工输入不会因识别刷新而清空。') })
      .catch(error => { if (alive) setLocalNotice(`本机保存失败：${errorMessage(error)}。请保持页面打开。`) })
    return () => { alive = false }
  }, [scope, draftId, draft, ready, dirty, conflict])
  const edit = (field: keyof IntakeCandidateValues, value: string) => {
    const before = current.current
    update({ ...before, touched: [...new Set([...before.touched, field])], values: { ...before.values, [field]: value, ...(field === 'confirmed' ? {} : { confirmed: 'false' }) } })
  }
  const useCandidate = (field: string, value: string) => {
    if (!(field in fieldLabels) || !validRecognitionValue(field, value)) return
    const key = field as keyof typeof fieldLabels
    if (current.current.values[key].trim() && current.current.values[key] !== value) { setNotice('该字段已有值，未覆盖。请先核对原件，确需采用其他候选时清空该字段再选择。'); return }
    edit(key, value); setNotice('已选用候选，尚未人工确认或保存云端。')
  }
  const run = async (label: string, action: () => Promise<void>) => {
    if (lock.current || !ready || conflict) return
    if (!navigator.onLine) { setError('当前离线，已填内容保留在本机。联网后可识别或保存云端。'); return }
    lock.current = true; setBusy(label); setError(''); setNotice('')
    try { await action() } catch (error) { setError(`${errorMessage(error)} 已填内容保留；已保存原件无需重新选择。`) }
    finally { lock.current = false; setBusy('') }
  }
  const retry = () => void run('重新识别已保存原件…', async () => {
    const result = await financeRequest<FinanceRow>(identity, `/invoice-pool/${id}/recognize`, 'POST', {})
    if (acceptResult(result, 'RETRY')) setNotice('已读取原件识别结果；仅补空白候选，没有覆盖手工输入。请对照原件核对后保存。')
  })
  return <section aria-label="收票候选填写与本地识别">
    {error && <p role="alert" className={s.error}>{error}</p>}{notice && <p role="status" className={s.info}>{notice}</p>}
    {conflict && <p className={s.warning}>云端或其他页面已有更新。本机内容仍在，已暂停识别和覆盖保存。</p>}
    <fieldset className={s.form} disabled={!ready || !!busy || conflict || row.canEdit !== true}>
      {row.originalName ? <p className={s.info}>原件已保存且不可覆写。识别失败可直接重试，无需重新选图；换票请新建资料。</p> : <label className={s.uploadButton}>{busy ? '正在保存原件并识别…' : '选择或拍摄发票原件'}<input type="file" accept=".pdf,.ofd,.xml,.jpg,.jpeg,.png" onChange={event => {
        const file = event.target.files?.[0]; event.target.value = ''; if (!file) return
        void run('保存原件并识别…', async () => { const body = new FormData(); body.append('file', file); const result = await financeRequest<FinanceRow>(identity, `/invoice-pool/${id}/original`, 'POST', body); if (acceptResult(result, 'UPLOAD')) setNotice('原件已保存。识别候选仅补入空白字段，请核对后确认。') })
      }} /></label>}
      <RecognitionReview extraction={row.extraction as EvidenceExtraction | undefined} onRetry={row.originalName && ['image/jpeg', 'image/png'].includes(rowText(row, 'mediaType')) ? retry : undefined} retrying={busy.startsWith('重新识别')} disabled={!ready || !!busy || conflict} onUse={useCandidate} />
      <div className={s.pair}>{Object.entries(fieldLabels).map(([field, label]) => <label key={field}>{label}<input inputMode={field === 'amount' ? 'decimal' : field === 'invoiceNumber' || field === 'invoiceCode' ? 'numeric' : undefined} value={draft.values[field as keyof typeof fieldLabels]} maxLength={field === 'invoiceCode' ? 20 : field === 'invoiceNumber' ? 24 : field === 'buyerTaxNumber' ? 32 : 18} onChange={event => edit(field as keyof typeof fieldLabels, event.target.value)} /></label>)}</div>
      <label className={s.check}><input type="checkbox" checked={draft.values.confirmed === 'true'} onChange={event => edit('confirmed', String(event.target.checked))} />已对照原件人工确认号码、金额及购买方税号</label>
      <button type="button" className={s.primary} onClick={() => void run('保存候选字段…', async () => {
        const before = current.current
        const result = await financeRequest<FinanceRow>(identity, `/invoice-pool/${id}`, 'PUT', { ...before.values, amount: before.values.amount || null, confirmed: before.values.confirmed === 'true', expectedVersion: before.version })
        setRow(result); const next = { ...before, version: Number(result.version), values: fromRow(result) }; current.current = next; setDraft(next); setDirty(false)
        await removePrivateFinanceForm(scope, draftId); onUpdated(); setNotice('候选字段已保存到云端。人工确认不代表税务验真，正式报销提交仍会排重。')
      })}>{busy || '保存候选字段'}</button>
    </fieldset>
    {localNotice && <p role="status" className={s.muted}>{localNotice}</p>}
    {conflict && <button type="button" onClick={() => void (async () => {
      if (!window.confirm('重新载入将放弃本机未同步的收票字段。请先核对本机内容；是否明确使用云端版本？')) return
      try { const latest = await financeRequest<FinanceRow>(identity, `/invoice-pool/${id}`); await removePrivateFinanceForm(scope, draftId); setRow(latest); const next = { values: fromRow(latest), version: Number(latest.version), touched: [] }; current.current = next; setDraft(next); setDirty(false); setConflict(false); setError(''); onUpdated() } catch (error) { setError(errorMessage(error)) }
    })()}>已核对，重新载入云端字段</button>}
  </section>
}
