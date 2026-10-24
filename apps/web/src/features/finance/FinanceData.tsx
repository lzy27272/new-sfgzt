import { useEffect, useRef, useState, type ReactNode } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeRequest } from './api'
import { financeCsv, financeState, rowText, textValue, type FinanceRow } from './unifiedModel'
import { loadPrivateFinanceForm, savePrivateFinanceForm, removePrivateFinanceForm, type PrivateFinanceForm } from './encryptedDrafts'
import s from './finance.module.css'

export type FinanceColumn = { key: string; label: string; render?: (row: FinanceRow) => ReactNode }
export function FinanceTable({ rows, columns, label, onOpen }: { rows: FinanceRow[]; columns: FinanceColumn[]; label: string; onOpen?: (row: FinanceRow) => void }) {
  return rows.length ? <div className={s.tableWrap} tabIndex={0} role="region" aria-label={`${label}，可左右滚动`}><table><thead><tr>{columns.map(c => <th key={c.key} scope="col">{c.label}</th>)}{onOpen && <th scope="col">操作</th>}</tr></thead><tbody>{rows.map((row, index) => <tr key={rowText(row, 'id', String(index))}>{columns.map(c => <td key={c.key}>{c.render ? c.render(row) : c.key.toLowerCase().includes('status') ? financeState(row[c.key]) : rowText(row, c.key)}</td>)}{onOpen && <td><button type="button" onClick={() => onOpen(row)}>查看详情 ›</button></td>}</tr>)}</tbody></table></div> : <p className={s.empty}>{label}暂无记录；没有数据不代表已完成核对。</p>
}
export function exportFinanceCsv(rows: FinanceRow[], columns: FinanceColumn[], filename: string) {
  const url = URL.createObjectURL(new Blob([financeCsv(rows, columns)], { type: 'text/csv;charset=utf-8' }))
  const link = document.createElement('a'); link.href = url; link.download = filename.endsWith('.csv') ? filename : `${filename}.csv`; link.click()
  setTimeout(() => URL.revokeObjectURL(url), 30000)
}
export type FinanceField = { key: string; label: string; type?: 'text' | 'number' | 'date' | 'month' | 'textarea' | 'select' | 'multi-select' | 'checkbox'; required?: boolean; placeholder?: string; options?: { value: string; label: string }[]; help?: string }
export function FinanceFields({ fields, values, onChange }: { fields: FinanceField[]; values: Record<string, string>; onChange: (value: Record<string, string>) => void }) {
  return <div className={s.pair}>{fields.map(field => <label key={field.key} className={field.type === 'checkbox' ? s.check : undefined}>{field.type === 'checkbox' && <input type="checkbox" checked={values[field.key] === 'true'} onChange={e => onChange({ ...values, [field.key]: String(e.target.checked) })} />}{field.label}{field.required && ' *'}
    {field.type === 'multi-select' ? <select multiple required={field.required} value={(values[field.key] ?? '').split(',').filter(Boolean)} onChange={e => onChange({ ...values, [field.key]: Array.from(e.target.selectedOptions, option => option.value).join(',') })}>{field.options?.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select> : field.type === 'select' ? <select required={field.required} value={values[field.key] ?? ''} onChange={e => onChange({ ...values, [field.key]: e.target.value })}><option value="">请选择</option>{field.options?.map(o => <option key={o.value} value={o.value}>{o.label}</option>)}</select>
      : field.type === 'textarea' ? <textarea required={field.required} rows={3} maxLength={4000} value={values[field.key] ?? ''} placeholder={field.placeholder} onChange={e => onChange({ ...values, [field.key]: e.target.value })} />
        : field.type !== 'checkbox' ? <input type={field.type === 'number' ? 'text' : field.type ?? 'text'} inputMode={field.type === 'number' ? 'decimal' : undefined} required={field.required} maxLength={field.type === 'date' || field.type === 'month' ? undefined : 500} value={values[field.key] ?? ''} placeholder={field.placeholder} onChange={e => onChange({ ...values, [field.key]: e.target.value })} /> : null}
    {field.help && <small>{field.help}</small>}</label>)}</div>
}
export function FinanceMutation({ identity, path, label, fields, initial = {}, build, children, onSuccess, confirm, method = 'POST', privateDraft }: {
  identity: ApiIdentity; path: string; label: string; fields: FinanceField[]; initial?: Record<string, string>;
  build: (values: Record<string, string>, requestId: string) => unknown; children?: ReactNode; onSuccess?: (value: FinanceRow) => void; confirm?: string; method?: 'POST' | 'PUT'; privateDraft?: { id: string; baseVersion: string };
}) {
  const [values, setValues] = useState(initial); const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [notice, setNotice] = useState('')
  const lock = useRef(false); const retry = useRef({ signature: '', id: '' })
  const scope = `${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId ?? ''}`
  const draftId = privateDraft?.id; const baseVersion = privateDraft?.baseVersion
  const [ready, setReady] = useState(!privateDraft); const [dirty, setDirty] = useState(false); const [localNotice, setLocalNotice] = useState(''); const [conflictDraft, setConflictDraft] = useState<PrivateFinanceForm>()
  useEffect(() => { if (!draftId) return; let alive = true
    void loadPrivateFinanceForm(scope, draftId).then(local => { if (!alive) return; if (local && local.baseVersion !== baseVersion) { setConflictDraft(local); setLocalNotice('云端版本已变化。本机内容保留待核对，未自动覆盖新版本。') } else if (local) { setValues(local.values); setDirty(true); setLocalNotice('已恢复本账号、当前任职的加密本机草稿，尚未提交云端。') } }).catch(error => { if (alive) setLocalNotice(errorMessage(error)) }).finally(() => { if (alive) setReady(true) })
    return () => { alive = false }
  }, [scope, draftId, baseVersion])
  useEffect(() => { if (!draftId || !ready || !dirty || conflictDraft || baseVersion === undefined) return
    // Save every edit, not only on unload: mobile webviews can be destroyed without an unload event.
    let alive = true; void savePrivateFinanceForm(scope, draftId, { values, baseVersion }).then(() => { if (alive) setLocalNotice('本机已加密暂存；切换消息或接电话后可恢复，仍需明确保存到云端。') }).catch(error => { if (alive) setLocalNotice(errorMessage(error)) })
    return () => { alive = false }
  }, [scope, draftId, baseVersion, ready, dirty, values, conflictDraft])
  return <form className={s.form} onSubmit={async event => {
    event.preventDefault(); if (lock.current || !ready || conflictDraft) return
    if (!navigator.onLine) { setError('当前离线，不能执行财务确认。已填写内容保持不变。'); return }
    if (confirm && !window.confirm(confirm)) return
    lock.current = true; setBusy(true); setError(''); setNotice('')
    try {
      // Include child-controlled lines and current server versions, not only the scalar fields.
      const proposed = build(values, retry.current.id || crypto.randomUUID())
      const signature = JSON.stringify({ path, method, payload: proposed }, (key, value) => key === 'requestId' ? null : value)
      if (retry.current.signature !== signature) retry.current = { signature, id: crypto.randomUUID() }
      const payload = build(values, retry.current.id)
      const result = await financeRequest<FinanceRow>(identity, path, method, payload)
      setDirty(false); if (draftId) { try { await removePrivateFinanceForm(scope, draftId) } catch { setLocalNotice('云端已保存，但本机旧草稿清理失败；下次恢复时请核对云端版本，不要重复提交。') } }
      setNotice(`${label}：服务端已确认。`); onSuccess?.(result)
    } catch (error) { setError(errorMessage(error)) } finally { lock.current = false; setBusy(false) }
  }}><fieldset disabled={busy || !ready || !!conflictDraft}><FinanceFields fields={fields} values={values} onChange={value => { setValues(value); setDirty(true) }} />{children}<div className={s.actions}><button type="submit" className={s.primary}>{busy ? '正在核验并保存…' : !ready ? '正在核对加密草稿…' : label}</button></div></fieldset>{localNotice && <p role="status" className={s.info}>{localNotice}</p>}{conflictDraft && <section className={s.warning}><h4>本机旧版本内容（请先与云端核对）</h4><FinanceFacts data={conflictDraft.values} labels={Object.fromEntries(fields.map(field => [field.key, field.label]))} /><button type="button" onClick={() => { setConflictDraft(undefined); setValues(conflictDraft.values); setDirty(true); setLocalNotice('已明确选择在新版本上保留本机内容，请逐项核对后再保存。') }}>已核对，将本机内容带入新版本</button><button type="button" onClick={async () => { if (draftId) await removePrivateFinanceForm(scope, draftId); setConflictDraft(undefined); setValues(initial); setDirty(false); setLocalNotice('已明确放弃本机旧内容，保留云端版本。') }}>放弃本机旧内容，使用云端版本</button></section>}{error && <p role="alert" className={s.error}>{error}</p>}{notice && <p role="status" className={s.info}>{notice}</p>}</form>
}
export function FinanceFacts({ data, labels }: { data: FinanceRow; labels: Record<string, string> }) {
  return <dl className={s.facts}>{Object.entries(labels).map(([key, label]) => <div key={key} className={s.factPair}><dt>{label}</dt><dd>{key.toLowerCase().includes('status') ? financeState(data[key]) : textValue(data[key])}</dd></div>)}</dl>
}
export function FinanceJsonImport({ label, example, onChange }: { label: string; example: string; onChange: (value: string) => void }) {
  return <label>{label}<textarea rows={8} spellCheck={false} placeholder={example} onChange={e => onChange(e.target.value)} /><small>金额及票号以文本提供。先预览错误，再明确提交；不自动选择公司或覆盖历史。</small></label>
}
