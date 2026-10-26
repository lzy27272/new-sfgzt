import { useEffect, useMemo, useRef, useState } from 'react'
import { apiRequest, asList } from '../../api/client'
import { createManagementTask, dispatchManagementTask, loadEnterpriseTemplates, uploadTaskDispatchAttachment } from '../../api/resources'
import { validateImageEvidence } from '../../app/imageEvidence'
import { materializeSelectedFiles } from '../../app/uploadFiles'
import { matchesTaskAssignee } from '../../app/workEntryPolicy'
import type { EnterpriseTemplate, RoleContext } from '../../domain'
import { hasGrantedPermission, loadOptionalTaskContext } from './taskDispatchAccess'
import {
  MAX_BATCH_TASKS, deliverTask, localDateTimeInput, newTaskDelivery, newTaskRow, taskBatchErrors, taskTitle,
  type TaskAssignment, type TaskBatchRow, type TaskDelivery, type TaskDeliveryStage,
} from './taskBatch'
import './taskBatch.css'

type Props = {
  identity: RoleContext; permissions: string[]; initialHotelId?: string; creationSource?: string
  onClose: () => void; onCreated: () => Promise<void>
}
type Row = Record<string, unknown>
type StandardOption = { versionId: string; name: string; status: string }
type RowStatus = TaskDeliveryStage | 'error'
const statusLabels: Record<RowStatus, string> = {
  creating: '正在创建任务…', uploading: '正在上传参考图片…', dispatching: '正在下达…', dispatched: '已下达', error: '下达未完成',
}
const text = (row: Row, ...keys: string[]) => String(keys.map(key => row[key]).find(value => value != null) ?? '')

export function TaskCreateDialog(props: Props) {
  return <StoreTaskBatchDialog key={props.identity.key} {...props} />
}

