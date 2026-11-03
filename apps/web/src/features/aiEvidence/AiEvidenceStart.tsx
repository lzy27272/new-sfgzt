import { useState } from 'react'
import type { RoleContext, WorkExpectation } from '../../domain'
import { loadTeamWork } from '../../api/resources'
import { useScopedResource } from '../../shared/useScopedResource'
import { localBusinessDate, validDate } from '../workbench/workBoardModel'
import { errorText } from './model'
import { groomingWorkCandidates } from './startModel'

export function AiEvidenceStart({ identity, hotelId, onOpenWork }: {
  identity: RoleContext; hotelId?: string; onOpenWork: (item: WorkExpectation) => void;
}) {
  const [date, setDate] = useState(localBusinessDate)
  const resource = useScopedResource<WorkExpectation[]>(`ai-start:${identity.key}:${hotelId ?? ''}:${date}`, async () => {
    if (!validDate(date)) return []
    const result = await loadTeamWork(identity, { orgUnitId: hotelId, businessDate: date })
    if (result.source !== 'api') throw new Error('未读取到真实工作记录，请重试。')
    return groomingWorkCandidates(result.data)
  }, [])
  return <section className="ai-card ai-start" aria-label="选择原工作发起AI检查">
    <h2>选择原工作，再确认员工和照片</h2>
    <p className="ai-muted">这里显示所选日期已提交的仪容工作，无需先有 AI 检查记录。本人可发起检查；授权管理人员的历史补查目前仅限两家试点店 2026 年 10 月 27 日这批记录。</p>
    <div className="ai-start-toolbar"><label>原工作日期<input aria-label="AI原工作日期" type="date" value={date} onChange={event => setDate(event.target.value)} /></label>
      <button type="button" className="secondary" onClick={() => setDate('2026-10-27')}>查看10月27日补查工作</button>
      <button type="button" className="secondary" onClick={() => void resource.reload()}>刷新原工作</button></div>
    {!validDate(date) && <p className="ai-error" role="alert">请选择完整的工作日期。</p>}
    {resource.loading && <p role="status">正在读取已提交的仪容工作…</p>}
    {!!resource.error && <p className="ai-error" role="alert">原工作读取失败：{errorText(resource.error)}<button type="button" className="secondary" onClick={() => void resource.reload()}>重试原工作</button></p>}
    {!resource.loading && !resource.error && validDate(date) && !resource.data.length && <p className="ai-empty">该日期和授权范围内没有已提交的仪容工作。请核对原工作日期；尚未提交的工作需先完成填报。</p>}
    {!resource.loading && !resource.error && <div className="ai-start-work-list">{resource.data.map(item => <article key={item.id}>
      <div><strong>{item.title}</strong><small>{item.hotelName || item.targetOrgName} · {item.assigneeName} · {item.positionName}</small><small>原工作日期：{item.businessDate}</small></div>
      <button type="button" className="primary" onClick={() => onOpenWork(item)}>选择员工与照片</button>
    </article>)}</div>}
    <p className="ai-muted">进入工作详情后会再次核对权限、执行标准和照片有效性；仅在你确认人员与照片并点击发起后，才会调用 AI。</p>
  </section>
}
