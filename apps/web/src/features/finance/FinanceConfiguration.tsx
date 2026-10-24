import { useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeRequest } from './api'
import { treatmentLabels, type Category, type FinanceContext } from './model'
import { configurationFromStore, configurationIssues, reviewerFields, type ApprovalConfiguration, type ConfigurationData, type FinanceStore } from './approvalConfiguration'
import s from './finance.module.css'
export function FinanceConfiguration({ identity, context, onChange }: { identity: ApiIdentity; context: FinanceContext; onChange: () => void }) {
  const [data, setData] = useState<ConfigurationData>(); const [store, setStore] = useState<FinanceStore>(); const [form, setForm] = useState<ApprovalConfiguration>(); const [targets, setTargets] = useState<string[]>([])
  const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false); const lock = useRef(false)
  const [category, setCategory] = useState<Partial<Category>>({ treatment: 'EXPENSE', enabled: true }); const [history, setHistory] = useState('')
  useEffect(() => { let active = true; void financeRequest<ConfigurationData>(identity, '/configuration').then(d => {
    if (active) { setData(d); const first = d.stores[0]; setStore(first); setForm(first ? configurationFromStore(first) : undefined); setTargets(first ? [first.hotel_id] : []) }
  }).catch(e => { if (active) setError(errorMessage(e)) }); return () => { active = false } }, [identity])
  const run = async (path: string, method: string, body: unknown) => {
    if (lock.current) return false
    if (!navigator.onLine) { setError('财务配置必须联网保存，当前填写内容仍保留在页面。'); return false }
    lock.current = true; setBusy(true); setError(''); setNotice('')
    try { await financeRequest(identity, path, method, body); onChange(); setNotice('配置已保存。已提交单据保留原审核链，重新提交时读取新配置。'); return true }
    catch (e) { setError(errorMessage(e)); return false } finally { lock.current = false; setBusy(false) }
  }
  if (!data || !store || !form) return <p className={error ? s.error : s.empty}>{error || (data ? '当前范围暂无可配置组织' : '读取财务配置…')}</p>
  const patch = (value: Partial<ApprovalConfiguration>) => setForm({ ...form, ...value })
  const saveIssues = configurationIssues(form, data.accounts, false), enableIssues = configurationIssues(form, data.accounts, true)
  const saveConfiguration = async (enabled: boolean) => {
    const issues = enabled ? enableIssues : saveIssues
    if (issues.length) { setError(issues.join('；')); return }
    if (enabled && !window.confirm(`将启用 ${targets.map(id => data.stores.find(s => s.hotel_id === id)?.hotel_name).join('、')} 的当前四段审批配置。已提交单据保留原审核链，确定吗？`)) return
    const ok = await run('/configuration', 'PUT', { targets: targets.map(hotelId => ({ hotelId, expectedVersion: data.stores.find(s => s.hotel_id === hotelId)?.version ?? -1 })), configuration: { ...form, companyName: form.companyName.trim(), taxNumber: form.taxNumber.trim(), enabled } })
    if (!ok) return
    lock.current = true; setBusy(true)
    setNotice(enabled ? '审批流程已启用。不会自动授予审核或付款权限，也不会修改已提交单据。' : '配置草稿已保存，未启用新流程；已有启用配置继续生效。')
    // Refresh versions without re-running the initial-load effect or silently discarding unrelated fields.
    try { const updated = await financeRequest<ConfigurationData>(identity, '/configuration'); setData(updated); const selected = updated.stores.find(s => s.hotel_id === store.hotel_id); if (selected) { setStore(selected); setForm(configurationFromStore(selected)) } }
    catch (e) { setError(`保存已完成，但刷新失败，请重新打开配置后继续：${errorMessage(e)}`) }
    finally { lock.current = false; setBusy(false) }
  }
  const categories = context.categories.filter(c => c.hotel_id === store.hotel_id)
  const accountSelect = (key: typeof reviewerFields[number][0] | 'cashierAccountId', label: string) => <select aria-label={`${label}账号`} value={form[key] ?? ''} onChange={e => patch({ [key]: e.target.value || null })}>
    <option value="">{key === 'leaderAccountId' && form.leaderName ? `${form.leaderName} · 待绑定账号` : `请选择${label}账号`}</option>
    {form[key] && !data.accounts.some(a => a.id === form[key]) && <option value={form[key]!}>原账号不可用，请重新选择</option>}
    {data.accounts.map(a => <option key={a.id} value={a.id}>{a.display_name}{a.login_name ? `（${a.login_name}）` : ''}</option>)}
  </select>
  return <section className={s.configuration}><div className={s.heading}><div><h2>公司与审批流程配置</h2><p className={s.muted}>公司资料与审核账号分开配置，补全后方可启用。</p></div><span className={s.pill}>{store.enabled ? (store.draft ? '已启用 · 有未发布草稿' : '当前配置已启用') : '配置草稿'}</span></div>
    <label>配置门店或总部<select disabled={busy} value={store.hotel_id} onChange={e => { const selected = data.stores.find(s => s.hotel_id === e.target.value); if (!selected) return; if (JSON.stringify(form) !== JSON.stringify(configurationFromStore(store)) && !window.confirm('当前尚有未保存的修改，确定切换组织吗？')) return; setStore(selected); setForm(configurationFromStore(selected)); setTargets([selected.hotel_id]); setError(''); setNotice(''); setCategory({ treatment: 'EXPENSE', enabled: true }) }}>{data.stores.map(s => <option key={s.hotel_id} value={s.hotel_id}>{s.hotel_name}{s.hotel_code ? ` · ${s.hotel_code}` : ''}</option>)}</select></label>
    {error && <p className={s.error} role="alert">{error}</p>}{notice && <p className={s.info} role="status">{notice}</p>}
    <div className={s.approvalConfigColumns}><fieldset className={s.panel} disabled={busy}><h3>公司资料</h3>
      <label>公司完整名称<input value={form.companyName} maxLength={160} onChange={e => patch({ companyName: e.target.value })} /></label><label>纳税人识别号<input value={form.taxNumber} maxLength={20} onChange={e => patch({ taxNumber: e.target.value.toUpperCase().replace(/\s/g, '') })} /></label>
      <div><strong>适用组织</strong><div className={s.orgChips}>{targets.map(id => <span className={s.pill} key={id}>{data.stores.find(s => s.hotel_id === id)?.hotel_name}</span>)}</div><div className={s.orgOptions}>{data.stores.map(item => <label key={item.hotel_id} className={s.check}><input type="checkbox" checked={targets.includes(item.hotel_id)} disabled={item.hotel_id === store.hotel_id} onChange={e => setTargets(old => e.target.checked ? [...old, item.hotel_id] : old.filter(id => id !== item.hotel_id))} />{item.hotel_name}{item.hotel_code ? `（${item.hotel_code}）` : ''}</label>)}</div><small>仅对勾选组织应用相同公司与审批配置。不按编号自动匹配，已提交报销的公司快照不变。</small></div>
      <label>CEO拟配置姓名<input value={form.leaderName} maxLength={120} onChange={e => patch({ leaderName: e.target.value })} /></label><small>姓名只是待绑定提示，不会匹配或授权同名、测试账号。</small>
      <label>贷方科目（凭证草稿）<input value={form.payableAccount} placeholder="由财务填写，如其他应付款—员工报销" maxLength={120} onChange={e => patch({ payableAccount: e.target.value })} /></label>
      <p className={s.info}>公司资料可先保存，审核人员可后续绑定。</p>
    </fieldset><fieldset className={s.panel} disabled={busy}><h3>审批流程</h3>
      {enableIssues.length > 0 ? <div className={s.warning}><strong>审核账号未配置完整或存在异常，暂不可启用</strong><ul>{enableIssues.map(issue => <li key={issue}>{issue}</li>)}</ul></div> : <p className={s.info}>审核与出纳账号已配齐，可启用当前流程。</p>}
      <div className={s.approvalRows}>{reviewerFields.map(([key, label], index) => <div className={s.approvalRow} key={key}><span className={s.stepNumber}>{index + 1}</span><label>{label}{accountSelect(key, label)}</label><span className={form[key] ? s.pill : s.pendingLabel}>{form[key] ? '已选择' : '待配置'}</span></div>)}</div>
      <div className={s.paymentDivider}>审批通过后</div><div className={s.approvalRow}><span className={s.stepNumber}>付</span><label>出纳登记付款{accountSelect('cashierAccountId', '出纳')}</label><span className={form.cashierAccountId ? s.pill : s.pendingLabel}>{form.cashierAccountId ? '已选择' : '待配置'}</span></div>
      <div className={s.info}><h3>自审回避</h3><label className={s.check}><input type="checkbox" checked={form.skipSelfReview} onChange={e => patch({ skipSelfReview: e.target.checked })} />初审人为申请人时，转交下一审核节点</label><small>本人初审跳过后记录原因，不视为审核通过。后续节点必须已有独立审核账号，未配置的节点不可自动跳过。</small></div>
      <small>审核人仍须具有“报销审核”及对应组织数据权限；出纳须具有“付款登记”权限。本页不会自动扩权或发起银行转账。</small>
    </fieldset></div>
    <div className={s.configurationFooter}><p className={s.muted}>保存草稿不发布新配置。已启用的配置继续生效，已提交单据保留原审核链。</p><div className={s.actions}><button disabled={busy || saveIssues.length > 0} onClick={() => void saveConfiguration(false)}>保存草稿</button><button className={s.primary} disabled={busy || enableIssues.length > 0} onClick={() => void saveConfiguration(true)}>启用流程</button></div></div>
    <details className={s.panel}><summary>费用类别与分类规则 · {store.hotel_name}</summary><fieldset className={s.form} disabled={busy}><label>已有类别<select value={category.id ?? ''} onChange={e => setCategory(categories.find(c => c.id === e.target.value) ?? { treatment: 'EXPENSE', enabled: true })}><option value="">＋ 新增类别</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}{c.enabled ? '' : '（已停用）'}</option>)}</select></label>
      <label>类别名称<input value={category.name ?? ''} maxLength={80} onChange={e => setCategory({ ...category, name: e.target.value })} /></label>
      <label>稳定财务类别编码（预算与采购共用）<input value={category.budget_code ?? ''} maxLength={80} placeholder="由财务明确配置，不按名称或UUID猜测" onChange={e => setCategory({ ...category, budget_code: e.target.value })} /><small>已被预算或原始费用引用的编码不可直接改名；请新建类别并受控停用旧类别。</small></label>
      <label>会计处理<select value={category.treatment} onChange={e => setCategory({ ...category, treatment: e.target.value })}>{Object.entries(treatmentLabels).map(([key, name]) => <option value={key} key={key}>{name}</option>)}</select></label>
      <label>分类关键词（逗号分隔）<input value={category.keywords ?? ''} maxLength={500} onChange={e => setCategory({ ...category, keywords: e.target.value })} /></label>
      <label>借方科目（凭证草稿）<input value={category.debit_account ?? ''} maxLength={120} onChange={e => setCategory({ ...category, debit_account: e.target.value })} /></label>
      <label className={s.check}><input type="checkbox" checked={category.enabled ?? true} onChange={e => setCategory({ ...category, enabled: e.target.checked })} />启用此分类</label>
      <button className={s.primary} disabled={!category.name?.trim()} onClick={() => {
        const id = category.id ?? crypto.randomUUID(); setCategory({ ...category, id })
        void run(`/hotels/${store.hotel_id}/categories/${id}`, 'PUT', { name: category.name, treatment: category.treatment, keywords: category.keywords ?? '', debitAccount: category.debit_account ?? '', enabled: category.enabled ?? true, expectedVersion: category.version ?? -1, budgetCode: category.budget_code ?? null })
          .then(success => { if (success) setCategory({ treatment: 'EXPENSE', enabled: true }) })
      }}>保存分类</button>
    </fieldset></details>
    <details className={s.panel}><summary>导入历史已报销发票（集团权限）</summary><p>用于阻止旧系统已报销的发票再次提交，不创建费用或凭证。每行：发票号码,发票代码,历史单号；数电票的代码留空。每批最多500条，整批校验。</p><label>历史记录<textarea rows={6} value={history} onChange={e => setHistory(e.target.value)} placeholder="20位数电发票号码,,旧系统报销单号" /></label><button disabled={busy || !history.trim()} onClick={() => { const rows = history.trim().split(/\r?\n/).map(line => line.split(/[,，]/).map(t => t.trim())); if (rows.some(r => r.length !== 3)) { setError('每行应有三列：号码,代码,历史单号'); return } void run('/historical-invoices', 'POST', { invoices: rows.map(([invoiceNumber, invoiceCode, reference]) => ({ invoiceNumber, invoiceCode, reference })) }) }}>核验并导入</button></details>
  </section>
}
