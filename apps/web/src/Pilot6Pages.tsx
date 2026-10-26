import { useEffect, useState } from 'react'
import { asList } from './api/client'
import {
  createEnterpriseTemplate,
  loadEnterpriseTemplates,
  publishEnterpriseTemplate,
  saveEnterpriseTemplateVersion,
} from './api/resources'
import { WorkPackageCenter } from './ConfigurationPages'
import type { EnterpriseTemplate, EnterpriseTemplateType, RoleContext } from './domain'
import { product } from './product'

type Row = Record<string, unknown>

function Modal({ title, children, onClose, footer }: { title: string; children: React.ReactNode; onClose: () => void; footer: React.ReactNode }) {
  return <div className="modal-backdrop"><section className="modal configuration-modal pilot6-editor" role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">{product.version}</span><h2>{title}</h2></div><button className="close" onClick={onClose}>×</button></header>
    <div className="form-body configuration-form">{children}</div><footer>{footer}</footer>
  </section></div>
}

function templateDefaults(type: EnterpriseTemplateType): Record<string, unknown> {
  if (type === 'HOTEL_DASHBOARD') return {
    sections: ['OPERATING_METRICS', 'RISKS', 'INCOMPLETE_TASKS', 'WORK_COMPLETION'],
    metricCodes: ['REVENUE', 'OCCUPANCY_RATE', 'ADR', 'REVPAR', 'OTA_RATING', 'COST'],
    riskLimit: 10, taskLimit: 20,
  }
  return {
    titlePrefix: '', description: '请按要求完成任务并提交结果说明。', priority: 'NORMAL', dueHours: 24,
    evidencePolicy: { narrativeRequired: true, attachmentRequired: false, maxAttachments: 10, allowedExtensions: ['jpg', 'jpeg', 'png', 'pdf', 'docx', 'xlsx'] },
  }
}

