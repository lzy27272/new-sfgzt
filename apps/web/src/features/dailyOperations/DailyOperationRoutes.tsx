import { useState } from 'react'
import { hasPermission, permissions } from '../../app/permissions'
import type { AppNavigate, DailyFeatureRouteId } from '../../app/routeConfig'
import type { RoleContext, RouteParams } from '../../domain'
import { AsyncState, PartialDataNotice } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { AiRecommendationCard, DataModeBadge, FeatureHeader, StatusBadge, featureStyles as styles, formatLocalDateTime } from '../shared/FeatureUI'
import {
  confirmTaskCandidate,
  createOperationExport,
  createTaskCandidate,
  decideAiRecommendation,
  loadDailyOperationOverview,
  loadOperationActionItems,
  loadOperationExports,
  loadOperationIssue,
  loadOperationIssues,
  loadOperationSnapshot,
  loadOperationSnapshots,
  rejectTaskCandidate,
  retryOperationSnapshot,
  retryTaskCandidateSync,
  transitionOperationIssue,
} from './api'
import type { AiRecommendation, OperationIssueDetail, OperationSnapshotSummary, TaskCandidate } from './types'
import { OperatingCostPanel } from './OperatingCostPanel'

export function DailyOperationRoutes({ view, params, identity, grantedPermissions, go }: { view: DailyFeatureRouteId; params: RouteParams; identity: RoleContext; grantedPermissions: string[]; go: AppNavigate }) {
  if (view === 'daily-operations') return <OverviewPage identity={identity} grantedPermissions={grantedPermissions} params={params} go={go} />
  if (view === 'daily-operation-action-items') return <ActionItemsPage identity={identity} params={params} go={go} />
  if (view === 'daily-operation-issues') return <IssueListPage identity={identity} params={params} go={go} />
  if (view === 'daily-operation-issue-detail') return params.issueId ? <IssueDetailPage identity={identity} grantedPermissions={grantedPermissions} issueId={params.issueId} go={go} /> : <AsyncState loading={false} error={new Error('路由缺少异常编号')} />
  if (view === 'daily-operation-snapshots') return <SnapshotListPage identity={identity} grantedPermissions={grantedPermissions} params={params} go={go} />
  if (view === 'daily-operation-snapshot-detail') return params.snapshotId ? <SnapshotDetailPage identity={identity} snapshotId={params.snapshotId} go={go} /> : <AsyncState loading={false} error={new Error('路由缺少快照编号')} />
  return <ExportsPage identity={identity} grantedPermissions={grantedPermissions} params={params} />
}

function businessDateFrom(params: RouteParams) {
  return params.businessDate || new Date().toISOString().slice(0, 10)
}

