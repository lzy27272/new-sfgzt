import { useState } from 'react'
import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { reviewWorkRecord } from '../../api/resources'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { correctWorkUsage, loadUsageCatalogue, loadUsageConfig, loadUsageReviewers, loadWorkUsages, reverseWorkUsage, saveUsageConfig, usageStatus, usageSummary, usageValidation } from './workUsage'
import type { UsageCatalogue, UsageConfig, UsageDetail, UsageInput, UsageRow } from './workUsage'
import { WorkUsageFields } from './WorkUsageFields'
import styles from './storeResources.module.css'

export function UsageEvidenceSummary({ usage }: { usage: UsageDetail }) {
  return <section className={styles.usageForm}><h3>物品耗用审核</h3><strong>{usageStatus(usage.status)}</strong>{usage.referenced && <p>已关联当日物品使用登记，不重复扣库存或计费。</p>}<p>{usageSummary(usage)}</p>{!!usage.correctionVersion && <p>已更正 {usage.correctionVersion} 次，以上为最新核定数量；原提交及更正记录保留。{usage.corrections?.[0]?.reason && `最近更正原因：${usage.corrections[0].reason}`}</p>}{usage.reversalReason && <p>冲销原因：{usage.reversalReason}</p>}<small>布草使用不计采购消耗和洗涤费用；退回不扣库，冲销保留原记录。</small></section>
}

