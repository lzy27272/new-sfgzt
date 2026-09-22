import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { useScopedResource } from '../../shared/useScopedResource'
import { AsyncState } from '../../shared/AsyncState'
import { loadQuickListPositions, loadUsageQuickList, saveUsageQuickList, type UsageItem, type UsageQuickList } from './workUsage'
import { StoreItemPicker } from './StoreItemPicker'
import styles from './storeResources.module.css'
import dailyStyles from './dailyUsage.module.css'

export function UsageQuickListConfig({ identity, hotelId }: { identity: RoleContext; hotelId: string }) {
  const positions = useScopedResource(`${identity.key}:${hotelId}:quick-list-positions`, signal => loadQuickListPositions(identity,hotelId,signal), [])
  const [selected, setSelected] = useState('')
  const positionId = selected || positions.data.find(position => position.code === 'HOUSEKEEPING_ATTENDANT')?.id || positions.data[0]?.id
  return <details className={`${styles.usageForm} ${dailyStyles.configuration}`}><summary>岗位日报常用品配置</summary><p>按门店、岗位一次配置，员工打开日报就能直接填写当天合计。这里不设置用量，也不修改历史日报。</p>
    <AsyncState loading={positions.loading} error={positions.error} onRetry={positions.reload} />
    <label>配置岗位<select aria-label="常用品配置岗位" value={positionId || ''} onChange={event => setSelected(event.target.value)}>{positions.data.map(position => <option key={position.id} value={position.id}>{position.name}</option>)}</select></label>
    {positionId && <QuickListResource key={`${identity.key}:${hotelId}:${positionId}`} identity={identity} hotelId={hotelId} positionId={positionId} />}
  </details>
}

function QuickListResource({ identity,hotelId,positionId }: { identity: RoleContext; hotelId: string; positionId: string }) {
  const resource = useScopedResource<UsageQuickList & { items: UsageItem[] } | undefined>(`${identity.key}:${hotelId}:${positionId}:quick-list`, signal => loadUsageQuickList(identity,hotelId,positionId,signal), undefined)
  return <><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />{resource.data && !resource.loading && !resource.error && <QuickListEditor key={resource.data.rowVersion} identity={identity} hotelId={hotelId} positionId={positionId} data={resource.data} onSaved={resource.reload} />}</>
}

function QuickListEditor({ identity,hotelId,positionId,data,onSaved }: { identity: RoleContext; hotelId: string; positionId: string; data: UsageQuickList & { items: UsageItem[] }; onSaved: () => Promise<void> }) {
  const [itemIds,setItemIds] = useState(data.itemIds)
  const [picking,setPicking] = useState(false)
  const [busy,setBusy] = useState(false)
  const [message,setMessage] = useState('')
  const [conflict,setConflict] = useState(false)
  const move = (index: number, delta: number) => setItemIds(previous => {
    const next = [...previous]; [next[index],next[index+delta]]=[next[index+delta],next[index]]; return next
  })
  const save = async () => {
    if (busy) return
    setBusy(true); setMessage(''); setConflict(false)
    try { await saveUsageQuickList(identity,hotelId,positionId,itemIds,data.rowVersion); await onSaved() }
    catch (error) { setMessage(error instanceof Error ? error.message : '保存失败，修改内容仍保留。'); setConflict(true) }
    finally { setBusy(false) }
  }
  return <fieldset disabled={busy} style={{ border: 0, margin: 0, padding: 0, minWidth: 0 }}>
    <p>{data.configured ? '当前已保存本岗位常用品配置。' : '尚未单独配置：目前直接显示本店可填报物品的前100项。'}</p><small>按布草、客用品、清洁用品分组；同组物品按以下顺序显示。取消常用后，员工仍可从“添加其他物品”选择。</small>
    <ol className={styles.resourceList}>{itemIds.map((id,index) => { const item=data.items.find(item => item.id === id); return <li key={id}><div className={styles.actions}><span>{item ? `${item.name} ${item.specification || ''} · ${item.issueUnit}` : '已停用或不可填报物品（请移除）'}</span><button type="button" className="secondary" disabled={index===0} aria-label={`上移第${index+1}项`} onClick={() => move(index,-1)}>上移</button><button type="button" className="secondary" disabled={index===itemIds.length-1} aria-label={`下移第${index+1}项`} onClick={() => move(index,1)}>下移</button><button type="button" className="secondary" aria-label={`移除第${index+1}项常用品`} onClick={() => setItemIds(previous => previous.filter(value => value!==id))}>移除</button></div></li> })}</ol>
    {!itemIds.length && <p>没有常用品。保存后员工可自行添加其他物品；不会自动认定无耗用。</p>}
    <div className={styles.actions}><button type="button" className="secondary" disabled={itemIds.length>=100} onClick={() => setPicking(true)}>添加常用品</button><button type="button" className="primary" onClick={() => void save()}>{busy ? '保存中…' : '保存常用品配置'}</button></div>
    {message && <p role="alert">{message}</p>}{conflict && <button type="button" className="secondary" onClick={() => void onSaved()}>放弃本次修改，重新读取配置</button>}
    {picking && <StoreItemPicker items={data.items} selectedIds={itemIds} onClose={() => setPicking(false)} onConfirm={items => { setItemIds(previous => [...new Set([...previous,...items.map(item => item.id)])]); setPicking(false) }} />}
  </fieldset>
}
