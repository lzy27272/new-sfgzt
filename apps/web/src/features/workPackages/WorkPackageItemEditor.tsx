import { useEffect, useMemo, useState, type Dispatch, type DragEvent, type SetStateAction } from 'react'
import {
  createWorkPackageItemDraft,
  duplicateWorkPackageItem,
  moveWorkPackageItem,
  type EvidenceRequirementDraft,
  type ReminderMomentDraft,
  type WorkPackageItemDraft,
  type WorkPackageOption,
} from './configuration'

type Props = {
  items: WorkPackageItemDraft[]
  setItems: Dispatch<SetStateAction<WorkPackageItemDraft[]>>
  forms: WorkPackageOption[]
  standards: WorkPackageOption[]
  issues: string[]
}

const WEEKDAYS = [
  { value: 1, label: '一' }, { value: 2, label: '二' }, { value: 3, label: '三' }, { value: 4, label: '四' },
  { value: 5, label: '五' }, { value: 6, label: '六' }, { value: 7, label: '日' },
]

const key = (prefix: string) => `${prefix}-${crypto.randomUUID()}`

function defaultEvidence(): EvidenceRequirementDraft {
  return {
    key: key('evidence'), checkpointCode: '', label: '', captureSource: 'CAMERA_OR_FILE', minimum: '1', recommendedMaximum: '',
    requiredInstances: '', instanceField: '', instanceLabel: '房号', minimumPerInstance: '', requiredWhenField: '', requiredWhenEquals: '',
    mediaTypes: 'image/jpeg,image/png,application/pdf', extras: {},
  }
}

function defaultReminder(sequence: number): ReminderMomentDraft {
  return { key: key('reminder'), code: `R${String(sequence).padStart(2, '0')}`, kind: 'REMINDER', localTime: '09:00', recipient: 'EXECUTORS', extras: {} }
}