function StoreTaskBatchDialog({ identity, permissions, initialHotelId, creationSource = 'MANUAL_TASK_CENTER', onClose, onCreated }: Props) {
  const [assignments, setAssignments] = useState<TaskAssignment[]>([])
  const [standards, setStandards] = useState<StandardOption[]>([])
  const [templates, setTemplates] = useState<EnterpriseTemplate[]>([])
  const [loading, setLoading] = useState(true)
  const [contextError, setContextError] = useState<string>()
  const [error, setError] = useState<string>()
  const [form, setForm] = useState(() => ({
    hotelId: initialHotelId ?? '', reviewerAssignmentId: '', standardVersionId: '', templateId: '',
    priority: 'NORMAL', dueAt: localDateTimeInput(new Date(Date.now() + 24 * 3600_000)),
  }))
  const [rows, setRows] = useState<TaskBatchRow[]>(() => [newTaskRow()])
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({})
  const [rowStatuses, setRowStatuses] = useState<Record<string, RowStatus>>({})
  const [assigneeSearch, setAssigneeSearch] = useState('')
  const [targetsRevision, setTargetsRevision] = useState(0)
  const [validatingFiles, setValidatingFiles] = useState(0)
  const [saving, setSaving] = useState(false)
  const savingRef = useRef(false)
  const deliveries = useRef(new Map<string, TaskDelivery>())
  const canReadTemplates = hasGrantedPermission(permissions, 'template.read')
  const canReadStandards = hasGrantedPermission(permissions, 'standard.read')
  const canDispatch = hasGrantedPermission(permissions, 'task.create') && hasGrantedPermission(permissions, 'task.dispatch')

  useEffect(() => {
    let active = true
    setLoading(true); setContextError(undefined)
    void Promise.all([
      apiRequest<unknown>('/tasks/targets', identity),
      loadOptionalTaskContext(permissions, 'standard.read', () => apiRequest<unknown>('/standards', identity).then(asList<Row>)),
      loadOptionalTaskContext(permissions, 'template.read', () => loadEnterpriseTemplates(identity, 'TASK')),
    ]).then(([targetRaw, standardRaw, templateRows]) => {
      if (!active) return
      const people = asList<Row>(targetRaw).filter(row => text(row, 'assignment_id', 'assignmentId')).map((row): TaskAssignment => ({
        id: text(row, 'assignment_id', 'assignmentId'), employeeName: text(row, 'employee_name', 'employeeName'),
        positionName: text(row, 'position_name', 'positionName'), levelCode: text(row, 'level_code', 'levelCode') || undefined,
        orgUnitId: text(row, 'org_unit_id', 'orgUnitId'), orgName: text(row, 'org_unit_name', 'orgUnitName'),
        hotelId: text(row, 'hotel_id', 'hotelId') || undefined, hotelName: text(row, 'hotel_name', 'hotelName') || undefined,
      }))
      setAssignments(people)
      const available = new Set(people.map(person => person.hotelId ?? person.orgUnitId))
      const firstHotel = initialHotelId && available.has(initialHotelId) ? initialHotelId : people.find(person => person.hotelId)?.hotelId ?? people[0]?.orgUnitId ?? ''
      setForm(current => deliveries.current.size || available.has(current.hotelId) ? current : { ...current, hotelId: firstHotel })
      setStandards(asList<Row>(standardRaw).map(row => ({ versionId: text(row, 'latest_version_id', 'latestVersionId'), name: text(row, 'name'), status: text(row, 'lifecycle_status', 'lifecycleStatus') })).filter(row => row.versionId))
      setTemplates(templateRows.flatMap(row => row.lifecycleStatus === 'PUBLISHED' ? [row] : row.publishedVersionId && row.publishedConfiguration
        ? [{ ...row, latestVersionId: row.publishedVersionId, versionNo: row.publishedVersionNo ?? row.versionNo, lifecycleStatus: 'PUBLISHED', configuration: row.publishedConfiguration }] : []))
    }).catch(reason => { if (active) setContextError(reason instanceof Error ? reason.message : '任务人员读取失败，请重试。') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [identity.key, initialHotelId, canReadStandards, canReadTemplates, targetsRevision])

  const hotels = useMemo(() => Array.from(new Map(assignments.map(person => [
    person.hotelId ?? person.orgUnitId, { id: person.hotelId ?? person.orgUnitId, name: person.hotelName ?? person.orgName },
  ])).values()), [assignments])
  const people = useMemo(() => assignments.filter(person => (person.hotelId ?? person.orgUnitId) === form.hotelId), [assignments, form.hotelId])
  const matchingPeople = people.filter(person => matchesTaskAssignee(person, assigneeSearch))
  const matchingIds = new Set(matchingPeople.map(person => person.id))
  const settingsLocked = saving || deliveries.current.size > 0
  const deliveredCount = rows.filter(row => rowStatuses[row.id] === 'dispatched').length
  const allDelivered = rows.length > 0 && deliveredCount === rows.length
  const selectedTemplate = templates.find(template => template.id === form.templateId)
  const editRow = (id: string, patch: Partial<TaskBatchRow>) => {
    setRows(current => current.map(row => row.id === id ? { ...row, ...patch } : row))
    setRowErrors(current => ({ ...current, [id]: '' }))
    setRowStatuses(current => current[id] === 'error' ? Object.fromEntries(Object.entries(current).filter(([key]) => key !== id)) : current)
    setError(undefined)
  }
  const changeHotel = (hotelId: string) => {
    setForm(current => ({ ...current, hotelId, reviewerAssignmentId: '' }))
    setRows(current => current.map(row => ({ ...row, assigneeAssignmentId: '' })))
    setRowErrors({}); setError(undefined); setAssigneeSearch('')
  }
  const addTask = () => {
    if (rows.length >= MAX_BATCH_TASKS) return
    const row = newTaskRow(String(selectedTemplate?.configuration.description ?? ''))
    setRows(current => [...current, row])
    requestAnimationFrame(() => document.getElementById(`task-person-${row.id}`)?.focus())
  }
  const applyTemplate = (templateId: string) => {
    const template = templates.find(item => item.id === templateId)
    const hours = Number(template?.configuration.dueHours ?? 24)
    setForm(current => ({ ...current, templateId,
      ...(template ? { priority: String(template.configuration.priority ?? 'NORMAL'),
        dueAt: localDateTimeInput(new Date(Date.now() + (Number.isFinite(hours) && hours > 0 ? hours : 24) * 3600_000)) } : {}),
    }))
    if (template?.configuration.description) setRows(current => current.map(row => row.content.trim() ? row : { ...row, content: String(template.configuration.description) }))
  }
  const selectFiles = async (rowId: string, files: File[]) => {
    if (!files.length) return
    setValidatingFiles(count => count + 1)
    setRowErrors(current => ({ ...current, [rowId]: '' }))
    try {
      const validated = await validateImageEvidence(files.slice(0, 10).map(file => ({ file })))
      const materialized = await materializeSelectedFiles(validated.accepted)
      const accepted = materialized.accepted.map(entry => ({ id: crypto.randomUUID(), file: entry.file }))
      const available = Math.max(0, 10 - (rows.find(row => row.id === rowId)?.referenceFiles.length ?? 0))
      setRows(current => current.map(row => row.id === rowId ? { ...row, referenceFiles: [...row.referenceFiles, ...accepted].slice(0, 10) } : row))
      const rejected = [...validated.rejected, ...materialized.rejected]
      const message = rejected.length ? `${rejected[0].entry.file.name}：${rejected[0].reason}`
        : accepted.length > available || files.length > 10 ? '每条任务最多10张参考图片，超出的图片未加入。' : ''
      if (message) setRowErrors(current => ({ ...current, [rowId]: message }))
    } catch (reason) {
      setRowErrors(current => ({ ...current, [rowId]: reason instanceof Error ? reason.message : '图片读取失败，请重新选择。' }))
    } finally { setValidatingFiles(count => count - 1) }
  }

  const save = async () => {
    if (savingRef.current || loading || validatingFiles || !canDispatch) return
    const errors = taskBatchErrors(rows, form.hotelId, assignments, new Set(deliveries.current.keys()), form.reviewerAssignmentId)
    setRowErrors(errors); setError(undefined)
    if (!rows.length || rows.length > MAX_BATCH_TASKS || !form.hotelId) { setError('请选择目标门店并添加任务。'); return }
    if (Object.keys(errors).length) { setError('请先处理标记的问题后再下达。'); return }
    const deadline = new Date(form.dueAt)
    if (!Number.isFinite(deadline.getTime())) { setError('请在统一设置中选择有效的截止时间。'); return }
    savingRef.current = true; setSaving(true)
    let failures = 0
    try {
      for (const row of rows) {
        let delivery = deliveries.current.get(row.id)
        if (delivery?.dispatched) continue
        if (!delivery) {
          delivery = newTaskDelivery(row, {
            orgUnitId: form.hotelId, assigneeAssignmentId: row.assigneeAssignmentId,
            reviewerAssignmentId: form.reviewerAssignmentId || null, creatorAssignmentId: identity.businessActorAssignmentId ?? null,
            dispatchNow: false, standardVersionId: form.standardVersionId || null, workRecordId: null,
            title: taskTitle(row.content, String(selectedTemplate?.configuration.titlePrefix ?? '')), description: row.content.trim(),
            priority: form.priority, dueAt: deadline.toISOString(),
            sourceSnapshot: { source: creationSource, templateId: form.templateId || null,
              templateVersionId: selectedTemplate?.latestVersionId ?? null, dispatchReferenceNote: row.referenceNote.trim() || null,
              taskPolicy: selectedTemplate?.configuration.evidencePolicy ?? {} },
          })
          deliveries.current.set(row.id, delivery)
        }
        const currentDelivery = delivery
        try {
          await deliverTask(currentDelivery, {
            create: (input, key) => createManagementTask(identity, input, key),
            upload: (taskId, file) => uploadTaskDispatchAttachment(identity, taskId, identity.businessActorAssignmentId, row.referenceNote, file),
            dispatch: (taskId, version, key) => dispatchManagementTask(identity, taskId, identity.businessActorAssignmentId, version, key),
          }, stage => setRowStatuses(current => ({ ...current, [row.id]: stage })))
          editRow(row.id, { referenceFiles: [] })
        } catch (reason) {
          failures += 1
          if (currentDelivery.editable) deliveries.current.delete(row.id)
          setRowStatuses(current => ({ ...current, [row.id]: 'error' }))
          const message = reason instanceof Error ? reason.message : '下达失败，请重试。'
          setRowErrors(current => ({ ...current, [row.id]: currentDelivery.editable ? message : `${message} 已保留进度，可直接重试。` }))
        }
      }
      const successes = rows.filter(row => deliveries.current.get(row.id)?.dispatched).length
      if (failures) setError(`已下达 ${successes} 条，还有 ${failures} 条未完成。重试将从未完成的步骤继续。`)
      if (successes) {
        try { await onCreated() }
        catch { setError('下达结果已保留，任务列表刷新失败，请点击按钮重试。'); return }
      }
      if (!failures) onClose()
    } finally { savingRef.current = false; setSaving(false) }
  }

  return <div className="modal-backdrop task-batch-backdrop"><section className="modal configuration-modal pilot6-editor task-batch-modal" role="dialog" aria-modal="true" aria-labelledby="task-batch-title" aria-busy={saving}>
    <header><div><h2 id="task-batch-title">工作下达</h2><p>选好门店，添加任务；每条只需选择执行人员、填写任务内容。</p></div><button type="button" className="close" aria-label="关闭工作下达" disabled={saving || validatingFiles > 0} onClick={onClose}>×</button></header>
    <div className="form-body configuration-form task-batch-body">
      {loading && <p role="status">正在读取可下达任务的门店与人员…</p>}
      {contextError && <p className="inline-error" role="alert">{contextError}<button type="button" className="text-action" onClick={() => setTargetsRevision(value => value + 1)}>重新读取</button></p>}
      <div className="task-batch-store"><label>目标门店<select value={form.hotelId} disabled={settingsLocked || loading} onChange={event => changeHotel(event.target.value)}><option value="">请选择门店</option>{hotels.map(hotel => <option key={hotel.id} value={hotel.id}>{hotel.name}</option>)}</select></label>
        <button type="button" className="secondary" disabled={saving || loading} onClick={() => setTargetsRevision(value => value + 1)}>刷新人员</button><small>当前门店 {people.length} 个可选任职，人员按当前账号的下达权限显示。</small></div>
      <details className="task-batch-settings"><summary>统一设置（选填） · {form.priority === 'NORMAL' ? '普通优先级' : { LOW: '低优先级', HIGH: '高优先级', URGENT: '紧急' }[form.priority]} · 截止 {form.dueAt.replace('T', ' ')} · {form.reviewerAssignmentId ? '指定验收人' : '自动验收人'}</summary>
        <div className="form-grid">{canReadTemplates && <label>任务模板<select value={form.templateId} disabled={settingsLocked} onChange={event => applyTemplate(event.target.value)}><option value="">不使用模板</option>{templates.map(template => <option key={template.id} value={template.id}>{template.name} · V{template.versionNo}</option>)}</select></label>}
          <label>截止时间<input type="datetime-local" value={form.dueAt} disabled={settingsLocked} onChange={event => setForm(current => ({ ...current, dueAt: event.target.value }))} /></label>
          <label>优先级<select value={form.priority} disabled={settingsLocked} onChange={event => setForm(current => ({ ...current, priority: event.target.value }))}><option value="LOW">低</option><option value="NORMAL">普通</option><option value="HIGH">高</option><option value="URGENT">紧急</option></select></label>
          <label>验收负责人（可选）<select value={form.reviewerAssignmentId} disabled={settingsLocked} onChange={event => setForm(current => ({ ...current, reviewerAssignmentId: event.target.value }))}><option value="">自动：发起人 / 直属上级 / 门店管理岗</option>{people.filter(person => person.levelCode?.startsWith('M')).map(person => <option key={person.id} value={person.id}>{person.employeeName} · {person.positionName}</option>)}</select></label>
          {canReadStandards && <label>关联标准<select value={form.standardVersionId} disabled={settingsLocked} onChange={event => setForm(current => ({ ...current, standardVersionId: event.target.value }))}><option value="">暂不关联</option>{standards.filter(standard => standard.status === 'PUBLISHED').map(standard => <option key={standard.versionId} value={standard.versionId}>{standard.name}</option>)}</select></label>}
        </div></details>
      {form.hotelId && !loading && !people.length && <p className="inline-warning task-batch-empty">当前门店暂无可派单人员，请核对员工的任职状态和下达权限。</p>}
      <div className="task-batch-heading"><h3>任务清单 · {rows.length} 条</h3><span>每条独立下达、独立跟进</span></div>
      <label className="task-batch-search">搜索执行人员<input type="search" value={assigneeSearch} disabled={saving} onChange={event => setAssigneeSearch(event.target.value)} placeholder="输入姓名、岗位或部门筛选人员" /></label>
      <div className="task-batch-list">{rows.map((row, index) => {
        const locked = saving || deliveries.current.has(row.id)
        const options = people.filter(person => matchingIds.has(person.id) || person.id === row.assigneeAssignmentId)
        const files = deliveries.current.get(row.id)?.pendingFiles ?? row.referenceFiles
        return <section key={row.id} className={`task-batch-item${rowStatuses[row.id] === 'dispatched' ? ' is-delivered' : rowErrors[row.id] ? ' is-error' : ''}`} aria-label={`任务 ${index + 1}`}>
          <div className="task-batch-item-top"><strong>任务 {index + 1}</strong><button type="button" className="text-action" aria-label={`移除任务 ${index + 1}`} disabled={locked || rows.length === 1 || validatingFiles > 0} onClick={() => setRows(current => current.filter(item => item.id !== row.id))}>移除</button></div>
          <div className="task-batch-item-fields"><label>执行人员 *<select id={`task-person-${row.id}`} aria-label={`任务 ${index + 1} 执行人员`} value={row.assigneeAssignmentId} disabled={locked || loading || !form.hotelId} onChange={event => editRow(row.id, { assigneeAssignmentId: event.target.value })}>
            <option value="">请选择门店人员</option>{options.map(person => <option key={person.id} value={person.id}>{person.employeeName} · {person.positionName} · {person.orgName}</option>)}</select>{assigneeSearch.trim() && !matchingPeople.length && <small>没有匹配人员，可清空搜索重新选择。</small>}</label>
            <label>任务内容 *<textarea aria-label={`任务 ${index + 1} 任务内容`} rows={3} value={row.content} disabled={locked} onChange={event => editRow(row.id, { content: event.target.value })} placeholder="填写要完成的工作及要求" /></label></div>
          <details className="task-batch-references"><summary>参考图片及说明（选填）{files.length ? ` · ${files.length} 张待上传` : ''}</summary><div>
            <label>图片说明（选填）<textarea aria-label={`任务 ${index + 1} 图片说明`} rows={2} maxLength={500} value={row.referenceNote} disabled={locked} onChange={event => editRow(row.id, { referenceNote: event.target.value })} /></label>
            <div className="evidence-uploader task-dispatch-reference"><strong>下达参考图片（选填）</strong><div><label className="secondary task-file-button"><input type="file" aria-label={`任务 ${index + 1} 选择图片`} accept="image/jpeg,image/png" multiple disabled={locked || validatingFiles > 0} onChange={event => { void selectFiles(row.id, Array.from(event.target.files ?? [])); event.currentTarget.value = '' }} />选择图片</label>
              <label className="secondary task-file-button"><input type="file" aria-label={`任务 ${index + 1} 直接拍照`} accept="image/jpeg,image/png" capture="environment" multiple disabled={locked || validatingFiles > 0} onChange={event => { void selectFiles(row.id, Array.from(event.target.files ?? [])); event.currentTarget.value = '' }} />直接拍照</label></div>
              <small>支持 JPG、PNG，每条最多10张，参考图片随对应任务一起下达。</small>
              {!!files.length && <div className="pending-evidence-list">{files.map(file => <button type="button" className="secondary" key={file.id} disabled={locked} onClick={() => editRow(row.id, { referenceFiles: row.referenceFiles.filter(item => item.id !== file.id) })}>{file.file.name} ×</button>)}</div>}
            </div></div></details>
          {rowStatuses[row.id] && <p className="task-batch-row-status" role="status">{statusLabels[rowStatuses[row.id]]}</p>}
          {rowErrors[row.id] && <p className="task-batch-row-error" role="alert">{rowErrors[row.id]}</p>}
        </section>
      })}</div>
      <button type="button" className="secondary task-batch-add" disabled={saving || loading || !form.hotelId || rows.length >= MAX_BATCH_TASKS} onClick={addTask}>＋ 添加任务</button>
      {rows.length >= MAX_BATCH_TASKS && <small>一次最多下达 {MAX_BATCH_TASKS} 条任务。</small>}
      {validatingFiles > 0 && <p role="status">正在读取参考图片…</p>}
      {error && <p className="inline-error" role="alert">{error}</p>}
    </div>
    <footer><span className="task-batch-total">共 <strong>{rows.length}</strong> 条任务{deliveredCount > 0 && ` · 已下达 ${deliveredCount} 条`}</span><button type="button" className="secondary" disabled={saving || validatingFiles > 0} onClick={onClose}>关闭</button>
      <button type="button" className="primary" disabled={saving || loading || !!contextError || validatingFiles > 0 || !canDispatch || (!allDelivered && !people.length)} onClick={() => void save()}>{saving ? '正在下达…' : allDelivered ? '刷新任务列表' : deliveredCount > 0 || Object.values(rowStatuses).includes('error') ? '重试未完成任务' : `创建并下达 ${rows.length} 条任务`}</button></footer>
  </section></div>
}
