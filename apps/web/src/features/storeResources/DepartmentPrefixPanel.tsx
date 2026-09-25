import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { AsyncState } from '../../shared/AsyncState'
import { featureApiMutation, featureApiRequest } from '../shared/featureApi'
import { usageScopeSummary, type ItemUsageScope, type PrefixConfig, type UsageScopeOption } from './usageScope'
import styles from './crossDepartment.module.css'

type PreviewRow = { id: string; type: 'ITEM' | 'ASSET'; code: string; name: string; rowVersion: number; usageScope: ItemUsageScope; inferredDepartmentId?: string; action: 'FILL' | 'PRESERVE' | 'MANUAL' }
type Preview = { config: PrefixConfig; departments: UsageScopeOption[]; rows: PreviewRow[] }
export function DepartmentPrefixPanel({ identity, hotelId, canConfigure, onApplied }: {
  identity: RoleContext; hotelId: string; canConfigure: boolean; onApplied: () => Promise<unknown>
}) {
  const path = `/store-resources/hotels/${encodeURIComponent(hotelId)}/department-prefix`
  const resource = useScopedResource<Preview | undefined>(`${identity.key}:prefix:${hotelId}:${canConfigure}`, signal => canConfigure ? featureApiRequest<Preview>(path, identity, { signal }) : Promise.resolve(undefined), undefined)
  const command = useStableCommand(`department-prefix:${identity.key}:${hotelId}`)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<string[]>([])
  const [ruleDraft, setRuleDraft] = useState<Record<string, string> | undefined>()
  const rowKey = (row: PreviewRow) => `${row.type}:${row.id}`
  const reload = async () => { setSelected([]); setRuleDraft(undefined); await resource.reload() }
  const execute = async (action: (key: string) => Promise<unknown>, message: string, applied = false) => {
    setError(''); setMessage('')
    try { const result = await command.run(action); if (result === undefined) return; setMessage(message); await reload(); if (applied) await onApplied() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '保存失败，请重新预览核对后重试') }
  }
  const data = resource.data
  const chosen = data?.rows.filter(row => row.action === 'FILL' && selected.includes(rowKey(row))) ?? []
  return <section className={styles.panel}>
    <h2>按编号补全使用部门</h2><p>保留现有编号；仅补全尚未配置的部门，已人工配置的部门优先，不自动覆盖。</p>
    {!canConfigure ? <p>此操作需要物品配置和固定资产管理权限，请联系管理员。</p> : <>
      <AsyncState loading={resource.loading} error={resource.error} empty={false} onRetry={reload} />
      {data && <>
        <h3>编号识别规则</h3>
        <div className={`${styles.table} ${styles.rules}`}><table><thead><tr><th>编号前缀</th><th>默认含义</th><th>对应本店部门</th></tr></thead><tbody>{data.config.rules.map(rule => <tr key={rule.prefix}><td><strong>{rule.prefix}-</strong></td><td>{rule.defaultName}</td><td><select aria-label={`${rule.prefix}对应部门`} value={ruleDraft?.[rule.prefix] ?? rule.departmentId ?? ''} disabled={command.busy} onChange={event => { setSelected([]); setRuleDraft({ ...Object.fromEntries(data.config.rules.map(r => [r.prefix, r.departmentId ?? ''])), ...ruleDraft, [rule.prefix]: event.target.value }) }}><option value="">未匹配时使用门店通用</option>{data.departments.map(d => <option value={d.id} key={d.id}>{d.name} · {d.code}</option>)}</select></td></tr>)}</tbody></table></div>
        {ruleDraft && <div className={styles.actions}><p>规则有未保存修改，保存后请重新核对补全预览。</p><button className="secondary" type="button" disabled={command.busy} onClick={() => setRuleDraft(undefined)}>取消修改</button><button type="button" className="primary" disabled={command.busy} onClick={() => void execute(() => featureApiMutation(path, identity, { method: 'PUT', body: { expectedVersion: data.config.rowVersion, qtDepartmentId: ruleDraft.QT || null, kfDepartmentId: ruleDraft.KF || null, wxDepartmentId: ruleDraft.WX || null } }), '编号规则已保存；尚未修改历史物品或资产。')}>保存编号规则</button></div>}
        <p className={styles.note}>只有当前门店的有效部门可以参与识别。未知前缀、部门缺失或重名时按门店通用补全；明确指定的部门和岗位不会被编号覆盖。</p>
        <div className={styles.actions}><h3>补全预览</h3><button type="button" className="secondary" disabled={command.busy} onClick={() => void reload()}>重新预览</button></div>
        <div className={styles.table}><table><thead><tr><th>选择</th><th>类型</th><th>编号 / 名称</th><th>当前使用部门</th><th>识别部门</th><th>处理方式</th></tr></thead><tbody>{data.rows.map(row => <tr key={rowKey(row)}><td><input aria-label={`补全${row.code}`} type="checkbox" disabled={row.action !== 'FILL' || command.busy || !!ruleDraft || (chosen.length >= 200 && !selected.includes(rowKey(row)))} checked={selected.includes(rowKey(row))} onChange={event => setSelected(event.target.checked ? [...selected, rowKey(row)] : selected.filter(key => key !== rowKey(row)))} /></td><td>{row.type === 'ITEM' ? '物品' : '资产'}</td><td><strong>{row.code}</strong><small>{row.name}</small></td><td>{usageScopeSummary(row.usageScope).departments}</td><td>{data.departments.find(d => d.id === row.inferredDepartmentId)?.name ?? '门店通用'}</td><td>{row.action === 'FILL' ? '可补全' : row.action === 'PRESERVE' ? '保留人工配置' : '待人工配置'}</td></tr>)}</tbody></table>{!data.rows.length && <p>当前门店暂无物品或资产。</p>}</div>
        <div className={styles.actions}><div><strong>已选择 {chosen.length} 项</strong><p>最多每批200项；仅更新使用部门，不修改岗位、库存数量、成本、流水或责任人。</p></div><button type="button" className="primary" disabled={command.busy || resource.loading || !!resource.error || !chosen.length || !!ruleDraft} onClick={() => void execute(key => featureApiMutation(`${path}/apply`, identity, { method: 'POST', idempotencyKey: key, body: { configVersion: data.config.rowVersion, selections: chosen.map(row => ({ id: row.id, type: row.type, departmentId: row.inferredDepartmentId, expectedVersion: row.rowVersion })) } }), `已补全 ${chosen.length} 项使用部门，库存与成本未改变。`, true)}>{command.busy ? '处理中…' : `确认补全 ${chosen.length} 项`}</button></div>
      </>}
      {message && <p role="status" className={styles.success}>{message}</p>}{error && <p role="alert" className={styles.error}>{error}</p>}
    </>}
  </section>
}