export function WorkPackageItemEditor({ items, setItems, forms, standards, issues }: Props) {
  const [selectedKey, setSelectedKey] = useState(items[0]?.key ?? '')
  const [dragIndex, setDragIndex] = useState<number>()
  const selectedIndex = useMemo(() => Math.max(0, items.findIndex((item) => item.key === selectedKey)), [items, selectedKey])
  const selected = items[selectedIndex]

  useEffect(() => {
    if (!items.length) setSelectedKey('')
    else if (!items.some((item) => item.key === selectedKey)) setSelectedKey(items[0].key)
  }, [items, selectedKey])

  const updateSelected = (patch: Partial<WorkPackageItemDraft>) => {
    setItems((current) => current.map((item) => item.key === selectedKey ? { ...item, ...patch } : item))
  }
  const updateEvidence = (evidenceKey: string, patch: Partial<EvidenceRequirementDraft>) => {
    setItems((current) => current.map((item) => item.key === selectedKey
      ? { ...item, evidenceRequirements: item.evidenceRequirements.map((evidence) => evidence.key === evidenceKey ? { ...evidence, ...patch } : evidence) }
      : item))
  }
  const updateReminder = (reminderKey: string, patch: Partial<ReminderMomentDraft>) => {
    setItems((current) => current.map((item) => item.key === selectedKey
      ? { ...item, reminderMoments: item.reminderMoments.map((moment) => moment.key === reminderKey ? { ...moment, ...patch } : moment) }
      : item))
  }
  const addItem = () => {
    const created = createWorkPackageItemDraft(items.length + 1, forms[0]?.id)
    setItems((current) => [...current, created])
    setSelectedKey(created.key)
  }
  const duplicate = () => {
    const next = duplicateWorkPackageItem(items, selectedIndex)
    setItems(next)
    setSelectedKey(next[selectedIndex + 1]?.key ?? selectedKey)
  }
  const remove = () => {
    if (!selected || !window.confirm(`确认从新版本移除“${selected.name}”？历史版本、已生成工作和证据不会删除。`)) return
    setItems((current) => current.filter((item) => item.key !== selected.key))
  }
  const move = (offset: number) => {
    const target = selectedIndex + offset
    setItems((current) => moveWorkPackageItem(current, selectedIndex, target))
  }
  const dropAt = (event: DragEvent<HTMLButtonElement>, target: number) => {
    event.preventDefault()
    if (dragIndex === undefined) return
    setItems((current) => moveWorkPackageItem(current, dragIndex, target))
    setDragIndex(undefined)
  }

  return <div className="work-package-editor">
    <aside className="work-item-sidebar">
      <div className="work-item-toolbar"><strong>工作清单</strong><span>{items.filter((item) => item.enabled).length}/{items.length} 启用</span></div>
      <div className="work-item-list">
        {items.map((item, index) => <button
          type="button" draggable key={item.key} className={`work-item-card ${item.key === selectedKey ? 'active' : ''} ${item.enabled ? '' : 'disabled'}`}
          onClick={() => setSelectedKey(item.key)} onDragStart={() => setDragIndex(index)} onDragOver={(event) => event.preventDefault()} onDrop={(event) => dropAt(event, index)}
        >
          <i aria-hidden="true">⋮⋮</i><span><b>{index + 1}. {item.name || '未命名工作项'}</b><small>{item.itemCode || '待填写编码'} · {item.workWindowStart || '—'}–{item.dueLocalTime || '—'}</small></span><em>{item.enabled ? '启用' : '停用'}</em>
        </button>)}
      </div>
      <button type="button" className="primary work-item-add" onClick={addItem}>＋ 新增工作项</button>
      <p>拖动工作项调整顺序；发布后修改必须创建新版本。</p>
    </aside>

    <div className="work-item-detail">
      {issues.length > 0 && <div className="work-editor-issues"><strong>发布前需处理</strong>{issues.slice(0, 6).map((issue) => <span key={issue}>{issue}</span>)}</div>}
      {!selected ? <div className="state-card"><strong>暂无工作项</strong><span>请新增至少一个工作项。</span></div> : <>
        <header className="work-item-detail-header"><div><span className="panel-kicker">STANDARD WORK ITEM</span><h3>{selected.name || '未命名工作项'}</h3></div><div className="work-item-actions"><button type="button" className="secondary" disabled={selectedIndex === 0} onClick={() => move(-1)}>上移</button><button type="button" className="secondary" disabled={selectedIndex === items.length - 1} onClick={() => move(1)}>下移</button><button type="button" className="secondary" onClick={duplicate}>复制</button><button type="button" className="secondary danger-action" disabled={items.length === 1} onClick={remove}>移除</button></div></header>

        <section className="work-rule-section"><header><h4>基础设置</h4><label className="switch-line"><input type="checkbox" checked={selected.enabled} onChange={(event) => updateSelected({ enabled: event.target.checked })} />生成新工作</label></header>
          <div className="form-grid work-rule-grid">
            <label>工作项编码<input value={selected.itemCode} onChange={(event) => updateSelected({ itemCode: event.target.value.toUpperCase() })} /></label>
            <label>工作项名称<input value={selected.name} onChange={(event) => updateSelected({ name: event.target.value, reportFactLabel: selected.reportFactLabel === selected.name ? event.target.value : selected.reportFactLabel })} /></label>
            <label>工作类型<select value={selected.itemType} onChange={(event) => updateSelected({ itemType: event.target.value })}><option value="SCHEDULED_RECORD">定时记录</option><option value="EVENT_RECORD">事件记录</option><option value="INSPECTION">巡检</option><option value="METRIC_REVIEW">指标复盘</option><option value="REVIEW_APPROVAL">复核审批</option></select></label>
            <label>执行表单<select value={selected.formVersionId} onChange={(event) => updateSelected({ formVersionId: event.target.value })}><option value="">请选择已发布表单</option>{forms.map((form) => <option key={form.id} value={form.id}>{form.name}</option>)}</select></label>
            <label>关联标准<select value={selected.standardVersionId} onChange={(event) => updateSelected({ standardVersionId: event.target.value })}><option value="">暂不关联</option>{standards.map((standard) => <option key={standard.id} value={standard.id}>{standard.name}</option>)}</select></label>
            <label>验收方式<select value={selected.reviewMode} onChange={(event) => updateSelected({ reviewMode: event.target.value })}><option value="NONE">提交即完成</option><option value="MANUAL">直属上级人工验收</option><option value="STANDARD_EVALUATION">按标准评价验收</option></select></label>
            <label className="full-field">工作说明<textarea rows={2} value={selected.description} onChange={(event) => updateSelected({ description: event.target.value })} /></label>
          </div>
        </section>

        <section className="work-rule-section"><header><h4>时间规则</h4><span>按岗位和门店时区生成</span></header>
          <div className="form-grid work-rule-grid">
            <label>执行周期<select value={selected.periodType} onChange={(event) => updateSelected({ periodType: event.target.value })}><option value="DAY">每日</option><option value="WEEK">每周</option><option value="SHIFT">每班次</option><option value="EVENT">事件触发</option></select></label>
            {selected.periodType === 'SHIFT' ? <>
              <label>可开始基准<select value={selected.shiftAvailableAnchor} onChange={(event) => updateSelected({ shiftAvailableAnchor: event.target.value as WorkPackageItemDraft['shiftAvailableAnchor'] })}><option value="SHIFT_START">班次开始</option><option value="SHIFT_MIDPOINT">班次中点</option><option value="SHIFT_END">班次结束</option></select></label>
              <label>可开始偏移（分钟）<input type="number" min="-2880" max="2880" value={selected.shiftAvailableOffsetMinutes} onChange={(event) => updateSelected({ shiftAvailableOffsetMinutes: event.target.value })} /></label>
              <label>截止基准<select value={selected.shiftDueAnchor} onChange={(event) => updateSelected({ shiftDueAnchor: event.target.value as WorkPackageItemDraft['shiftDueAnchor'] })}><option value="SHIFT_START">班次开始</option><option value="SHIFT_MIDPOINT">班次中点</option><option value="SHIFT_END">班次结束</option></select></label>
              <label>截止偏移（分钟）<input type="number" min="-2880" max="2880" value={selected.shiftDueOffsetMinutes} onChange={(event) => updateSelected({ shiftDueOffsetMinutes: event.target.value })} /></label>
            </> : <>
              <label>开始时间<input type="time" value={selected.workWindowStart} onChange={(event) => updateSelected({ workWindowStart: event.target.value })} /></label>
              <label>结束时间<input type="time" value={selected.workWindowEnd} onChange={(event) => updateSelected({ workWindowEnd: event.target.value })} /></label>
              <label>截止时间<input type="time" value={selected.dueLocalTime} onChange={(event) => updateSelected({ dueLocalTime: event.target.value })} /></label>
            </>}
            <label>逾期宽限（分钟）<input type="number" min="0" max="1440" value={selected.graceMinutes} onChange={(event) => updateSelected({ graceMinutes: event.target.value })} /></label>
            <label>节假日策略<select value={selected.holidayPolicy} onChange={(event) => updateSelected({ holidayPolicy: event.target.value })}><option value="INCLUDE">照常执行</option><option value="SKIP">跳过指定节假日</option><option value="SHIFT_FORWARD" disabled>历史策略：顺延到下一工作日</option><option value="SHIFT_BACKWARD" disabled>历史策略：提前到上一工作日</option></select></label>
            <label className="full-field">节假日日期<input disabled={selected.holidayPolicy === 'INCLUDE'} value={selected.holidayDates} onChange={(event) => updateSelected({ holidayDates: event.target.value })} placeholder="YYYY-MM-DD，多个日期用逗号分隔" /></label>
            <label className="full-field">调休工作日<input disabled={selected.holidayPolicy === 'INCLUDE'} value={selected.workdayOverrides} onChange={(event) => updateSelected({ workdayOverrides: event.target.value })} placeholder="指定日期即使在节假日清单中仍照常生成" /></label>
            <fieldset className="weekday-field full-field"><legend>适用星期</legend>{WEEKDAYS.map((day) => <label key={day.value}><input type="checkbox" checked={selected.weekdays.includes(day.value)} onChange={(event) => updateSelected({ weekdays: event.target.checked ? [...selected.weekdays, day.value].sort() : selected.weekdays.filter((value) => value !== day.value) })} />{day.label}</label>)}</fieldset>
          </div>
        </section>

        <section className="work-rule-section"><header><div><h4>提醒与督办</h4><span>可提醒执行人，也可升级给直属领导</span></div><button type="button" className="secondary" onClick={() => updateSelected({ reminderMoments: [...selected.reminderMoments, defaultReminder(selected.reminderMoments.length + 1)] })}>＋ 提醒时点</button></header>
          {selected.reminderMoments.length === 0 ? <p className="empty-rule">未配置提醒时点。</p> : <div className="rule-row-list">{selected.reminderMoments.map((moment) => <div className="rule-row reminder-row" key={moment.key}>
            <label>编码<input value={moment.code} onChange={(event) => updateReminder(moment.key, { code: event.target.value.toUpperCase() })} /></label>
            <label>类型<select value={moment.kind} onChange={(event) => updateReminder(moment.key, { kind: event.target.value as ReminderMomentDraft['kind'] })}><option value="REMINDER">执行提醒</option><option value="PROGRESS">进度督办</option><option value="OVERDUE">逾期提醒</option><option value="ESCALATION">升级提醒</option></select></label>
            <label>时间<input type="time" value={moment.localTime} onChange={(event) => updateReminder(moment.key, { localTime: event.target.value })} /></label>
            <label>接收人<select value={moment.recipient} onChange={(event) => updateReminder(moment.key, { recipient: event.target.value as ReminderMomentDraft['recipient'] })}><option value="EXECUTORS">执行人</option><option value="DIRECT_MANAGER">直属领导</option></select></label>
            <button type="button" className="text-action danger" onClick={() => updateSelected({ reminderMoments: selected.reminderMoments.filter((entry) => entry.key !== moment.key) })}>删除</button>
          </div>)}</div>}
        </section>

        <section className="work-rule-section"><header><div><h4>证据链规则</h4><span>图片、PDF、Word、Excel 及按房号/区域实例留证</span></div><button type="button" className="secondary" onClick={() => updateSelected({ evidenceRequirements: [...selected.evidenceRequirements, defaultEvidence()] })}>＋ 证据点</button></header>
          <div className="form-grid work-rule-grid evidence-policy-grid">
            <label>附件数量上限<input type="number" min="0" max="200" disabled={selected.attachmentCountUnlimited} value={selected.maxAttachments} onChange={(event) => updateSelected({ maxAttachments: event.target.value })} /></label>
            <label>单文件上限（MB）<input type="number" min="1" max="20" value={selected.maxFileSizeMb} onChange={(event) => updateSelected({ maxFileSizeMb: event.target.value })} /></label>
            <label className="full-field">允许扩展名<input value={selected.allowedExtensions} onChange={(event) => updateSelected({ allowedExtensions: event.target.value })} /></label>
            <label className="checkbox-label"><input type="checkbox" checked={selected.attachmentRequired} onChange={(event) => updateSelected({ attachmentRequired: event.target.checked })} />必须上传附件</label>
            <label className="checkbox-label"><input type="checkbox" checked={selected.attachmentCountUnlimited} onChange={(event) => updateSelected({ attachmentCountUnlimited: event.target.checked, maxAttachments: event.target.checked ? '200' : selected.maxAttachments })} />业务数量不限（安全上限200）</label>
          </div>
          {selected.evidenceRequirements.length === 0 ? <p className="empty-rule">未配置结构化证据点。</p> : <div className="evidence-card-list">{selected.evidenceRequirements.map((evidence) => <article className="evidence-rule-card" key={evidence.key}>
            <header><strong>{evidence.label || '新证据点'}</strong><button type="button" className="text-action danger" onClick={() => updateSelected({ evidenceRequirements: selected.evidenceRequirements.filter((entry) => entry.key !== evidence.key) })}>删除证据点</button></header>
            <div className="form-grid work-rule-grid">
              <label>证据点编码<input value={evidence.checkpointCode} onChange={(event) => updateEvidence(evidence.key, { checkpointCode: event.target.value })} placeholder="例如 lobby" /></label>
              <label>显示名称<input value={evidence.label} onChange={(event) => updateEvidence(evidence.key, { label: event.target.value })} /></label>
              <label>采集方式<select value={evidence.captureSource} onChange={(event) => updateEvidence(evidence.key, { captureSource: event.target.value as EvidenceRequirementDraft['captureSource'] })}><option value="CAMERA">仅现场拍照</option><option value="FILE">仅文件上传</option><option value="CAMERA_OR_FILE">拍照或文件</option></select></label>
              <label>最低数量<input type="number" min="0" max="200" value={evidence.minimum} onChange={(event) => updateEvidence(evidence.key, { minimum: event.target.value })} /></label>
              <label>建议上限<input type="number" min="0" max="200" value={evidence.recommendedMaximum} onChange={(event) => updateEvidence(evidence.key, { recommendedMaximum: event.target.value })} /></label>
              <label>实例数量<input type="number" min="0" max="200" value={evidence.requiredInstances} onChange={(event) => updateEvidence(evidence.key, { requiredInstances: event.target.value })} placeholder="如查房5间" /></label>
              <label>实例字段<input value={evidence.instanceField} onChange={(event) => updateEvidence(evidence.key, { instanceField: event.target.value })} placeholder="如 roomNumbers" /></label>
              <label>实例名称<input value={evidence.instanceLabel} onChange={(event) => updateEvidence(evidence.key, { instanceLabel: event.target.value })} placeholder="如房号" /></label>
              <label>每个实例最低数量<input type="number" min="0" max="200" value={evidence.minimumPerInstance} onChange={(event) => updateEvidence(evidence.key, { minimumPerInstance: event.target.value })} /></label>
              <label>条件字段<input value={evidence.requiredWhenField} onChange={(event) => updateEvidence(evidence.key, { requiredWhenField: event.target.value })} placeholder="选填" /></label>
              <label>条件值<select value={evidence.requiredWhenEquals} onChange={(event) => updateEvidence(evidence.key, { requiredWhenEquals: event.target.value as EvidenceRequirementDraft['requiredWhenEquals'] })}><option value="">始终要求</option><option value="true">为是时要求</option><option value="false">为否时要求</option></select></label>
              <label className="full-field">媒体类型<input value={evidence.mediaTypes} onChange={(event) => updateEvidence(evidence.key, { mediaTypes: event.target.value })} placeholder="image/jpeg,image/png" /></label>
            </div>
          </article>)}</div>}
        </section>

        <section className="work-rule-section"><header><h4>完成要求与日报</h4><span>每项完成后自动投影到岗位日报</span></header>
          <div className="form-grid work-rule-grid">
            <p className="muted">工作结果从业务字段和证据汇总，不再单独要求填写。</p>
            <label className="checkbox-label"><input type="checkbox" checked={selected.exceptionStatementRequired} onChange={(event) => updateSelected({ exceptionStatementRequired: event.target.checked })} />必须填写异常事项</label>
            <label className="checkbox-label"><input type="checkbox" checked={selected.nextActionRequired} onChange={(event) => updateSelected({ nextActionRequired: event.target.checked })} />必须填写下一步行动</label>
            <label className="checkbox-label"><input type="checkbox" checked={selected.waiverAllowed} onChange={(event) => updateSelected({ waiverAllowed: event.target.checked })} />允许主管豁免</label>
            <label className="checkbox-label"><input type="checkbox" checked={selected.dailyReport} onChange={(event) => updateSelected({ dailyReport: event.target.checked })} />自动汇入日报</label>
            <label>日报显示名称<input disabled={!selected.dailyReport} value={selected.reportFactLabel} onChange={(event) => updateSelected({ reportFactLabel: event.target.value })} /></label>
            <label className="checkbox-label"><input type="checkbox" disabled={!selected.dailyReport} checked={selected.reportIncludeEvidence} onChange={(event) => updateSelected({ reportIncludeEvidence: event.target.checked })} />日报展示证据</label>
            <label className="checkbox-label"><input type="checkbox" disabled={!selected.dailyReport} checked={selected.reportIncludeExceptions} onChange={(event) => updateSelected({ reportIncludeExceptions: event.target.checked })} />日报展示异常</label>
          </div>
        </section>
      </>}
    </div>
  </div>
}
