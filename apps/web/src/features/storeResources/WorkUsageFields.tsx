import { useState } from 'react'
import type { UsageCatalogue, UsageInputLine } from './workUsage'
import { usageInput, usageStatus, usageSummary } from './workUsage'
import { baseQuantityPreview } from './unitConversion'
import { appendUsageItems, matchesItemScope, type ItemScopeFilter } from './itemSelection'
import { UsageItemNavigator, UsageScopeNotice, useUsageQuantityNavigation } from './UsageItemNavigator'
import { StoreItemPicker } from './StoreItemPicker'
import { IssueUsageFields } from './IssueUsageFields'
import styles from './storeResources.module.css'

export function WorkUsageFields({ catalogue, value, disabled, onChange, onRefresh }: {
  catalogue: UsageCatalogue; value: unknown; disabled?: boolean; onChange: (value: Record<string, unknown>) => void; onRefresh: () => void
}) {
  const [picking, setPicking] = useState(false)
  const [scope, setScope] = useState<ItemScopeFilter>('ALL')
  const navigation = useUsageQuantityNavigation()
  const input = usageInput(value) ?? { schemaVersion: 1 as const, noUsage: false, lines: [] }
  const invalidDraft = value != null && !usageInput(value)
  const issue = catalogue.mode === 'ISSUE'
  const update = (lines: UsageInputLine[]) => onChange({ ...input, noUsage: false, lines })
  if (issue) return <IssueUsageFields catalogue={catalogue} value={value} disabled={disabled} onChange={onChange} onRefresh={onRefresh} />
  if (catalogue.referenceUsage) return <section className={styles.usageForm} aria-label="已关联当日耗用"><h3>已关联当日物品使用登记</h3><p>{usageStatus(catalogue.referenceUsage.status)} · {usageSummary(catalogue.referenceUsage)}</p><p>日报引用同一份登记，不重复扣库存、计算成本。数量有误请由指定审核人退回或更正。</p><button type="button" className="secondary" disabled={disabled} onClick={onRefresh}>刷新关联数量</button></section>
  return <section className={styles.usageForm} aria-label={issue ? '物品领用登记' : '当日物品使用数量'}><h3>{issue ? '物品领用登记' : '当日物品使用数量'}</h3>
    <p>{issue ? '领用不等于实际消耗，本次只登记领用明细，不作为经营成本。请在下班时另行填写实际使用数量。' : '审核人：' + (catalogue.reviewerName || '未配置') + ' · ' + (catalogue.reviewerPositionName || '') + '。耗品、清洁用品审核通过才扣库；布草正常使用不扣减总库存，不重复计洗涤费。'}</p>
    <p>物品与单位以后台配置为准。选好物品后只需填写数量，继续添加不会清空已填内容。</p>
    {!catalogue.reviewerAvailable && !issue && <div role="alert" className={styles.error}>审核人已失效，请联系管理员重新配置后提交。</div>}
    {invalidDraft && <div role="alert" className={styles.error}>旧草稿物品格式无法识别，已保留原内容。请联系管理员核对，不会自动清空。</div>}
    <UsageScopeNotice catalogue={catalogue} />
    <fieldset disabled={disabled || invalidDraft}>
      <UsageItemNavigator items={catalogue.items} selectedIds={input.lines.map(line => line.itemId)} scope={scope} onScopeChange={setScope} onLocate={item => { update(appendUsageItems(input.lines, [item])); navigation.locate(item.id) }} />
      <label className={styles.checkbox}><input type="checkbox" checked={input.noUsage} disabled={input.lines.length > 0} onChange={event => onChange({ schemaVersion: 1, noUsage: event.target.checked, lines: [] })} />{issue ? '本次没有领用' : '本次没有实际耗用或布草使用'}{input.lines.length > 0 && '（请先移除已填物品）'}</label>
      {!input.noUsage && input.lines.map((line, index) => {
        const item = catalogue.items.find(entry => entry.id === line.itemId)
        if (item && !matchesItemScope(item, scope)) return null
        const preview = item ? baseQuantityPreview(line.quantity, line.unit === item.unit ? 1 : item.issueFactor) : undefined
        return <div key={line.itemId || index} className={styles.usageRow}>
          <div><strong>{item?.name || '原物品不可用，请移除后重新选择'}</strong><small>{item?.specification || '未配置规格'}</small></div>
          <label>数量<input ref={navigation.inputRef(line.itemId)} aria-label={(item?.name || '第' + (index + 1) + '项') + '数量'} type="number" inputMode="decimal" min="0.0001" step="0.0001" value={line.quantity} onChange={event => update(input.lines.map((entry, i) => i === index ? { ...entry, quantity: event.target.value } : entry))} /></label>
          <div><small>单位（后台配置）</small><strong aria-label={(item?.name || '物品') + '单位'}>{line.unit || '—'}</strong></div>
          <button type="button" className="link-button" onClick={() => update(input.lines.filter((_, i) => i !== index))}>移除</button>
          {item && <small className={styles.full}>{line.expectedItemVersion !== item.rowVersion ? '配置已变化，请移除后重新选择该物品并核对数量。' : preview?.error || (preview?.quantity ? '折合 ' + preview.quantity + ' ' + item.unit : '1 ' + item.issueUnit + ' = ' + item.issueFactor + ' ' + item.unit)}</small>}
        </div>
      })}
      {scope !== 'ALL' && <p>其他范围已填数量仍保留并随本次提交。<button type="button" className="link-button" onClick={() => setScope('ALL')}>查看全部适用物品</button></p>}
      {!input.noUsage && <button type="button" className={'secondary ' + styles.addItems} disabled={input.lines.length >= 100 || !catalogue.items.length} onClick={() => setPicking(true)}>＋ {input.lines.length ? '继续添加物品' : '搜索、选择物品'}</button>}
      <button type="button" className="link-button" onClick={onRefresh}>刷新物品清单</button>
      {!catalogue.items.length && <p>门店尚未配置当前岗位可填报物品，请联系管理员添加；确无使用时可勾选“本次没有”。</p>}
    </fieldset>
    {picking && !disabled && !invalidDraft && <StoreItemPicker items={catalogue.items.filter(item => matchesItemScope(item, scope))} selectedIds={input.lines.map(line => line.itemId)} onClose={() => setPicking(false)} onConfirm={items => { update(appendUsageItems(input.lines, items)); setPicking(false) }} />}
  </section>
}