function TemplateEditor({ initial, type, onClose, onSaved, identity }: {
  initial?: EnterpriseTemplate; type: EnterpriseTemplateType; onClose: () => void; onSaved: () => Promise<void>; identity: RoleContext
}) {
  const starting = initial?.configuration ?? templateDefaults(type)
  const evidence = starting.evidencePolicy && typeof starting.evidencePolicy === 'object' ? starting.evidencePolicy as Row : {}
  const [code, setCode] = useState(initial?.code ?? '')
  const [name, setName] = useState(initial?.name ?? '')
  const [description, setDescription] = useState(initial?.description ?? '')
  const [titlePrefix, setTitlePrefix] = useState(String(starting.titlePrefix ?? ''))
  const [taskDescription, setTaskDescription] = useState(String(starting.description ?? ''))
  const [priority, setPriority] = useState(String(starting.priority ?? 'NORMAL'))
  const [dueHours, setDueHours] = useState(String(starting.dueHours ?? 24))
  const [narrativeRequired, setNarrativeRequired] = useState(evidence.narrativeRequired !== false)
  const [attachmentRequired, setAttachmentRequired] = useState(evidence.attachmentRequired === true)
  const [maxAttachments, setMaxAttachments] = useState(String(evidence.maxAttachments ?? 10))
  const [extensions, setExtensions] = useState(asList<string>(evidence.allowedExtensions).join(',') || 'jpg,jpeg,png,pdf,docx,xlsx')
  const [sections, setSections] = useState(asList<string>(starting.sections).join(',') || 'OPERATING_METRICS,RISKS,INCOMPLETE_TASKS,WORK_COMPLETION')
  const [metrics, setMetrics] = useState(asList<string>(starting.metricCodes).join(',') || 'REVENUE,OCCUPANCY_RATE,ADR,REVPAR,OTA_RATING,COST')
  const [riskLimit, setRiskLimit] = useState(String(starting.riskLimit ?? 10))
  const [taskLimit, setTaskLimit] = useState(String(starting.taskLimit ?? 20))
  const [busy, setBusy] = useState<'save' | 'publish'>()
  const [error, setError] = useState<string>()
  const configuration = (): Record<string, unknown> => type === 'TASK' ? {
    titlePrefix, description: taskDescription, priority, dueHours: Number(dueHours),
    evidencePolicy: {
      narrativeRequired, attachmentRequired, maxAttachments: Number(maxAttachments),
      allowedExtensions: extensions.split(',').map((item) => item.trim().toLowerCase()).filter(Boolean),
    },
  } : {
    sections: sections.split(',').map((item) => item.trim().toUpperCase()).filter(Boolean),
    metricCodes: metrics.split(',').map((item) => item.trim().toUpperCase()).filter(Boolean),
    riskLimit: Number(riskLimit), taskLimit: Number(taskLimit),
  }
  const save = async () => {
    setBusy('save'); setError(undefined)
    try {
      if (!name.trim() || !code.trim()) throw new Error('模板编码和名称不能为空。')
      if (initial) await saveEnterpriseTemplateVersion(identity, initial, configuration())
      else await createEnterpriseTemplate(identity, { templateType: type, code, name, description: description || undefined, configuration: configuration() })
      await onSaved(); onClose()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '模板保存失败。') }
    finally { setBusy(undefined) }
  }
  const publish = async () => {
    if (!initial?.latestVersionId || initial.lifecycleStatus !== 'DRAFT') return
    setBusy('publish'); setError(undefined)
    try { await publishEnterpriseTemplate(identity, initial.id, initial.latestVersionId); await onSaved(); onClose() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '模板发布失败。') }
    finally { setBusy(undefined) }
  }
  return <Modal title={`${initial ? '修改' : '创建'}${type === 'TASK' ? '任务' : '门店工作台'}模板`} onClose={onClose} footer={<><button className="secondary" onClick={onClose}>取消</button>{initial?.lifecycleStatus === 'DRAFT' && <button className="secondary" disabled={!!busy} onClick={() => void publish()}>{busy === 'publish' ? '发布中…' : '发布草稿'}</button>}<button className="primary" disabled={!!busy} onClick={() => void save()}>{busy === 'save' ? '保存中…' : initial?.lifecycleStatus === 'PUBLISHED' ? '保存为新版本' : '保存草稿'}</button></>}>
    {initial?.lifecycleStatus === 'PUBLISHED' && <div className="inline-warning">已发布版本不可覆盖，本次修改将创建新的草稿版本，历史任务与工作台快照不受影响。</div>}
    <div className="form-grid"><label>模板编码<input value={code} disabled={!!initial} onChange={(event) => setCode(event.target.value.toUpperCase())} placeholder={type === 'TASK' ? 'TASK-DAILY-CHECK' : 'DASHBOARD-HOTEL-OPS'} /></label><label>模板名称<input value={name} disabled={!!initial} onChange={(event) => setName(event.target.value)} /></label><label className="full-field">模板说明<textarea rows={2} value={description} disabled={!!initial} onChange={(event) => setDescription(event.target.value)} /></label></div>
    {type === 'TASK' ? <div className="form-grid"><label>标题前缀<input value={titlePrefix} onChange={(event) => setTitlePrefix(event.target.value)} /></label><label>优先级<select value={priority} onChange={(event) => setPriority(event.target.value)}><option value="LOW">低</option><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label><label>默认时限（小时）<input type="number" min="1" value={dueHours} onChange={(event) => setDueHours(event.target.value)} /></label><label>最多附件数<input type="number" min="0" max="10" value={maxAttachments} onChange={(event) => setMaxAttachments(event.target.value)} /></label><label className="full-field">默认执行要求<textarea rows={3} value={taskDescription} onChange={(event) => setTaskDescription(event.target.value)} /></label><label className="full-field">允许附件扩展名（逗号分隔）<input value={extensions} onChange={(event) => setExtensions(event.target.value)} /></label><label className="checkbox-label"><input type="checkbox" checked={narrativeRequired} onChange={(event) => setNarrativeRequired(event.target.checked)} />必须填写执行结果</label><label className="checkbox-label"><input type="checkbox" checked={attachmentRequired} onChange={(event) => setAttachmentRequired(event.target.checked)} />必须上传执行证据</label></div> : <div className="form-grid"><label className="full-field">工作台板块编码（逗号分隔）<input value={sections} onChange={(event) => setSections(event.target.value)} /></label><label className="full-field">经营指标编码（逗号分隔）<input value={metrics} onChange={(event) => setMetrics(event.target.value)} /></label><label>风险事项显示数<input type="number" min="1" max="100" value={riskLimit} onChange={(event) => setRiskLimit(event.target.value)} /></label><label>未完成任务显示数<input type="number" min="1" max="100" value={taskLimit} onChange={(event) => setTaskLimit(event.target.value)} /></label></div>}
    {error && <div className="inline-error">{error}</div>}
  </Modal>
}

