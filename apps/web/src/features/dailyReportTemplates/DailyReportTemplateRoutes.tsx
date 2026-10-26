import { useEffect, useMemo, useState } from 'react'
import { ApiError } from '../../api/client'
import { hasPermission, permissions } from '../../app/permissions'
import type { DailyFeatureRouteId, AppNavigate } from '../../app/routeConfig'
import type { RoleContext, RouteParams } from '../../domain'
import { AsyncState } from '../../shared/AsyncState'
import { useScopedResource } from '../../shared/useScopedResource'
import { useStableCommand } from '../../shared/useStableCommand'
import { FeatureHeader, StatusBadge, featureStyles as styles, formatLocalDateTime } from '../shared/FeatureUI'
import { loadDailyReportDeliveryPolicy, loadDailyReportTemplate, loadDailyReportTemplates, saveDailyReportDeliveryPolicy, saveDailyReportTemplateVersion, transitionDailyReportTemplateVersion } from './api'
import type { DailyReportDeliveryPolicy, DailyReportDeliveryPolicyDraft, DailyReportTemplateVersion, TemplateVersionDraft } from './types'
import { DeliveryTimingFields } from './DeliveryTimingFields'
import { publicationStart } from '../workPackages/publicationTiming'

export function DailyReportTemplateRoutes({ view, params, identity, grantedPermissions, go }: {
  view: DailyFeatureRouteId
  params: RouteParams
  identity: RoleContext
  grantedPermissions: string[]
  go: AppNavigate
}) {
  if (view === 'daily-report-templates') return <TemplateList identity={identity} grantedPermissions={grantedPermissions} go={go} />
  if (!params.templateId) return <AsyncState loading={false} error={new Error('路由缺少模板编号')} />
  if (view === 'daily-report-template-version') return <TemplateVersionPage identity={identity} grantedPermissions={grantedPermissions} templateId={params.templateId} versionId={params.versionId} go={go} />
  return <TemplateDetailPage identity={identity} grantedPermissions={grantedPermissions} templateId={params.templateId} go={go} />
}

function TemplateList({ identity, grantedPermissions, go }: { identity: RoleContext; grantedPermissions: string[]; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:daily-report-templates`, (signal) => loadDailyReportTemplates(identity, signal), [])
  const canCreate = hasPermission(grantedPermissions, permissions.dailyReportTemplate.create)
  return <section className={styles.page}>
    <FeatureHeader eyebrow="DAILY REPORT TEMPLATE CENTER" title="日报模板中心" description="总部基础模板与门店补充模块分层治理；已发布版本不可覆盖。" actions={canCreate ? <button className="primary" disabled title="需先选择岗位、模板来源和所属组织">＋ 新建模板</button> : undefined} />
    {canCreate && <div className={styles.locked}>新建入口暂未开放：后端要求明确选择 positionId、templateOrigin 与 ownerOrgUnitId，当前页面尚无安全的岗位和组织选择器，因此不会发送不完整请求。</div>}
    <AsyncState loading={resource.loading} error={resource.error} empty={!resource.data.length} onRetry={resource.reload} emptyTitle="尚无日报模板" emptyDescription="当前授权范围还没有日报模板，空结果不代表服务异常。" />
    {!resource.loading && !resource.error && <div className={styles.cardGrid}>{resource.data.map((template) => <article className={styles.card} key={template.id}><header><div className={styles.meta}><span>{template.code}</span><span>{template.ownership === 'HEADQUARTERS' ? '总部标准' : '门店补充'}</span></div><StatusBadge value={template.lifecycleStatus} /></header><h2>{template.name}</h2><p>{template.positionName || '待配置岗位'} · {template.workPackageName || '待关联工作包版本'}</p><div className={styles.meta}><span>V{template.versionNo ?? '—'}</span><span>{formatLocalDateTime(template.updatedAt)}</span></div><footer><button className="secondary" onClick={() => go('daily-report-template-detail', { templateId: template.id })}>查看模板</button>{template.currentVersionId && <button className="secondary" onClick={() => go('daily-report-template-version', { templateId: template.id, versionId: template.currentVersionId })}>查看版本</button>}</footer></article>)}</div>}
  </section>
}

function TemplateDetailPage({ identity, grantedPermissions, templateId, go }: { identity: RoleContext; grantedPermissions: string[]; templateId: string; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:daily-report-template:${templateId}`, (signal) => loadDailyReportTemplate(identity, templateId, signal), undefined as never)
  const canEdit = hasPermission(grantedPermissions, permissions.dailyReportTemplate.edit)
  return <section className={styles.page}>
    <FeatureHeader eyebrow="TEMPLATE DEFINITION" title={resource.data?.name || '日报模板详情'} description="总部标准项保持锁定；门店只能添加本地补充模块。" actions={<button className="secondary" onClick={() => go('daily-report-templates')}>返回列表</button>} />
    <AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {resource.data && !resource.loading && <div className={styles.split}><section className={styles.section}><header><h2>模板版本</h2><StatusBadge value={resource.data.lifecycleStatus} /></header><div className={styles.stack}>{resource.data.versions.map((version) => <article className={styles.card} key={version.id}><header><strong>V{version.versionNo} · {version.title}</strong><StatusBadge value={version.lifecycleStatus} /></header><p>{version.description || '暂无版本说明'}</p><footer><button className="secondary" onClick={() => go('daily-report-template-version', { templateId, versionId: version.id })}>{canEdit && version.lifecycleStatus === 'DRAFT' ? '编辑草稿' : '查看版本'}</button></footer></article>)}</div></section><aside className={styles.stack}><section className={styles.section}><h2>适用边界</h2><p>{resource.data.ownership === 'HEADQUARTERS' ? '总部基础模板：门店不可修改标准项。' : '门店补充模板：仅在本地授权组织生效。'}</p><div className={styles.meta}><span>{resource.data.positionName || '未绑定岗位'}</span><span>{resource.data.workPackageName || '未绑定工作包'}</span></div></section><div className={styles.desktopHint}>复杂模板编辑以桌面端为主；移动端仅用于查看与审批。</div></aside></div>}
  </section>
}