function OverviewPage({ identity, grantedPermissions, params, go }: { identity: RoleContext; grantedPermissions: string[]; params: RouteParams; go: AppNavigate }) {
  const businessDate = businessDateFrom(params)
  const mode = params.mode === 'SNAPSHOT' ? 'SNAPSHOT' : 'REALTIME'
  const canReadSnapshots = hasPermission(grantedPermissions, permissions.snapshot.read)
  const resource = useScopedResource(`${identity.key}:daily-operations:${businessDate}:${params.orgUnitId ?? ''}:${mode}:${params.snapshotId ?? ''}`, (signal) => loadDailyOperationOverview(identity, signal, { businessDate, orgUnitId: params.orgUnitId, mode, snapshotId: params.snapshotId }), undefined as never, mode === 'REALTIME' ? 30_000 : 0)
  return <section className={styles.page}>
    <FeatureHeader eyebrow="DAILY OPERATIONS" title={resource.data?.orgName ? `${resource.data.orgName} · 日运营中心` : '日运营中心'} description="行动优先；实时数据与不可变日终快照明确分开。" actions={<><DataModeBadge mode={mode} />{(mode === 'SNAPSHOT' || canReadSnapshots) && <button className="secondary" onClick={() => go(mode === 'REALTIME' ? 'daily-operation-snapshots' : 'daily-operations', mode === 'REALTIME' ? { businessDate, orgUnitId: params.orgUnitId } : { businessDate, orgUnitId: params.orgUnitId, mode: 'REALTIME' })}>{mode === 'REALTIME' ? '查看日终快照' : '返回实时数据'}</button>}</>} />
    <div className={styles.toolbar}><label>营业日<input type="date" value={businessDate} onChange={(event) => go('daily-operations', { ...params, businessDate: event.target.value })} /></label><button className="secondary" onClick={() => void resource.reload()}>刷新</button>{resource.lastUpdatedAt && <span className={styles.meta}>更新于 {formatLocalDateTime(resource.lastUpdatedAt)}{resource.stale ? ' · 刷新失败，当前为旧数据' : ''}</span>}</div>
    <AsyncState loading={resource.loading} error={!resource.data ? resource.error : undefined} onRetry={resource.reload} />
    {mode === 'REALTIME' && hasPermission(grantedPermissions, permissions.storeResource.read) && <OperatingCostPanel key={`${identity.key}:${params.orgUnitId ?? ''}`} identity={identity} initialHotelId={params.orgUnitId} />}
    {resource.data && <><PartialDataNotice sources={resource.data.unavailableSources || []} /><section className={styles.metricGrid}><button className={`${styles.metric} metric-action`} onClick={() => go('daily-operation-action-items', { businessDate, orgUnitId: params.orgUnitId })}><span>关键行动</span><strong>{resource.data.actionItemCount}</strong><small>进入行动队列</small></button><button className={`${styles.metric} metric-action`} onClick={() => go('daily-operation-issues', { businessDate, orgUnitId: params.orgUnitId, status: 'OPEN' })}><span>未闭环异常</span><strong>{resource.data.unresolvedIssueCount}</strong><small>{resource.data.overdueCount} 项逾期</small></button><div className={styles.metric}><span>待确认任务候选</span><strong>{resource.data.pendingTaskCandidateCount}</strong><small>确认后才创建正式任务</small></div>{resource.data.metrics.slice(0, 5).map((metric) => <div className={styles.metric} key={metric.code}><span>{metric.label}</span><strong>{metric.available ? `${metric.value ?? '—'}${metric.unit ?? ''}` : '不可用'}</strong><small>{metric.available ? metric.source || '实时业务数据' : '未按零值计算'}</small></div>)}</section><section className={styles.section}><header><h2>重要异常与未闭环事项</h2><button className="link-button" onClick={() => go('daily-operation-issues', { businessDate, orgUnitId: params.orgUnitId })}>查看全部</button></header>{!resource.data.issues.length ? <div className={styles.locked}>当前没有开放异常，这是有效零项结果。</div> : <div className={styles.actionList}>{resource.data.issues.slice(0, 8).map((issue) => <article key={issue.id}><div><StatusBadge value={issue.severity} /> <strong>{issue.title}</strong></div><button className="secondary" onClick={() => go('daily-operation-issue-detail', { issueId: issue.id })}>处理</button><p>{issue.hotelName || resource.data.orgName} · {issue.ownerName || '待指派'} · {issue.overdue ? '已逾期' : `截止 ${formatLocalDateTime(issue.dueAt)}`}</p></article>)}</div>}</section></>}
  </section>
}

function ActionItemsPage({ identity, params, go }: { identity: RoleContext; params: RouteParams; go: AppNavigate }) {
  const businessDate = businessDateFrom(params)
  const resource = useScopedResource(`${identity.key}:operation-actions:${businessDate}:${params.orgUnitId ?? ''}:${params.status ?? ''}`, (signal) => loadOperationActionItems(identity, signal, { businessDate, orgUnitId: params.orgUnitId, status: params.status }), [])
  return <section className={styles.page}><FeatureHeader eyebrow="ACTION QUEUE" title="行动队列" description="待办、提醒升级和结果消息分开；已读不等于已完成。" actions={<button className="secondary" onClick={() => go('daily-operations', { businessDate, orgUnitId: params.orgUnitId })}>返回总览</button>} /><AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} emptyTitle="当前没有待处理行动" /><div className={styles.actionList}>{resource.data.map((item) => <article key={item.id}><div>{item.severity && <StatusBadge value={item.severity} />} <strong>{item.title}</strong></div><button className="primary" onClick={() => item.sourceType === 'ISSUE' ? go('daily-operation-issue-detail', { issueId: item.sourceId }) : undefined}>查看来源</button><p>{item.description || item.actionType} · {item.ownerName || '待指派'} · 截止 {formatLocalDateTime(item.dueAt)}{item.syncStatus ? ` · ${item.syncStatus}` : ''}</p></article>)}</div></section>
}