export function EnterpriseTemplateCenter({ identity, permissions }: { identity: RoleContext; permissions: string[] }) {
  const [tab, setTab] = useState<'STANDARD_WORK' | EnterpriseTemplateType>('STANDARD_WORK')
  const [templates, setTemplates] = useState<EnterpriseTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string>()
  const [editing, setEditing] = useState<EnterpriseTemplate | 'NEW'>()
  const canManage = permissions.includes('template.manage') || permissions.includes('*')
  const reload = async () => {
    if (tab === 'STANDARD_WORK') return
    setLoading(true); setError(undefined)
    try { setTemplates(await loadEnterpriseTemplates(identity, tab)) }
    catch (reason) { setError(reason instanceof Error ? reason.message : '企业模板加载失败。') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key, tab])
  if (tab === 'STANDARD_WORK') return <section><div className="config-tabs template-tabs"><button className="active">岗位标准工作</button><button onClick={() => setTab('TASK')}>任务模板</button><button onClick={() => setTab('HOTEL_DASHBOARD')}>门店工作台模板</button></div><WorkPackageCenter identity={identity} permissions={permissions} /></section>
  return <section className="page-section configuration-page"><header className="page-title"><div><span className="eyebrow">CEO TEMPLATE GOVERNANCE</span><h1>集团模板配置</h1><p>CEO 统一配置集团模板；发布采用新版本，不覆盖已经执行的工作、任务和工作台快照。</p></div><div className="page-actions"><span className="source-flag api">实时 PostgreSQL</span>{canManage && <button className="primary" onClick={() => setEditing('NEW')}>＋ 新建模板</button>}</div></header>
    <div className="config-tabs template-tabs"><button onClick={() => setTab('STANDARD_WORK')}>岗位标准工作</button><button className={tab === 'TASK' ? 'active' : ''} onClick={() => setTab('TASK')}>任务模板</button><button className={tab === 'HOTEL_DASHBOARD' ? 'active' : ''} onClick={() => setTab('HOTEL_DASHBOARD')}>门店工作台模板</button></div>
    {error && <div className="inline-error page-error">{error}</div>}
    {loading ? <div className="state-card"><div className="spinner" /><strong>正在读取模板版本</strong></div> : !templates.length ? <div className="state-card"><b>◇</b><strong>当前类型暂无模板</strong><span>点击右上角创建集团模板草稿。</span></div> : <div className="card-grid">{templates.map((item) => <article className="package-card" key={item.id}><div className="card-top"><span>{item.code}</span><span className={`status-pill ${item.lifecycleStatus.toLowerCase()}`}>{item.lifecycleStatus === 'PUBLISHED' ? '已发布' : '草稿'}</span></div><h2>{item.name}</h2><p>{item.description || '集团统一模板'} · V{item.versionNo}</p><button className="secondary" onClick={() => setEditing(item)}>{canManage ? '配置 / 新版本' : '查看模板'}</button></article>)}</div>}
    {editing && canManage && <TemplateEditor key={editing === 'NEW' ? `new:${tab}` : `${editing.id}:${editing.latestVersionId}`} identity={identity} type={tab} initial={editing === 'NEW' ? undefined : editing} onClose={() => setEditing(undefined)} onSaved={reload} />}
  </section>
}

export { TaskCreateDialog } from './features/tasks/TaskCreateDialog'