function TemplateVersionPage({ identity, grantedPermissions, templateId, versionId, go }: { identity: RoleContext; grantedPermissions: string[]; templateId: string; versionId?: string; go: AppNavigate }) {
  const resource = useScopedResource(`${identity.key}:daily-report-template-version:${templateId}:${versionId}`, (signal) => loadDailyReportTemplate(identity, templateId, signal), undefined as never)
  const version = useMemo(() => resource.data?.versions.find((candidate) => candidate.id === versionId), [resource.data, versionId])
  if (!resource.loading && resource.data && !version) return <AsyncState loading={false} error={new Error('模板版本不存在')} />
  return <section className={styles.page}>
    <FeatureHeader eyebrow="TEMPLATE VERSION" title={version ? `${resource.data.name} · V${version.versionNo}` : '模板版本'} description="版本发布后不可原地编辑；审批与发布由服务端校验禁止自审。" actions={<button className="secondary" onClick={() => go('daily-report-template-detail', { templateId })}>返回模板</button>} />
    <AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {version && <>
      <TemplateVersionEditor identity={identity} grantedPermissions={grantedPermissions} templateId={templateId} version={version} onChanged={resource.reload} />
      {version.lifecycleStatus === 'PUBLISHED'
        ? <DeliveryPolicyCard identity={identity} grantedPermissions={grantedPermissions} templateId={templateId} version={version} />
        : <div className={styles.locked}>日报投递策略将在版本发布后开放配置，草稿与审核中版本不会自动生成日报。</div>}
    </>}
  </section>
}

type DeliveryPolicyForm = {
  enabled: boolean
  openLocalTime: string
  dueLocalTime: string
  graceMinutes: string
  preDueReminderMinutes: string
  overdueReminderMinutes: string
  backfillDays: string
}

function deliveryPolicyForm(policy: DailyReportDeliveryPolicy): DeliveryPolicyForm {
  return {
    enabled: policy.enabled,
    openLocalTime: policy.openLocalTime,
    dueLocalTime: policy.dueLocalTime,
    graceMinutes: String(policy.graceMinutes),
    preDueReminderMinutes: policy.preDueReminderMinutes.join(','),
    overdueReminderMinutes: policy.overdueReminderMinutes.join(','),
    backfillDays: String(policy.backfillDays),
  }
}

function parseMinuteList(value: string, minimum: number): number[] {
  return [...new Set(value.split(/[，,\s]+/).map((item) => item.trim()).filter(Boolean).map(Number)
    .filter((item) => Number.isInteger(item) && item >= minimum && item <= 10_080))]
    .sort((left, right) => left - right)
}

function DeliveryPolicyCard({ identity, grantedPermissions, templateId, version }: {
  identity: RoleContext
  grantedPermissions: string[]
  templateId: string
  version: DailyReportTemplateVersion
}) {
  const resource = useScopedResource(
    `${identity.key}:daily-report-delivery-policy:${templateId}:${version.id}`,
    (signal) => loadDailyReportDeliveryPolicy(identity, templateId, version.id, signal),
    undefined as never,
  )
  const canManage = hasPermission(grantedPermissions, permissions.dailyReportTemplate.edit)
    || hasPermission(grantedPermissions, permissions.dailyReportTemplate.publish)
  return <section className={styles.section}>
    <header><div><h2>日报自动投递</h2><p className={styles.meta}>按门店时区和营业日，为适用岗位生成日报并发送中台通知；超时与升级仍由规则引擎处理。</p></div>{resource.data && <StatusBadge value={resource.data.enabled ? '已启用' : '已停用'} />}</header>
    <AsyncState loading={resource.loading} error={resource.error} onRetry={resource.reload} />
    {resource.data && <DeliveryPolicyEditor policy={resource.data} identity={identity} templateId={templateId} versionId={version.id} canManage={canManage} onChanged={resource.reload} />}
  </section>
}