function IssueListPage({ identity, params, go }: { identity: RoleContext; params: RouteParams; go: AppNavigate }) {
  const businessDate = businessDateFrom(params)
  const severity = params.severity || ''
  const status = params.status || ''
  const resource = useScopedResource(`${identity.key}:operation-issues:${businessDate}:${params.orgUnitId ?? ''}:${severity}:${status}`, (signal) => loadOperationIssues(identity, signal, { businessDate, orgUnitId: params.orgUnitId, severity, status }), [])
  const update = (nextSeverity: string, nextStatus: string) => go('daily-operation-issues', { ...params, businessDate, severity: nextSeverity || undefined, status: nextStatus || undefined })
  return <section className={styles.page}><FeatureHeader eyebrow="ISSUE EVENTS" title="异常事件" description="一般、重要、重大按确定性规则分级；未闭环事项跨营业日延续。" actions={<button className="secondary" onClick={() => go('daily-operations', { businessDate, orgUnitId: params.orgUnitId })}>返回总览</button>} /><div className={styles.toolbar}><label>严重级别<select value={severity} onChange={(event) => update(event.target.value, status)}><option value="">全部</option><option value="GENERAL">一般</option><option value="IMPORTANT">重要</option><option value="MAJOR">重大</option></select></label><label>状态<select value={status} onChange={(event) => update(severity, event.target.value)}><option value="">全部</option><option value="CANDIDATE">候选</option><option value="CONFIRMED">已确认</option><option value="IN_PROGRESS">处理中</option><option value="PENDING_CLOSE">待验收</option><option value="CLOSED">已关闭</option></select></label></div><AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} /><div className={styles.cardGrid}>{resource.data.map((issue) => <article className={styles.card} key={issue.id}><header><StatusBadge value={issue.severity} /><StatusBadge value={issue.lifecycleStatus} /></header><h2>{issue.title}</h2><p>{issue.description || '暂无补充说明'}</p><div className={styles.meta}><span>{issue.issueNo}</span><span>{issue.ownerName || '待指派'}</span><span>{issue.overdue ? '已逾期' : formatLocalDateTime(issue.dueAt)}</span></div><footer><button className="primary" onClick={() => go('daily-operation-issue-detail', { issueId: issue.id })}>查看闭环</button></footer></article>)}</div></section>
}

