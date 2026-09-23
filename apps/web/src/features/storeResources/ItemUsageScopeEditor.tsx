import { useState } from 'react'
import { usageScopeSummary, type ItemUsageScope, type UsageScopeOption, type UsageScopeOptions } from './usageScope'
import styles from './storeResources.module.css'

export const emptyUsageOptions: UsageScopeOptions = { departments: [], positions: [] }

export function ItemUsageScopeEditor({ value, onChange, options, loading, error, retry, allowLegacy, itemName, employeeUsageEnabled }: {
  value: ItemUsageScope; onChange: (value: ItemUsageScope) => void; options: UsageScopeOptions
  loading: boolean; error?: unknown; retry?: () => void; allowLegacy: boolean; itemName: string; employeeUsageEnabled: boolean
}) {
  const summary = usageScopeSummary(value, options)
  return <section className={styles.usageScopeLayout} aria-label="物品使用范围">
    <div className={styles.usageScopeFields}>
      <h3>物品使用范围</h3>
      <p>记录物品适用的部门与岗位，为后续快捷领用准备。不会自动授予领用或审批权限。</p>
      <fieldset disabled={loading || Boolean(error)}><legend>适用部门 *</legend>
        <div className={styles.scopeModes}>
          {allowLegacy && <label><input type="radio" name="usage-scope-mode" checked={value.mode === 'LEGACY'} onChange={() => onChange({ mode: 'LEGACY', departmentIds: [], positionIds: [] })} />保留原配置（待补齐）</label>}
          <label><input type="radio" name="usage-scope-mode" checked={value.mode === 'ALL_DEPARTMENTS'} onChange={() => onChange({ ...value, mode: 'ALL_DEPARTMENTS', departmentIds: [] })} />全店通用</label>
          <label><input type="radio" name="usage-scope-mode" checked={value.mode === 'DEPARTMENTS'} onChange={() => onChange({ ...value, mode: 'DEPARTMENTS' })} />指定部门</label>
        </div>
        {value.mode === 'DEPARTMENTS' && <ScopeChoices label="部门" options={options.departments} saved={value.departments} selected={value.departmentIds} onChange={ids => onChange({ ...value, departmentIds: ids })} />}
      </fieldset>
      {value.mode !== 'LEGACY' && <fieldset disabled={loading || Boolean(error)}><legend>适用岗位（选填）</legend>
        <p>留空表示所选部门内全部岗位；可选集团有效岗位标准，不限于当前已有任职。</p>
        <ScopeChoices label="岗位" options={options.positions} saved={value.positions} selected={value.positionIds} onChange={ids => onChange({ ...value, positionIds: ids })} />
      </fieldset>}
      {loading && <p role="status">正在加载本门店部门和岗位…</p>}
      {Boolean(error) && <div className={styles.error} role="alert">部门和岗位加载失败，已填内容保留。<button type="button" className="secondary" onClick={retry}>重新加载选项</button></div>}
    </div>
    <aside className={styles.usageScopeSummary} aria-label="当前使用范围">
      <span>当前使用范围</span><h3>{itemName.trim() || '待填写物品名称'}</h3>
      <dl><dt>适用部门</dt><dd>{summary.departments}</dd><dt>适用岗位</dt><dd>{summary.positions}</dd><dt>员工领用及填报</dt><dd>{employeeUsageEnabled ? '已允许（仍需原有权限）' : '未开启'}</dd></dl>
      <p>一份物品，一本库存账。配置范围不会拆分库存，不会改动历史数量、成本或常用品清单。</p>
    </aside>
  </section>
}

function ScopeChoices({ label, options, saved = [], selected, onChange }: {
  label: string; options: UsageScopeOption[]; saved?: UsageScopeOption[]; selected: string[]; onChange: (ids: string[]) => void
}) {
  const [search, setSearch] = useState('')
  const visible = options.filter(option => `${option.name} ${option.code}`.toLowerCase().includes(search.trim().toLowerCase()))
  return <div className={styles.scopeChoices}>
    <div className={styles.scopeChips}>{selected.map(id => {
      const current = options.find(option => option.id === id)
      const name = current?.name ?? saved.find(option => option.id === id)?.name ?? '未知配置项'
      return <button type="button" key={id} onClick={() => onChange(selected.filter(value => value !== id))} aria-label={`移除${label}：${name}`}>{name}{!current ? '（不可用）' : ''} ×</button>
    })}</div>
    <label>搜索{label}<input type="search" value={search} placeholder={`输入${label}名称或编码`} onChange={event => setSearch(event.target.value)} /></label>
    <div className={styles.scopeChoiceList} aria-label={`可选${label}`}>
      {visible.map(option => <label key={option.id}><input type="checkbox" checked={selected.includes(option.id)} onChange={event => onChange(event.target.checked ? [...selected, option.id] : selected.filter(id => id !== option.id))} /><span>{option.name}<small>{option.code}</small></span></label>)}
      {!visible.length && <p>{search ? '没有匹配的选项' : `暂无有效${label}${label === '部门' ? '，可先在组织与人员中配置本门店部门' : ''}`}</p>}
    </div>
  </div>
}
