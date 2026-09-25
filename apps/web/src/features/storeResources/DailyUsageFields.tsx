import { useState } from 'react'
import { StoreItemPicker } from './StoreItemPicker'
import { WorkUsageFields } from './WorkUsageFields'
import { dailyUsageValue, emptyUsageLine, readDailyUsage, usageGroups, type DailyUsageInput } from './dailyUsage'
import { usageInput, type UsageCatalogue } from './workUsage'
import { matchesItemScope, type ItemScopeFilter } from './itemSelection'
import { UsageItemNavigator, UsageScopeNotice, useUsageQuantityNavigation } from './UsageItemNavigator'
import styles from './dailyUsage.module.css'

export function DailyUsageFields({ catalogue, value, disabled, onChange, onRefresh }: {
  catalogue: UsageCatalogue; value: unknown; disabled?: boolean; onChange: (value: DailyUsageInput) => void; onRefresh: () => void
}) {
  const [picking, setPicking] = useState(false)
  const [scope, setScope] = useState<ItemScopeFilter>('ALL')
  const navigation = useUsageQuantityNavigation()
  if (catalogue.referenceUsage) return <WorkUsageFields catalogue={catalogue} value={value} disabled={disabled} onChange={() => {}} onRefresh={onRefresh} />
  const input = dailyUsageValue(value, catalogue)
  const update = (next: Partial<DailyUsageInput>) => onChange({ ...input, ...next })
  const invalidDraft = value != null && !readDailyUsage(value) && !usageInput(value)
  const rows = input.lines.map(line => ({ line, item: catalogue.items.find(item => item.id === line.itemId) }))
  const changed = rows.some(({ line, item }) => !item || line.expectedItemVersion !== item.rowVersion)
  return <fieldset className={styles.form} disabled={disabled || invalidDraft}>
    <legend>当日物品使用</legend><p>常用物品直接填当天合计，不用搜索添加</p>
    {invalidDraft && <p role="alert">旧草稿物品格式无法识别，已保留原内容。请联系管理员核对，不会自动清空。</p>}
    {(!catalogue.enabled || !catalogue.reviewerAvailable) && <p role="alert" className={styles.warning}>门店尚未配置有效耗用审核人，请联系管理员配置后提交；可以先保存草稿。</p>}
    {changed && <p role="alert" className={styles.warning}>物品配置已变化，已填数量仍保留。请核对标记物品的单位、数量后确认。<button type="button" className="link-button" onClick={onRefresh}>刷新清单</button></p>}
    <UsageScopeNotice catalogue={catalogue} />
    {catalogue.items.some(item => item.issuedToday) && <section className={styles.group} aria-label="当日已领物品"><h3>当日已领物品</h3><p>来自本任职、本营业日已提交的领用记录，包括跨部门物品。只补充清单，实际用量请自行填写，不复制领用数量。</p>{catalogue.items.filter(item => item.issuedToday).map(item => <button type="button" key={item.id} className="secondary" disabled={!input.lines.some(line => line.itemId === item.id) && input.lines.length >= 100} onClick={() => {
      setScope('ALL')
      if (!input.lines.some(line => line.itemId === item.id)) update({ lines: [...input.lines, emptyUsageLine(item)], remainingUnusedConfirmed: false })
      navigation.locate(item.id)
    }}>{item.name} · {input.lines.some(line => line.itemId === item.id) ? '填写用量' : '添加并填写'}</button>)}</section>}
    {!catalogue.quickList?.configured && <small>目前显示当前任职适用清单（最多100项）；管理员可在“日清耗用审核”配置岗位常用品。</small>}
    <UsageItemNavigator items={catalogue.items} selectedIds={input.lines.map(line => line.itemId)} scope={scope} onScopeChange={setScope} onLocate={item => {
      if (!input.lines.some(line => line.itemId === item.id)) update({ lines: [...input.lines, emptyUsageLine(item)], remainingUnusedConfirmed: false })
      navigation.locate(item.id)
    }} />
    {[...usageGroups, ['UNAVAILABLE', '需核对的物品'] as const].map(([category, label]) => {
      const group = rows.filter(({ item }) => (item?.category ?? 'UNAVAILABLE') === category && (!item || matchesItemScope(item, scope)))
      if (!group.length) return null
      return <section key={category} className={styles.group} aria-label={label}><h3>{label}</h3><div className={styles.columns} aria-hidden="true"><span>物品 / 规格</span><span>单位</span><span>今日用量</span></div>
        {group.map(({ line, item }) => <div className={styles.row} key={line.itemId}>
          <div><strong>{item?.name ?? '已停用或不可用物品'}</strong><small>{item?.specification || '按实际使用登记'}{item?.issuedToday && ' · 当日已领用'}</small>
            {!input.commonItemIds.includes(line.itemId) && <button type="button" className="link-button" aria-label={`移除${item?.name ?? '不可用物品'}`} onClick={() => update({ lines: input.lines.filter(row => row.itemId !== line.itemId), remainingUnusedConfirmed: false })}>移除</button>}
          </div><span>{line.unit}</span><input ref={navigation.inputRef(line.itemId)} aria-label={`${item?.name ?? '不可用物品'}${item?.specification ? ` ${item.specification}` : ''}今日用量`} type="text" inputMode="decimal" autoComplete="off" placeholder="未填" value={line.quantity} onChange={event => update({ lines: input.lines.map(row => row.itemId === line.itemId ? { ...row, quantity: event.target.value } : row) })} />
          {item && line.expectedItemVersion !== item.rowVersion && <div className={styles.stale}>原单位：{line.unit}；当前单位：{item.issueUnit}。数量保留，请核对后确认。<button type="button" className="secondary" onClick={() => update({ lines: input.lines.map(row => row.itemId === item.id ? { ...row, expectedItemVersion: item.rowVersion, unit: item.issueUnit } : row), remainingUnusedConfirmed: false })}>已核对单位和数量</button></div>}
        </div>)}
      </section>
    })}
    {!rows.length && <p>尚无适用常用品，可搜索或添加可用物品；请先核实清单是否完整，确未使用时再明确确认。</p>}
    {scope !== 'ALL' && <p>当前仅查看所选范围，其他范围已填的数量仍保留并随本次提交。<button type="button" className="link-button" onClick={() => setScope('ALL')}>查看全部适用物品</button></p>}
    <button type="button" className={`secondary ${styles.add}`} disabled={input.lines.length >= 100} onClick={() => setPicking(true)}>＋ 添加其他物品</button>
    <button type="button" className={`link-button ${styles.refresh}`} onClick={onRefresh}>刷新物品清单（保留已填内容）</button>
    <label className={styles.confirm}><input type="checkbox" checked={input.remainingUnusedConfirmed} onChange={event => update({ remainingUnusedConfirmed: event.target.checked })} /><span>确认其余未填物品本日未使用<small>未勾选前，空白不视为零。</small></span></label>
    <p className={styles.warning}>耗用由门店指定人员复核，通过后扣库。{catalogue.reviewerAvailable && catalogue.reviewerName && <small>审核人：{catalogue.reviewerName} · {catalogue.reviewerPositionName}</small>}</p><small>布草正常使用不扣减总库存。</small>
    {picking && !disabled && !invalidDraft && <StoreItemPicker items={catalogue.items.filter(item => matchesItemScope(item, scope))} selectedIds={input.lines.map(line => line.itemId)} onClose={() => setPicking(false)} onConfirm={items => { update({ lines: [...input.lines, ...items.filter(item => !input.lines.some(line => line.itemId === item.id)).map(emptyUsageLine)], remainingUnusedConfirmed: false }); setPicking(false) }} />}
  </fieldset>
}