function IssueDetailPage({ identity, grantedPermissions, issueId, go }: { identity: RoleContext; grantedPermissions: string[]; issueId: string; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:operation-issue:${issueId}`, (signal) => loadOperationIssue(identity, issueId, signal), undefined as never)
  return <section className={styles.page}><FeatureHeader eyebrow="ISSUE CLOSED LOOP" title={resource.data?.title || '异常事件详情'} description="来源、任务、计时、验收和重开记录保持在同一闭环中。" actions={<button className="secondary" onClick={() => go('daily-operation-issues')}>返回异常列表</button>} /><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />{resource.data && <IssueWorkspace issue={resource.data} identity={identity} grantedPermissions={grantedPermissions} onChanged={resource.reload} />}</section>
}

function IssueWorkspace({ issue, identity, grantedPermissions, onChanged }: { issue: OperationIssueDetail; identity: RoleContext; grantedPermissions: string[]; onChanged: () => Promise<void> }) {
  const command = useStableCommand(`issue-${issue.id}`)
  const [note, setNote] = useState('')
  const [message, setMessage] = useState<string>()
  const [candidateDraft, setCandidateDraft] = useState<{ title: string; description: string; priority: string; assigneeAssignmentId: string; reviewerAssignmentId: string }>()
  const act = async (action: 'confirm' | 'start' | 'pending-close' | 'close' | 'reopen') => {
    setMessage(undefined)
    try { await command.run((key) => transitionOperationIssue(identity, issue, action, { reason: note }, key)); await onChanged() }
    catch (error) { setMessage(error instanceof Error ? error.message : '异常状态更新失败') }
  }
  const createCandidate = async () => {
    if (!candidateDraft) return
    setMessage(undefined)
    try { await command.run((key) => createTaskCandidate(identity, { sourceIssueId: issue.id, ...candidateDraft }, key)); setCandidateDraft(undefined); await onChanged() }
    catch (error) { setMessage(error instanceof Error ? error.message : '任务候选创建失败') }
  }
  const actorMissing = !identity.businessActorAssignmentId
  return <div className={styles.split}><main className={styles.stack}><section className={styles.section}><header><h2>{issue.issueNo}</h2><div className={styles.actions}><StatusBadge value={issue.severity} /><StatusBadge value={issue.lifecycleStatus} /></div></header><p>{issue.description}</p><div className={styles.meta}><span>负责人 {issue.ownerName || '待指派'}</span><span>营业日 {issue.businessDate}</span><span>{issue.overdue ? '已逾期' : `截止 ${formatLocalDateTime(issue.dueAt)}`}</span></div>{actorMissing && <div className={styles.locked}>请先在顶部选择当前任职，关键人工命令必须携带 actorAssignmentId。</div>}<label className={styles.formGrid}>操作意见<textarea rows={2} value={note} onChange={(event) => setNote(event.target.value)} /></label><div className={styles.actions}>{issue.allowedActions.includes('CONFIRM') && hasPermission(grantedPermissions, permissions.dailyOperations.confirmIssue) && <button className="primary" disabled={command.busy || actorMissing || !note.trim()} onClick={() => void act('confirm')}>确认异常</button>}{issue.allowedActions.includes('START') && hasPermission(grantedPermissions, permissions.dailyOperations.assignIssue) && <button className="primary" disabled={command.busy || actorMissing || !note.trim()} onClick={() => void act('start')}>开始处理</button>}{issue.allowedActions.includes('PENDING_CLOSE') && hasPermission(grantedPermissions, permissions.dailyOperations.assignIssue) && <button className="secondary" disabled={command.busy || actorMissing || !note.trim()} onClick={() => void act('pending-close')}>提交验收</button>}{issue.allowedActions.includes('CLOSE') && hasPermission(grantedPermissions, permissions.dailyOperations.closeIssue) && <button className="primary" disabled={command.busy || actorMissing || !note.trim()} onClick={() => void act('close')}>验收关闭</button>}{issue.allowedActions.includes('REOPEN') && hasPermission(grantedPermissions, permissions.dailyOperations.reopenIssue) && <button className="secondary" disabled={command.busy || actorMissing || !note.trim()} onClick={() => void act('reopen')}>重新打开</button>}</div>{message && <div className="inline-error">{message}</div>}{Object.values(issue.blockedActionReasons || {}).map((reason) => <div className={styles.locked} key={reason}>{reason}</div>)}</section><section className={styles.section}><header><h2>来源与事实快照</h2><span className={styles.badge}>{issue.sources.length} 项</span></header><div className={styles.stack}>{issue.sources.map((source) => <article className={styles.reportItem} key={source.id}><strong>{source.label}</strong><p>{source.snapshot}</p><div className={styles.meta}><span>{source.sourceType}</span><span>{source.invalidatedAt ? '来源已失效，历史仍保留' : '来源有效'}</span></div></article>)}</div></section><section className={styles.section}><header><h2>任务候选与正式任务</h2><span className={styles.badge}>{issue.taskCandidates.length + issue.linkedTasks.length}</span></header>{candidateDraft && <div className={styles.reportGroup}><div className={styles.formGrid}><label>任务目标<input value={candidateDraft.title} onChange={(event) => setCandidateDraft({ ...candidateDraft, title: event.target.value })} /></label><label>优先级<select value={candidateDraft.priority} onChange={(event) => setCandidateDraft({ ...candidateDraft, priority: event.target.value })}><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label><label>责任任职ID<input value={candidateDraft.assigneeAssignmentId} onChange={(event) => setCandidateDraft({ ...candidateDraft, assigneeAssignmentId: event.target.value })} /></label><label>验收任职ID<input value={candidateDraft.reviewerAssignmentId} onChange={(event) => setCandidateDraft({ ...candidateDraft, reviewerAssignmentId: event.target.value })} /></label><label className={styles.full}>执行要求<textarea rows={3} value={candidateDraft.description} onChange={(event) => setCandidateDraft({ ...candidateDraft, description: event.target.value })} /></label></div><div className={styles.actions}><button className="primary" disabled={command.busy || actorMissing || !candidateDraft.title.trim() || !candidateDraft.description.trim() || !candidateDraft.assigneeAssignmentId || !candidateDraft.reviewerAssignmentId} onClick={() => void createCandidate()}>保存任务候选</button><button className="secondary" onClick={() => setCandidateDraft(undefined)}>取消</button></div><p className={styles.meta}>保存后仍需负责人确认，前端不会直接创建正式任务。</p></div>}{issue.taskCandidates.map((candidate) => <TaskCandidateCard key={candidate.id} candidate={candidate} identity={identity} grantedPermissions={grantedPermissions} onChanged={onChanged} />)}{issue.linkedTasks.map((task) => <article className={styles.card} key={task.id}><header><strong>{task.title}</strong><StatusBadge value={task.status} /></header><p>正式任务 {task.taskNo || task.id}</p></article>)}</section></main><aside className={styles.stack}><section className={styles.section}><h2>状态时间线</h2><div className={styles.timeline}>{issue.timeline.map((item) => <article key={item.id}><strong>{item.eventType} · {item.actorName || '系统'}</strong><small>{formatLocalDateTime(item.occurredAt)} · {item.description}</small></article>)}</div></section>{issue.aiRecommendations.map((recommendation) => <IssueAiCard key={recommendation.id} recommendation={recommendation} identity={identity} grantedPermissions={grantedPermissions} onDraft={() => setCandidateDraft({ title: issue.title, description: recommendation.recommendation, priority: issue.severity === 'MAJOR' ? 'URGENT' : issue.severity === 'IMPORTANT' ? 'HIGH' : 'NORMAL', assigneeAssignmentId: issue.ownerAssignmentId || '', reviewerAssignmentId: issue.reviewerAssignmentId || '' })} />)}</aside></div>
}

function TaskCandidateCard({ candidate, identity, grantedPermissions, onChanged }: { candidate: TaskCandidate; identity: RoleContext; grantedPermissions: string[]; onChanged: () => Promise<void> }) {
  const command = useStableCommand(`task-candidate-${candidate.id}`)
  const [reason, setReason] = useState('')
  const [message, setMessage] = useState<string>()
  const act = async (action: 'confirm' | 'reject' | 'sync') => {
    setMessage(undefined)
    try {
      await command.run((key) => action === 'confirm' ? confirmTaskCandidate(identity, candidate, key) : action === 'reject' ? rejectTaskCandidate(identity, candidate, reason, key) : retryTaskCandidateSync(identity, candidate, key))
      await onChanged()
    } catch (error) { setMessage(error instanceof Error ? error.message : '任务候选操作失败；可使用同一幂等键安全重试。') }
  }
  const actorMissing = !identity.businessActorAssignmentId
  return <article className={styles.card}><header><strong>{candidate.title}</strong><StatusBadge value={candidate.status} /></header><p>{candidate.description}</p><div className={styles.meta}><span>{candidate.priority}</span><span>{candidate.ownerName || '待指派'}</span><span>{candidate.aiSuggested ? 'AI建议转草稿' : '人工创建'}</span>{candidate.syncStatus && <span>同步 {candidate.syncStatus}</span>}</div>{candidate.status === 'PENDING_CONFIRMATION' && <label className={styles.formGrid}>驳回原因<input value={reason} onChange={(event) => setReason(event.target.value)} /></label>}<footer>{candidate.allowedActions.includes('CONFIRM') && hasPermission(grantedPermissions, permissions.taskCandidate.confirm) && <button className="primary" disabled={command.busy || actorMissing} onClick={() => void act('confirm')}>{command.busy ? '处理中…' : '确认生成正式任务'}</button>}{candidate.allowedActions.includes('REJECT') && hasPermission(grantedPermissions, permissions.taskCandidate.reject) && <button className="danger-button" disabled={command.busy || actorMissing || !reason.trim()} onClick={() => void act('reject')}>驳回</button>}{candidate.allowedActions.includes('RETRY_SYNC') && hasPermission(grantedPermissions, permissions.taskCandidate.retry) && <button className="secondary" disabled={command.busy || actorMissing} onClick={() => void act('sync')}>重试同步</button>}</footer>{actorMissing && <div className={styles.locked}>请先选择当前任职后再处理候选任务。</div>}{candidate.status === 'PENDING_SYNC' && <div className={styles.locked}>已确认，正在异步创建正式任务。失败不会退回未确认状态。</div>}{message && <div className="inline-error">{message}</div>}</article>
}

function IssueAiCard({ recommendation, identity, grantedPermissions, onDraft }: { recommendation: AiRecommendation; identity: RoleContext; grantedPermissions: string[]; onDraft: () => void }) {
  const command = useStableCommand(`ai-decision-${recommendation.id}`)
  const [message, setMessage] = useState<string>()
  const decide = async (decision: 'ACCEPTED' | 'REJECTED' | 'REPORTED_INCORRECT') => {
    setMessage(undefined)
    try {
      await command.run((key) => decideAiRecommendation(identity, recommendation.id, decision, '', key))
      if (decision === 'ACCEPTED') onDraft()
      setMessage(decision === 'ACCEPTED' ? '已采纳为可编辑草稿，仍需人工确认后才能生成任务' : '反馈已记录')
    }
    catch (error) { setMessage(error instanceof Error ? error.message : '反馈失败') }
  }
  const actorMissing = !identity.businessActorAssignmentId
  const canAdopt = hasPermission(grantedPermissions, permissions.ai.adopt)
  const canFeedback = hasPermission(grantedPermissions, permissions.ai.feedback)
  return <AiRecommendationCard facts={recommendation.facts} analysis={recommendation.analysis} recommendation={recommendation.recommendation} sources={recommendation.sourceLabels} actions={<>{canAdopt && <button className="secondary" disabled={command.busy || actorMissing} onClick={() => void decide('ACCEPTED')}>采纳为可编辑草稿</button>}{canFeedback && <button className="link-button" disabled={command.busy || actorMissing} onClick={() => void decide('REJECTED')}>不采纳</button>}{canFeedback && <button className="link-button" disabled={command.busy || actorMissing} onClick={() => void decide('REPORTED_INCORRECT')}>报告有误</button>}{message && <span className={styles.meta}>{message}</span>}</>} />
}

function SnapshotListPage({ identity, grantedPermissions, params, go }: { identity: RoleContext; grantedPermissions: string[]; params: RouteParams; go: AppNavigate }) {
  const businessDate = businessDateFrom(params)
  const resource = useScopedResource(`${identity.key}:operation-snapshots:${businessDate}:${params.orgUnitId ?? ''}`, (signal) => loadOperationSnapshots(identity, signal, { businessDate, orgUnitId: params.orgUnitId }), [])
  return <section className={styles.page}><FeatureHeader eyebrow="IMMUTABLE DAILY SNAPSHOTS" title="日运营快照" description="历史快照不覆盖；修正生成新版本并保留原因。" actions={<button className="secondary" onClick={() => go('daily-operations', { businessDate, orgUnitId: params.orgUnitId })}>返回实时总览</button>} /><div className={styles.toolbar}><label>营业日<input type="date" value={businessDate} onChange={(event) => go('daily-operation-snapshots', { ...params, businessDate: event.target.value })} /></label></div><AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} /><div className={styles.cardGrid}>{resource.data.map((snapshot) => <SnapshotCard key={snapshot.id} snapshot={snapshot} identity={identity} grantedPermissions={grantedPermissions} go={go} onChanged={resource.reload} />)}</div></section>
}

function SnapshotCard({ snapshot, identity, grantedPermissions, go, onChanged }: { snapshot: OperationSnapshotSummary; identity: RoleContext; grantedPermissions: string[]; go: AppNavigate; onChanged: () => Promise<void> }) {
  const command = useStableCommand(`snapshot-${snapshot.id}`)
  return <article className={styles.card}><header><strong>{snapshot.orgName} · V{snapshot.versionNo}</strong><StatusBadge value={snapshot.status} /></header><p>{snapshot.businessDate} · 完整度 {snapshot.completenessPercent ?? '—'}%</p><div className={styles.meta}><span>{formatLocalDateTime(snapshot.generatedAt)}</span><span>{snapshot.correctionReason || '原始快照'}</span></div><footer><button className="secondary" onClick={() => go('daily-operation-snapshot-detail', { snapshotId: snapshot.id })}>查看快照</button>{snapshot.status === 'FAILED' && hasPermission(grantedPermissions, permissions.dailyOperations.retrySnapshot) && <button className="primary" disabled={command.busy} onClick={() => void command.run((key) => retryOperationSnapshot(identity, snapshot, key)).then(() => onChanged())}>重试生成</button>}</footer></article>
}

function SnapshotDetailPage({ identity, snapshotId, go }: { identity: RoleContext; snapshotId: string; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:operation-snapshot:${snapshotId}`, (signal) => loadOperationSnapshot(identity, snapshotId, signal), undefined as never)
  return <section className={styles.page}><FeatureHeader eyebrow="END-OF-DAY SNAPSHOT" title={resource.data ? `${resource.data.orgName} · ${resource.data.businessDate}` : '日终快照'} description="该页面只展示不可变快照，不与实时数据混合。" actions={<><DataModeBadge mode="SNAPSHOT" /><button className="secondary" onClick={() => go('daily-operation-snapshots')}>返回快照列表</button></>} /><AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />{resource.data && <><PartialDataNotice sources={resource.data.overview.unavailableSources || []} /><section className={styles.metricGrid}>{resource.data.overview.metrics.map((metric) => <div className={styles.metric} key={metric.code}><span>{metric.label}</span><strong>{metric.available ? `${metric.value ?? '—'}${metric.unit ?? ''}` : '不可用'}</strong><small>{metric.available ? metric.source : '快照生成时来源不可用'}</small></div>)}</section></>}</section>
}

