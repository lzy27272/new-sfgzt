import { useEffect, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { errorMessage, financeRequest } from './api'
import { treatmentLabels, type Category, type FinanceContext } from './model'
import s from './finance.module.css'
type Store = { hotel_id: string; hotel_name: string; company_name?: string; tax_number?: string; manager_account_id?: string; finance_account_id?: string; leader_account_id?: string; payable_account?: string; enabled?: boolean; version?: number }
type Config = { stores: Store[]; accounts: { id: string; display_name: string }[] }
export function FinanceConfiguration({ identity, context, onChange }: { identity: ApiIdentity; context: FinanceContext; onChange: () => void }) {
  const [data, setData] = useState<Config>(); const [store, setStore] = useState<Store>(); const [error, setError] = useState(''); const [notice, setNotice] = useState(''); const [busy, setBusy] = useState(false)
  const [category, setCategory] = useState<Partial<Category>>({ treatment: 'EXPENSE', enabled: true }); const [history, setHistory] = useState(''); const [refresh, setRefresh] = useState(0)
  useEffect(() => { let active = true; void financeRequest<Config>(identity, '/configuration').then(d => { if (active) { setData(d); setStore(old => d.stores.find(s => s.hotel_id === old?.hotel_id) ?? d.stores[0]) } }).catch(e => { if (active) setError(errorMessage(e)) }); return () => { active = false } }, [identity, refresh])
  const run = async (path: string, method: string, body: unknown) => { setBusy(true); setError(''); setNotice(''); try { await financeRequest(identity, path, method, body); setNotice('配置已保存。未审批的历史申请保留原审核链，重新提交时读取新配置。'); setRefresh(x => x + 1); onChange(); return true } catch (e) { setError(errorMessage(e)); return false } finally { setBusy(false) } }
  if (!data || !store) return <p className={error ? s.error : s.empty}>{error || '读取门店财务配置…'}</p>
  const categories = context.categories.filter(c => c.hotel_id === store.hotel_id)
  return <section className={s.configuration}><h2>门店财务配置</h2><p className={s.info}>先配置公司抬头与三段审核人，再启用员工报销。审核人还必须在岗位权限中获得“报销审核”和对应门店数据范围；本页不会自动扩权。</p>
    <label>配置门店<select value={store.hotel_id} onChange={e => { setStore(data.stores.find(s => s.hotel_id === e.target.value)); setCategory({ treatment: 'EXPENSE', enabled: true }) }}>{data.stores.map(s => <option key={s.hotel_id} value={s.hotel_id}>{s.hotel_name}</option>)}</select></label>
    {error && <p className={s.error} role="alert">{error}</p>}{notice && <p className={s.info} role="status">{notice}</p>}
    <div className={s.configColumns}><fieldset className={s.panel} disabled={busy}><h3>公司与审批流程</h3>
      <label>公司完整名称<input value={store.company_name ?? ''} maxLength={160} onChange={e => setStore({ ...store, company_name: e.target.value })} /></label><label>纳税人识别号<input value={store.tax_number ?? ''} maxLength={20} onChange={e => setStore({ ...store, tax_number: e.target.value.toUpperCase() })} /></label>
      {(['manager_account_id', 'finance_account_id', 'leader_account_id'] as const).map((key, index) => <label key={key}>{index + 1}. {['门店负责人', '财务', '集团领导'][index]}<select value={store[key] ?? ''} onChange={e => setStore({ ...store, [key]: e.target.value })}><option value="">请选择实际审核人</option>{data.accounts.map(a => <option key={a.id} value={a.id}>{a.display_name}</option>)}</select></label>)}
      <label>贷方科目（凭证草稿）<input value={store.payable_account ?? ''} placeholder="由财务填写，如其他应付款—员工报销" maxLength={120} onChange={e => setStore({ ...store, payable_account: e.target.value })} /></label><label className={s.check}><input type="checkbox" checked={store.enabled ?? false} onChange={e => setStore({ ...store, enabled: e.target.checked })} />启用本店报销</label>
      <button className={s.primary} disabled={![store.company_name, store.tax_number, store.manager_account_id, store.finance_account_id, store.leader_account_id].every(Boolean)} onClick={() => void run(`/hotels/${store.hotel_id}/configuration`, 'PUT', { companyName: store.company_name, taxNumber: store.tax_number, managerAccountId: store.manager_account_id, financeAccountId: store.finance_account_id, leaderAccountId: store.leader_account_id, payableAccount: store.payable_account ?? '', enabled: store.enabled ?? false, expectedVersion: store.version ?? -1 })}>保存门店配置</button>
    </fieldset><fieldset className={s.panel} disabled={busy}><h3>费用类别与分类规则</h3><label>已有类别<select value={category.id ?? ''} onChange={e => setCategory(categories.find(c => c.id === e.target.value) ?? { treatment: 'EXPENSE', enabled: true })}><option value="">＋ 新增类别</option>{categories.map(c => <option key={c.id} value={c.id}>{c.name}{c.enabled ? '' : '（已停用）'}</option>)}</select></label>
      <label>类别名称<input value={category.name ?? ''} maxLength={80} onChange={e => setCategory({ ...category, name: e.target.value })} /></label>
      <label>会计处理<select value={category.treatment} onChange={e => setCategory({ ...category, treatment: e.target.value })}>{Object.entries(treatmentLabels).map(([key, name]) => <option value={key} key={key}>{name}</option>)}</select></label>
      <label>分类关键词（逗号分隔）<input value={category.keywords ?? ''} maxLength={500} onChange={e => setCategory({ ...category, keywords: e.target.value })} /></label>
      <label>借方科目（凭证草稿）<input value={category.debit_account ?? ''} maxLength={120} onChange={e => setCategory({ ...category, debit_account: e.target.value })} /></label>
      <label className={s.check}><input type="checkbox" checked={category.enabled ?? true} onChange={e => setCategory({ ...category, enabled: e.target.checked })} />启用此分类</label>
      <button className={s.primary} disabled={!category.name?.trim()} onClick={() => {
        const id = category.id ?? crypto.randomUUID(); setCategory({ ...category, id })
        void run(`/hotels/${store.hotel_id}/categories/${id}`, 'PUT', { name: category.name, treatment: category.treatment, keywords: category.keywords ?? '', debitAccount: category.debit_account ?? '', enabled: category.enabled ?? true, expectedVersion: category.version ?? -1 })
          .then(success => { if (success) setCategory({ treatment: 'EXPENSE', enabled: true }) })
      }}>保存分类</button>
    </fieldset></div>
    <details className={s.panel}><summary>导入历史已报销发票（集团权限）</summary><p>用于阻止旧系统已报销的发票再次提交，不创建费用或凭证。每行：发票号码,发票代码,历史单号；数电票的代码留空。每批最多500条，整批校验。</p><label>历史记录<textarea rows={6} value={history} onChange={e => setHistory(e.target.value)} placeholder="20位数电发票号码,,旧系统报销单号" /></label><button disabled={busy || !history.trim()} onClick={() => { const rows = history.trim().split(/\r?\n/).map(line => line.split(/[,，]/).map(t => t.trim())); if (rows.some(r => r.length !== 3)) { setError('每行应有三列：号码,代码,历史单号'); return } void run('/historical-invoices', 'POST', { invoices: rows.map(([invoiceNumber, invoiceCode, reference]) => ({ invoiceNumber, invoiceCode, reference })) }) }}>核验并导入</button></details>
  </section>
}