function DeliveryPolicyEditor({ policy, identity, templateId, versionId, canManage, onChanged }: {
  policy: DailyReportDeliveryPolicy
  identity: RoleContext
  templateId: string
  versionId: string
  canManage: boolean
  onChanged: () => Promise<void>
}) {
  const [form, setForm] = useState<DeliveryPolicyForm>(() => deliveryPolicyForm(policy))
  const [message, setMessage] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const command = useStableCommand(`daily-report-delivery-policy-${versionId}`)
  useEffect(() => setForm(deliveryPolicyForm(policy)), [policy])

  const save = async () => {
    setMessage(undefined); setConflict(false)
    const graceMinutes = Number(form.graceMinutes)
    const backfillDays = Number(form.backfillDays)
    const preDueReminderMinutes = parseMinuteList(form.preDueReminderMinutes, 1)
    const overdueReminderMinutes = parseMinuteList(form.overdueReminderMinutes, 0)
    if (!form.openLocalTime || !form.dueLocalTime
      || !Number.isInteger(graceMinutes) || graceMinutes < 0 || graceMinutes > 1440
      || !Number.isInteger(backfillDays) || backfillDays < 0 || backfillDays > 7
      || preDueReminderMinutes.length > 8 || overdueReminderMinutes.length > 8) {
      setMessage('请填写有效的开放时间、截止时间、宽限分钟和补发天数。')
      return
    }
    const draft: DailyReportDeliveryPolicyDraft = {
      enabled: form.enabled,
      openLocalTime: form.openLocalTime,
      dueLocalTime: form.dueLocalTime,
      graceMinutes,
      preDueReminderMinutes,
      overdueReminderMinutes,
      backfillDays,
    }
    try {
      await command.run((key) => saveDailyReportDeliveryPolicy(identity, templateId, versionId, policy, draft, key))
      setMessage('投递策略已保存。')
      await onChanged()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) {
        setConflict(true)
        setMessage('服务端策略已有更新，请刷新后重新应用本次设置。')
      } else setMessage(error instanceof Error ? error.message : '投递策略保存失败')
    }
  }

  return <div className={styles.stack}>
    {!canManage && <div className={styles.locked}>当前账号只有查看权限，不能修改日报投递时间。</div>}
    {policy.deliveryTiming && <DeliveryTimingFields value={policy.deliveryTiming} disabled onChange={() => {}} />}
    <div className={styles.formGrid}>
      <label><span>自动生成与通知</span><select disabled={!canManage} value={form.enabled ? 'true' : 'false'} onChange={(event) => setForm({ ...form, enabled: event.target.value === 'true' })}><option value="true">启用</option><option value="false">停用</option></select></label>
      <label>门店时区<input disabled value={policy.timeZone || '继承门店时区'} /></label>
      {!policy.deliveryTiming && <><label>开放填报时间<input type="time" disabled={!canManage} value={form.openLocalTime} onChange={(event) => setForm({ ...form, openLocalTime: event.target.value })} /></label>
      <label>截止时间<input type="time" disabled={!canManage} value={form.dueLocalTime} onChange={(event) => setForm({ ...form, dueLocalTime: event.target.value })} /></label></>}
      <label>宽限分钟<input type="number" min="0" max="1440" disabled={!canManage} value={form.graceMinutes} onChange={(event) => setForm({ ...form, graceMinutes: event.target.value })} /></label>
      <label>补发营业日数<input type="number" min="0" max="7" disabled={!canManage} value={form.backfillDays} onChange={(event) => setForm({ ...form, backfillDays: event.target.value })} /></label>
      <label>截止前提醒（分钟）<input disabled={!canManage} value={form.preDueReminderMinutes} onChange={(event) => setForm({ ...form, preDueReminderMinutes: event.target.value })} placeholder="例如 30,60" /></label>
      <label>逾期提醒（分钟）<input disabled={!canManage} value={form.overdueReminderMinutes} onChange={(event) => setForm({ ...form, overdueReminderMinutes: event.target.value })} placeholder="例如 0,30" /></label>
    </div>
    <div className={styles.meta}><span>策略版本 {policy.rowVersion}</span><span>最近更新 {formatLocalDateTime(policy.updatedAt)}</span><span>{policy.configured ? '已绑定模板适用范围' : '尚未绑定模板适用范围'}</span></div>
    {message && <div className={conflict ? styles.conflict : 'inline-error'}>{message}</div>}
    {canManage && <footer className={styles.actions}><button className="primary" disabled={command.busy} onClick={() => void save()}>{command.busy ? '保存中…' : '保存投递策略'}</button></footer>}
  </div>
}