function ExportsPage({ identity, grantedPermissions, params }: { identity: RoleContext; grantedPermissions: string[]; params: RouteParams }) {
  const resource = useScopedResource(`${identity.key}:operation-exports`, (signal) => loadOperationExports(identity, signal), [])
  const command = useStableCommand('operation-export')
  const [type, setType] = useState('EXCEL_DETAIL')
  const [includeSensitive, setIncludeSensitive] = useState(false)
  const [message, setMessage] = useState<string>()
  const create = async () => {
    try { await command.run((key) => createOperationExport(identity, { exportType: type, businessDate: businessDateFrom(params), orgUnitId: params.orgUnitId, includeSensitive }, key)); await resource.reload() }
    catch (error) { setMessage(error instanceof Error ? error.message : '导出创建失败') }
  }
  return <section className={styles.page}><FeatureHeader eyebrow="AUDITED EXPORTS" title="导出任务" description="明细异步生成并记录下载审计；敏感字段需要独立权限。" /><section className={styles.toolbar}><label>格式<select value={type} onChange={(event) => setType(event.target.value)}><option value="EXCEL_DETAIL">Excel明细</option><option value="CSV_DETAIL">CSV明细</option><option value="PDF_SUMMARY">PDF摘要</option><option value="EVIDENCE_LIST">证据清单</option></select></label><label><span>敏感字段</span><input type="checkbox" checked={includeSensitive} disabled={!hasPermission(grantedPermissions, permissions.export.sensitive)} onChange={(event) => setIncludeSensitive(event.target.checked)} /></label><button className="primary" disabled={command.busy || !identity.businessActorAssignmentId || !hasPermission(grantedPermissions, permissions.export.create)} onClick={() => void create()}>创建导出任务</button></section>{!identity.businessActorAssignmentId && <div className={styles.locked}>请先在顶部选择当前任职；导出审计必须记录 actorAssignmentId。</div>}{message && <div className="inline-error">{message}</div>}<AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} /><div className={styles.cardGrid}>{resource.data.map((item) => <article className={styles.card} key={item.id}><header><strong>{item.exportType}</strong><StatusBadge value={item.status} /></header><p>{item.orgName} · {item.businessDate}</p><div className={styles.meta}><span>{formatLocalDateTime(item.createdAt)}</span><span>{item.sensitiveIncluded ? '含敏感字段' : '普通导出'}</span></div><footer>{item.status === 'SUCCEEDED' && item.downloadUrl && hasPermission(grantedPermissions, permissions.export.download) && <a className="secondary" href={item.downloadUrl}>下载</a>}</footer></article>)}</div></section>
}