export function WorkUsagePanel({ identity, hotelId, canConfigure, canReverse, go, onStockChanged }: {
  identity: RoleContext; hotelId: string; canConfigure: boolean; canReverse: boolean; go: AppNavigate; onStockChanged: () => Promise<void>
}) {
  const [filter, setFilter] = useState('PENDING')
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState<string>()
  const [error, setError] = useState<string>()
  const [reasons, setReasons] = useState<Record<string, string>>({})
  const [confirmation, setConfirmation] = useState<{ row: UsageRow; action: 'APPROVED' | 'REJECTED' | 'REVERSE' }>()
  const [editing, setEditing] = useState<string>()
  const config = useScopedResource<UsageConfig | undefined>(`${identity.key}:${hotelId}:usage-config`, (signal) => loadUsageConfig(identity, hotelId, signal), undefined)
  const reviewers = useScopedResource(`${identity.key}:${hotelId}:usage-reviewers:${canConfigure}`, (signal) => canConfigure ? loadUsageReviewers(identity, hotelId, signal) : Promise.resolve([]), [])
  const records = useScopedResource(`${identity.key}:${hotelId}:work-usage:${filter}`, (signal) => loadWorkUsages(identity, hotelId, filter, signal), [])
  const [selectedReviewer, setSelectedReviewer] = useState<string>()
  const reviewerId = selectedReviewer ?? config.data?.reviewerAssignmentId ?? ''
  const command = async (run: () => Promise<unknown>, notice: string) => {
    if (busy) return
    setBusy(true); setError(undefined); setMessage(undefined)
    try { await run(); setConfirmation(undefined); setMessage(notice); await Promise.all([config.reload(), records.reload(), onStockChanged()]) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败，请刷新核对后重试。') }
    finally { setBusy(false) }
  }
  return <section className={styles.panel}><header><div><h2>日清耗用审核</h2><p>员工填报 → 门店指定人员复核 → 耗品和清洁用品扣库；与工作记录复核一次完成。</p></div><button type="button" className="secondary" disabled={busy} onClick={() => void Promise.all([records.reload(), config.reload(), reviewers.reload()])}>刷新审核列表</button></header>
    <AsyncState loading={config.loading} error={config.error} onRetry={config.reload} />
    {config.data && <section className={styles.usageForm}><h3>本店耗用审核人</h3><p>{config.data.enabled ? `${config.data.reviewerName} · ${config.data.reviewerPositionName}` : '尚未启用：原有文字填报不自动扣库。配置审核人后，新提交的耗用工作必须按清单填写。'}</p>{config.data.enabled && !config.data.reviewerAvailable && <p className={styles.error}>当前审核人已失效，请重新选择有效人员。</p>}
      {canConfigure && <><p>可按门店选择客房主管、店助或店长等有权限的人员。更换后，未审核记录也交给新审核人，原分配及操作历史保留。</p><div className={styles.actions}><label>审核人员<select aria-label="耗用审核人员" disabled={busy || reviewers.loading} value={reviewerId} onChange={(event) => setSelectedReviewer(event.target.value)}><option value="">请选择</option>{config.data.reviewerAssignmentId && !reviewers.data.some((person) => person.assignmentId === config.data?.reviewerAssignmentId) && <option value={config.data.reviewerAssignmentId}>原审核人（已失效或无权限）</option>}{reviewers.data.map((person) => <option key={person.assignmentId} value={person.assignmentId}>{person.employeeName} · {person.positionName}</option>)}</select></label><button type="button" className="primary" disabled={busy || !reviewers.data.some((person) => person.assignmentId === reviewerId)} onClick={() => void command(() => saveUsageConfig(identity, hotelId, reviewerId, config.data!.rowVersion), '耗用审核配置已保存，未审核记录已按新配置流转。')}>保存审核配置</button></div><AsyncState loading={reviewers.loading} error={reviewers.error} onRetry={reviewers.reload} />{!reviewers.loading && !reviewers.error && !reviewers.data.length && <p>没有可选人员。请先配置本店有效任职，并授予工作复核、门店库存查看及模块权限。</p>}</>}
    </section>}
    <label>审核状态<select aria-label="耗用审核状态" value={filter} disabled={busy} onChange={(event) => { setFilter(event.target.value); setConfirmation(undefined) }}>{['PENDING', 'APPROVED', 'REJECTED', 'REVERSED', 'ALL'].map((status) => <option key={status} value={status}>{status === 'ALL' ? '全部状态' : usageStatus(status)}</option>)}</select></label><small>显示所选状态最近100条记录，历史记录不删除。</small>
    {message && <div className={styles.success} role="status">{message}</div>}{error && <div className={styles.error} role="alert">{error}</div>}
    <AsyncState loading={records.loading} error={records.error} empty={!records.data.length} onRetry={records.reload} emptyTitle="当前状态暂无耗用记录" />
    <div className={styles.resourceList}>{records.data.map((row) => <article key={row.workRecordId}><h3>{row.employeeName} · {row.workTitle}</h3><p>{row.businessDate} · {usageStatus(row.status)}</p><p>{usageSummary(row)}</p>{row.reviewReason && <p>复核意见：{row.reviewReason}</p>}{row.reversalReason && <p>冲销原因：{row.reversalReason}</p>}
      {(row.canReview || (canReverse && row.status === 'APPROVED')) && <label>处理说明<textarea rows={2} maxLength={1000} disabled={busy} value={reasons[row.workRecordId] ?? ''} placeholder="退回或冲销时必填" onChange={(event) => setReasons({ ...reasons, [row.workRecordId]: event.target.value })} /></label>}
      <div className={styles.actions}>{row.canReview && <><button type="button" className="primary" disabled={busy} onClick={() => setConfirmation({ row, action: 'APPROVED' })}>审核通过并记账</button><button type="button" className="secondary" disabled={busy || !reasons[row.workRecordId]?.trim()} onClick={() => setConfirmation({ row, action: 'REJECTED' })}>退回更正</button></>}{row.canCorrect && <button type="button" className="primary" disabled={busy} onClick={() => setEditing(row.workRecordId)}>更正数量并同步库存成本</button>}{canReverse && row.status === 'APPROVED' && <button type="button" className="secondary" disabled={busy || !reasons[row.workRecordId]?.trim()} onClick={() => setConfirmation({ row, action: 'REVERSE' })}>冲销原耗用</button>}{row.workExpectationId && <button type="button" className="link-button" onClick={() => go('team-work', { hotelId, expectationId: row.workExpectationId, recordId: row.workRecordId })}>查看工作与证据</button>}</div>
      {row.corrections?.length ? <details><summary>更正历史（{row.corrections.length} 次）</summary>{row.corrections.map((entry) => <p key={entry.id}>第 {entry.revisionNo} 次 · {entry.operatorName} · {new Date(entry.createdAt).toLocaleString('zh-CN')}：{entry.reason}</p>)}</details> : null}
      {editing === row.workRecordId && <UsageCorrectionEditor key={`${identity.key}:${hotelId}:${row.workRecordId}:${row.correctionVersion}`} identity={identity} hotelId={hotelId} row={row} busy={busy} onCancel={() => setEditing(undefined)} onSave={(quantities, reason) => void command(async () => { await correctWorkUsage(identity, hotelId, row, quantities, reason); setEditing(undefined) }, '数量已更正，库存与成本已同步，原始提交及更正记录已保留。')} />}
      {confirmation?.row.workRecordId === row.workRecordId && <div className={styles.confirmation} role="alert"><p>{confirmation.action === 'APPROVED' ? '确认数量无误？通过后按提交时换算比例扣除耗品、清洁用品；布草仅记录使用。' : confirmation.action === 'REJECTED' ? '确认退回？本次不扣库存，员工需更正后重新提交。' : '确认冲销？恢复原扣减数量和原成本，保留原审核、原流水及冲销原因。工作完成记录不删除；正确耗用需由授权人员另行登记并关联本次冲销。'}</p><div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={() => setConfirmation(undefined)}>取消</button><button type="button" className="primary" disabled={busy} onClick={() => { const action = confirmation.action; void command(() => action === 'REVERSE' ? reverseWorkUsage(identity, hotelId, row.workRecordId, reasons[row.workRecordId]?.trim() ?? '') : reviewWorkRecord(identity, row.workRecordId, action, reasons[row.workRecordId]?.trim() ?? '', row.recordVersion), action === 'APPROVED' ? '复核通过，耗用已记账，布草使用已留痕。' : action === 'REJECTED' ? '已退回更正，没有扣减库存。' : '已冲销并恢复原数量和成本，历史流水已保留。') }}>{busy ? '处理中…' : '确认操作'}</button></div></div>}
    </article>)}</div>
  </section>
}

function UsageCorrectionEditor({ identity, hotelId, row, busy, onCancel, onSave }: { identity: RoleContext; hotelId: string; row: UsageRow; busy: boolean; onCancel: () => void; onSave: (quantities: UsageInput, reason: string) => void }) {
  const catalogue = useScopedResource<UsageCatalogue | undefined>(`${identity.key}:${hotelId}:correction-catalogue`, (signal) => loadUsageCatalogue(identity, hotelId, signal), undefined)
  return <div className={styles.confirmation}><AsyncState loading={catalogue.loading} error={catalogue.error} onRetry={catalogue.reload} />{catalogue.data && !catalogue.loading && !catalogue.error && <UsageCorrectionForm key={catalogue.data.items.map((item) => `${item.id}:${item.rowVersion}`).join('|')} catalogue={catalogue.data} row={row} busy={busy} onCancel={onCancel} onSave={onSave} onRefresh={() => void catalogue.reload()} />}</div>
}

export function UsageCorrectionForm({ catalogue, row, busy, onCancel, onSave, onRefresh }: { catalogue: UsageCatalogue; row: UsageRow; busy: boolean; onCancel: () => void; onSave: (quantities: UsageInput, reason: string) => void; onRefresh: () => void }) {
  const [quantities, setQuantities] = useState<UsageInput>(() => ({ schemaVersion: 1, noUsage: row.noUsage, lines: row.lines.map((line) => ({ itemId: line.itemId, quantity: String(line.baseQuantity), unit: line.baseUnit, expectedItemVersion: catalogue.items.find((item) => item.id === line.itemId)?.rowVersion ?? -1 })) }))
  const [reason, setReason] = useState('')
  const invalid = usageValidation(quantities, catalogue)
  return <><h3>更正已审核使用数量</h3><p>下方先按库存基础单位带入。保存会恢复上一版数量和原金额，再按更正数量及当前库存加权均价重新记账；整个过程一次完成。原日报、证据和已关账月结不会被覆盖，经营汇总按原营业日更新。</p><WorkUsageFields catalogue={catalogue} value={quantities} disabled={busy} onChange={(value) => setQuantities(value as UsageInput)} onRefresh={onRefresh} /><label>更正原因<textarea maxLength={1000} rows={2} value={reason} disabled={busy} onChange={(event) => setReason(event.target.value)} /></label>{invalid && <p role="alert">{invalid}</p>}<div className={styles.actions}><button type="button" className="secondary" disabled={busy} onClick={onCancel}>取消更正</button><button type="button" className="primary" disabled={busy || !!invalid || !reason.trim()} onClick={() => onSave(quantities, reason.trim())}>{busy ? '正在同步…' : '确认更正并同步'}</button></div></>
}