function TemplateVersionEditor({ identity, grantedPermissions, templateId, version, onChanged }: { identity: RoleContext; grantedPermissions: string[]; templateId: string; version: DailyReportTemplateVersion; onChanged: () => Promise<void> }) {
  const [draft, setDraft] = useState<TemplateVersionDraft>({ title: version.title, description: version.description, effectiveFrom: version.effectiveFrom, effectiveTo: version.effectiveTo, sections: version.sections, deliveryTiming: version.deliveryTiming })
  const [message, setMessage] = useState<string>()
  const [conflict, setConflict] = useState(false)
  const command = useStableCommand(`template-version-${version.id}`)
  const canEdit = version.lifecycleStatus === 'DRAFT' && hasPermission(grantedPermissions, permissions.dailyReportTemplate.edit)
  const canPublish = version.allowedActions.includes('PUBLISH') && hasPermission(grantedPermissions, permissions.dailyReportTemplate.publish)
  const act = async (action: 'save' | 'submit-review' | 'publish' | 'retire') => {
    setMessage(undefined); setConflict(false)
    try {
      await command.run((key) => action === 'save'
        ? saveDailyReportTemplateVersion(identity, templateId, version, draft, key)
        : transitionDailyReportTemplateVersion(identity, templateId, version, action, key,
          action === 'publish' ? publicationStart(draft.effectiveFrom?.slice(0, 10) ?? '', version.publicationTimeZone ?? '') : undefined))
      await onChanged()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409) { setConflict(true); setMessage('服务器已有更新。本地输入已保留，请查看最新版本后重新应用。') }
      else setMessage(error instanceof Error ? error.message : '操作失败')
    }
  }
  return <div className={styles.split}><section className={styles.section}><header><h2>版本内容</h2><StatusBadge value={version.lifecycleStatus} /></header>{draft.deliveryTiming && <DeliveryTimingFields value={draft.deliveryTiming} disabled={!canEdit} onChange={deliveryTiming => setDraft({ ...draft, deliveryTiming })} />}{!canEdit && <div className={styles.locked}>版本内容只读。已发布内容不会被覆盖；审核发布人仍可选择本次发布日期。</div>}<div className={styles.formGrid}><label>版本标题<input disabled={!canEdit} value={draft.title} onChange={(event) => setDraft({ ...draft, title: event.target.value })} /></label>
    <label>发布生效日期<input type="date" disabled={!canEdit && !canPublish} value={draft.effectiveFrom?.slice(0, 10) ?? ''} onChange={(event) => setDraft({ ...draft, effectiveFrom: event.target.value })} /><small>发布时按{version.publicationTimeZone || '服务端时区'}午夜生效；日期在发布时提交，不随内容草稿保存。</small></label>
    <label className={styles.full}>说明<textarea rows={3} disabled={!canEdit} value={draft.description ?? ''} onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></label></div>{conflict && <div className={styles.conflict}>{message}</div>}{message && !conflict && <div className="inline-error">{message}</div>}<footer className={styles.actions}>{canEdit && <button className="primary" disabled={command.busy} onClick={() => void act('save')}>保存草稿</button>}{version.allowedActions.includes('SUBMIT_REVIEW') && hasPermission(grantedPermissions, permissions.dailyReportTemplate.review) && <button className="secondary" disabled={command.busy} onClick={() => void act('submit-review')}>提交审核</button>}{canPublish && <button className="primary" disabled={command.busy} onClick={() => void act('publish')}>发布版本</button>}{version.allowedActions.includes('RETIRE') && hasPermission(grantedPermissions, permissions.dailyReportTemplate.retire) && <button className="secondary" disabled={command.busy} onClick={() => void act('retire')}>停用版本</button>}</footer></section><aside className={styles.stack}>{draft.sections.map((section) => <section className={styles.reportGroup} key={section.id}><header><strong>{section.name}</strong><span className={styles.meta}>{section.source === 'HEADQUARTERS' ? '总部锁定' : '门店补充'}{section.conditional ? ' · 条件模块' : ''}</span></header>{section.items.map((item) => <div className={styles.reportItem} key={item.id}><strong>{item.label}</strong><div className={styles.meta}><span>{item.dataSource}</span><span>{item.required ? '必填' : '选填'}</span><span>{item.evidenceRequired ? '需要证据' : '无需证据'}</span></div></div>)}</section>)}</aside></div>
}
