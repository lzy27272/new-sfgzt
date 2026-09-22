import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useScopedResource } from '../../shared/useScopedResource'
import { loadCurrentBusinessDay, loadMyDailyReports } from '../dailyReports/api'
import type { DailyReportSummary } from '../dailyReports/types'
import { formatLocalDateTime } from '../shared/FeatureUI'

export function DailyReportTodo({ identity, go }: { identity: RoleContext; go: AppNavigate }) {
  const reports = useScopedResource(`${identity.key}:workbench-daily-report`, async signal => {
    const org = identity.assignmentOrgUnitId || identity.orgScopes[0]
    if (!org) throw new Error('当前任职未配置日报组织范围')
    const day = await loadCurrentBusinessDay(identity, org, signal)
    return loadMyDailyReports(identity, signal, { businessDate: day.businessDate, positionAssignmentId: identity.businessActorAssignmentId })
  }, [] as DailyReportSummary[], 60_000)
  return <article className="wb-panel wb-report-todo"><header><div><h2>今日待办 · 岗位日报</h2><p>与日工作清单共用一份日报，按当前任职和门店营业日填报</p></div><button className="text-action" onClick={() => go('daily-reports-my')}>历史日报 ›</button></header>
    {reports.loading ? <p className="wb-empty" role="status">正在读取今日日报…</p> : reports.error ? <p className="inline-error" role="alert">日报读取失败<button onClick={() => void reports.reload()}>重试</button></p> : reports.data.length ? reports.data.map(report => <button className="wb-compact-row" key={report.id} onClick={() => go('daily-report-detail', { reportId: report.id })}>
      <span><strong>{report.positionName}日报</strong><small>{report.businessDate} · 截止 {formatLocalDateTime(report.dueAt)}</small></span><b>{report.status === 'DRAFT' ? '待填报 · 立即填写' : '已提交 · 查看日报'} ›</b></button>)
      : <p className="wb-empty">当前营业日暂无已分配日报。<button className="text-action" onClick={() => go('daily-reports-my')}>进入我的日报检查配置 ›</button></p>}
  </article>
}
