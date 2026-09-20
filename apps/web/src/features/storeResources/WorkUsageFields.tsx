import type { UsageCatalogue, UsageInputLine } from './workUsage'
import { usageInput, usageStatus, usageSummary } from './workUsage'
import { baseQuantityPreview } from './unitConversion'
import styles from './storeResources.module.css'

export function WorkUsageFields({ catalogue, value, disabled, onChange, onRefresh }: {
  catalogue: UsageCatalogue; value: unknown; disabled?: boolean; onChange: (value: Record<string, unknown>) => void; onRefresh: () => void
}) {
  const input = usageInput(value) ?? { schemaVersion: 1 as const, noUsage: false, lines: [] }
  const update = (lines: UsageInputLine[]) => onChange({ ...input, noUsage: false, lines })
  if (catalogue.referenceUsage) return <section className={styles.usageForm} aria-label="已关联当日耗用"><h3>已关联当日物品使用登记</h3><p>{usageStatus(catalogue.referenceUsage.status)} · {usageSummary(catalogue.referenceUsage)}</p><p>日报引用同一份登记，不重复扣库存、计算成本。数量有误请由指定审核人退回或更正。</p><button type="button" className="secondary" disabled={disabled} onClick={onRefresh}>刷新关联数量</button></section>
  return <section className={styles.usageForm} aria-label="实际耗用登记"><h3>实际耗用与布草使用</h3>
    <p>审核人：{catalogue.reviewerName} · {catalogue.reviewerPositionName}。耗品、清洁用品审核通过才扣库；布草只登记使用数量，不扣减总库存，也不计洗涤费。</p>
    {!catalogue.reviewerAvailable && <div role="alert" className={styles.error}>审核人已失效，请联系管理员重新配置后提交。</div>}
    {value && !usageInput(value) ? <div className={styles.error}>旧草稿中的文字数量尚未关联物品，请按清单重新核对填写。</div> : null}
    <fieldset disabled={disabled}>
      <label className={styles.checkbox}><input type="checkbox" checked={input.noUsage} onChange={(event) => onChange({ schemaVersion: 1, noUsage: event.target.checked, lines: [] })} />本次没有实际耗用或布草使用</label>
      {!input.noUsage && input.lines.map((line, index) => {
        const item = catalogue.items.find((entry) => entry.id === line.itemId)
        const preview = item ? baseQuantityPreview(line.quantity, line.unit === item.unit ? 1 : item.issueFactor) : undefined
        const patch = (change: Partial<UsageInputLine>) => update(input.lines.map((entry, i) => i === index ? { ...entry, ...change } : entry))
        return <div key={index} className={styles.usageRow}>
          <label>物品<select aria-label={`第${index + 1}项物品`} value={line.itemId} onChange={(event) => { const next = catalogue.items.find((entry) => entry.id === event.target.value); patch({ itemId: event.target.value, quantity: '', unit: next?.issueUnit ?? '', expectedItemVersion: next?.rowVersion ?? -1 }) }}><option value="">请选择</option>{!item && line.itemId && <option value={line.itemId}>原物品已停用，请重新选择</option>}{catalogue.items.map((entry) => <option key={entry.id} value={entry.id} disabled={input.lines.some((other, i) => i !== index && other.itemId === entry.id)}>{entry.name}{entry.specification ? ` · ${entry.specification}` : ''}{entry.category === 'LINEN' ? '（布草使用）' : ''}</option>)}</select></label>
          <label>数量<input aria-label={`第${index + 1}项数量`} type="number" min="0.0001" step="0.0001" value={line.quantity} onChange={(event) => patch({ quantity: event.target.value })} /></label>
          <label>单位<select aria-label={`第${index + 1}项单位`} value={line.unit} onChange={(event) => patch({ unit: event.target.value, quantity: '' })}>{item ? [...new Set([item.issueUnit, item.unit])].map((unit) => <option key={unit}>{unit}</option>) : <option value="">先选择物品</option>}</select></label>
          <button type="button" className="secondary" onClick={() => update(input.lines.filter((_, i) => i !== index))}>移除</button>
          {item && <small className={styles.full}>{line.expectedItemVersion !== item.rowVersion ? '配置已变化，请重新选择该物品并核对数量。' : preview?.error || (preview?.quantity ? `折合 ${preview.quantity} ${item.unit}；${item.category === 'LINEN' ? '仅登记布草使用' : '待审核通过后扣库'}` : `1 ${item.issueUnit} = ${item.issueFactor} ${item.unit}`)}</small>}
        </div>
      })}
      {!input.noUsage && <button type="button" className="secondary" disabled={input.lines.length >= 100 || !catalogue.items.length} onClick={() => update([...input.lines, { itemId: '', quantity: '', unit: '', expectedItemVersion: -1 }])}>添加使用物品</button>}
      <button type="button" className="link-button" onClick={onRefresh}>刷新物品清单</button>
      {!catalogue.items.length && <p>门店尚未配置可填报物品，请联系管理员添加；确无使用时可勾选“本次没有”。</p>}
    </fieldset>
  </section>
}
