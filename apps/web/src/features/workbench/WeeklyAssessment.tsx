import { useState } from 'react'
import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
import type { AppNavigate } from '../../app/routeConfig'
import { useResource } from '../../useResource'

type Employee = { id: string; name: string; position_name: string; department_name: string }
type Assessment = { employee_id: string; score: number; focus: string; row_version: number; employee_name_snapshot: string; position_name_snapshot: string }
type Detail = { expectation: { id: string; hotel_name: string; period_start: string; period_end: string; due_at: string; status: string }; employees: Employee[]; assessments: Assessment[]; canEdit: boolean }
type Reference = { metrics: { expected: number; submitted: number; completed: number; on_time: number; late: number; missed: number }; history: { week_start: string; score: number; focus: string; status: string }[]
  records: { id: string; name: string; status: string; business_date: string }[] }
const empty: Detail = { expectation: { id: '', hotel_name: '', period_start: '', period_end: '', due_at: '', status: '' }, employees: [], assessments: [], canEdit: false }
const emptyReference: Reference = { metrics: { expected: 0, submitted: 0, completed: 0, on_time: 0, late: 0, missed: 0 }, history: [], records: [] }

export function WeeklyAssessment({ identity, id, onBack, go }: { identity: RoleContext; id: string; onBack: () => void; go: AppNavigate }) {
  const data = useResource(identity.key + id, async () => ({ data: await apiRequest<Detail>(`/manager-cycles/${id}/assessment`, identity), source: 'api' as const }), empty)
  const [selectedId, setSelectedId] = useState<string>()
  const [search, setSearch] = useState('')
  const [department, setDepartment] = useState('')
  const [position, setPosition] = useState('')
  const [filter, setFilter] = useState('ALL')
  const [submitting, setSubmitting] = useState(false)
  const [message, setMessage] = useState('')
  const [failure, setFailure] = useState('')
  const [dirty, setDirty] = useState(false)
  const [showEmployees, setShowEmployees] = useState(true)
  // Historical assessments survive an employee leaving; the snapshot is never turned into a new roster entry.
  const employees = [...data.data.employees, ...data.data.assessments.filter(a => !data.data.employees.some(e => e.id === a.employee_id))
    .map(a => ({ id: a.employee_id, name: a.employee_name_snapshot, position_name: a.position_name_snapshot, department_name: '历史评估（不在当前名册）' }))]
  const filtered = employees.filter(e => e.name.includes(search) && (!department || e.department_name === department) && (!position || e.position_name === position)
    && (filter === 'ALL' || (filter === 'DONE') === data.data.assessments.some(a => a.employee_id === e.id)))
  const employee = employees.find(e => e.id === selectedId) || filtered[0]
  const assessment = data.data.assessments.find(a => a.employee_id === employee?.id)
  const leave = (action: () => void) => { if (!dirty || window.confirm('当前评分尚未保存，确认离开？')) { setDirty(false); action() } }
  const submit = async () => {
    if (dirty) { setFailure('请先保存当前员工评估，再汇总并提交。'); return }
    if (!data.data.assessments.length) { setFailure('请至少保存一位员工评估；未评员工不会记为零分。'); return }
    if (!window.confirm(`将提交 ${data.data.assessments.length} 位员工的评估，提交后等待副总复核。确认提交？`)) return
    setSubmitting(true); setFailure(''); setMessage('')
    try {
      await apiRequest(`/manager-cycles/${id}/assessment/submit`, identity, { method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() } })
      await data.reload(); setMessage('已汇总并提交，等待分管副总复核。')
    } catch (e) { setFailure(e instanceof Error ? e.message : '提交失败，请重试') }
    finally { setSubmitting(false) }
  }
  return <section className="mc-assessment">
    <div className="mc-assessment-heading"><div><p>店长工作台 / 周清</p><h2>员工绩效评估</h2><p>{data.data.expectation.hotel_name} · {data.data.expectation.period_start} — {data.data.expectation.period_end}</p>
      {data.data.expectation.due_at && <small>截止 {new Date(data.data.expectation.due_at).toLocaleString('zh-CN', { timeZone: 'Asia/Shanghai' })}</small>}</div>
      <div className="mc-actions"><button className="secondary" onClick={() => leave(onBack)}>返回周清</button>
        {data.data.canEdit && <button className="primary" disabled={submitting || data.loading} onClick={() => void submit()}>{submitting ? '提交中…' : '汇总并提交'}</button>}</div></div>
    {(data.error || failure) && <p className="inline-error" role="alert">{data.error || failure}<button onClick={data.reload}>重新加载</button></p>}
    {message && <p className="mc-success" role="status">{message}</p>}
    {data.loading ? <p className="mc-empty">正在读取门店员工…</p> : <>
      {!data.data.canEdit && !data.error && <p className="mc-notice">此评估已提交或为只读记录。退回补充后，原执行人才可修改并重新提交。</p>}
      <button className="mc-mobile-picker secondary" onClick={() => leave(() => setShowEmployees(!showEmployees))}>选择员工：{employee?.name || '请选择'} · 切换员工</button>
      <div className="mc-assessment-grid"><aside className={'mc-panel mc-people' + (!showEmployees ? ' mobile-hidden' : '')}>
        <h3>选择门店员工</h3><input aria-label="搜索员工姓名" placeholder="搜索员工姓名" value={search} onChange={e => setSearch(e.target.value)} />
        <div className="mc-people-filters"><select aria-label="筛选部门" value={department} onChange={e => setDepartment(e.target.value)}><option value="">全部部门</option>{[...new Set(employees.map(e => e.department_name))].map(value => <option key={value}>{value}</option>)}</select>
          <select aria-label="筛选岗位" value={position} onChange={e => setPosition(e.target.value)}><option value="">全部岗位</option>{[...new Set(employees.map(e => e.position_name))].map(value => <option key={value}>{value}</option>)}</select></div>
        <div className="mc-filter-tabs">{[['ALL','全部', employees.length],['DONE','已评',data.data.assessments.length],['TODO','未评',employees.length-data.data.assessments.length]].map(([key,label,count]) =>
          <button key={key} className={filter === key ? 'active' : ''} onClick={() => setFilter(String(key))}>{label} {count}</button>)}</div>
        {!filtered.length && <p className="mc-empty">没有符合条件的员工</p>}{filtered.map(e => { const score = data.data.assessments.find(a => a.employee_id === e.id)
          return <button key={e.id} className={'mc-person' + (employee?.id === e.id ? ' active' : '')} onClick={() => leave(() => { setSelectedId(e.id); setShowEmployees(false) })}>
            <i>{e.name[0]}</i><span><b>{e.name}</b><small>{e.department_name} · {e.position_name}</small></span><em>{score ? `${score.score}分` : '未评'}</em></button> })}
        <p className="mc-note">同一门店、同一员工、同一周仅一份评估。多岗位不重复计分，未评不是零分。</p>
      </aside>
      {employee && <EmployeeEditor key={employee.id + ':' + (assessment?.row_version ?? 'new')} employee={employee} assessment={assessment} identity={identity} expectationId={id}
        editable={data.data.canEdit && data.data.employees.some(e => e.id === employee.id)} onDirty={setDirty} go={go}
        onSaved={async next => {
          setDirty(false); setFailure(''); await data.reload(); setMessage('员工评估已保存。')
          if (next) { const index=filtered.findIndex(e => e.id===employee.id); const nextEmployee=filtered[index+1] || filtered[0]; if (nextEmployee) setSelectedId(nextEmployee.id) }
        }} />}
      </div>
    </>}
  </section>
}

function EmployeeEditor({ employee, assessment, identity, expectationId, editable, onDirty, onSaved, go }: {
  employee: Employee; assessment?: Assessment; identity: RoleContext; expectationId: string; editable: boolean
  onDirty: (dirty: boolean) => void; onSaved: (next: boolean) => Promise<void>; go: AppNavigate
}) {
  const [score, setScore] = useState(assessment ? String(assessment.score) : '')
  const [focus, setFocus] = useState(assessment?.focus || '')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [history, setHistory] = useState(false)
  const [records, setRecords] = useState(false)
  const [profile, setProfile] = useState(false)
  const reference = useResource(identity.key + expectationId + employee.id, async () => ({ data: await apiRequest<Reference>(`/manager-cycles/${expectationId}/assessment/${employee.id}/reference`, identity), source: 'api' as const }), emptyReference)
  const percent = (n: number) => reference.loading || reference.error ? '—' : reference.data.metrics.expected ? `${Math.round(n / reference.data.metrics.expected * 100)}%` : '无应交工作'
  async function save(next: boolean) {
    if (!score.trim() || !Number.isFinite(Number(score)) || Number(score)<0 || Number(score)>100 || !/^\d+(\.\d{1,2})?$/.test(score)) { setError('请输入0至100分，最多两位小数。'); return }
    setSaving(true); setError('')
    try {
      await apiRequest(`/manager-cycles/${expectationId}/assessment/${employee.id}`, identity, { method: 'PUT', body: JSON.stringify({ score: Number(score), focus, expectedVersion: assessment?.row_version ?? null }) })
      await onSaved(next)
    } catch (e) { setError(e instanceof Error ? e.message : '保存失败，请重试') }
    finally { setSaving(false) }
  }
  return <div className="mc-employee-editor"><article className="mc-panel">
    <header><div className="mc-employee-heading"><i>{employee.name[0]}</i><div><h3>{employee.name}</h3><p>{employee.department_name} · {employee.position_name}</p></div></div>
      <button className="secondary" onClick={() => setProfile(!profile)}>查看工作档案</button></header>
    {profile && <p className="mc-notice">{employee.name} · {employee.position_name}。此处仅展示本门店工作与周评记录；人事资料仍按人事权限管理。</p>}
    <section className="mc-reference"><h3>本周工作参考</h3><div className="mc-reference-grid">
      <div><small>闭环完成率</small><b>{percent(reference.data.metrics.completed)}</b></div><div><small>按时提交率</small><b>{percent(reference.data.metrics.on_time)}</b></div>
      <div><small>补交</small><b>{reference.loading || reference.error ? '—' : `${reference.data.metrics.late}项`}</b></div><div><small>漏交</small><b>{reference.loading || reference.error ? '—' : `${reference.data.metrics.missed}项`}</b></div>
    </div><button className="text-action" onClick={() => setRecords(!records)}>查看本周工作记录 ›</button>
      {reference.error && <p className="inline-error">参考数据读取失败，请重试<button onClick={reference.reload}>重试</button></p>}
      {records && <div className="mc-history">{reference.data.records?.length ? reference.data.records.map(row => <button key={row.id} className="mc-work-link" onClick={() => go('team-work', { expectationId: row.id })}>
        {row.business_date} · {row.name} <span>查看详情 ›</span></button>) : <p>本周暂无工作记录</p>}</div>}
    </section>
    <section className="mc-score-form"><label>评估分数 <span className="mc-required">*</span><div className="mc-score"><input aria-label="评估分数" type="number" min="0" max="100" step="0.01" disabled={!editable || saving} value={score} onChange={e => { setScore(e.target.value); setError(''); onDirty(true) }} /><span>/ 100</span></div></label>
      <small>店长人工评分，范围0—100分。</small><label>需要关注的重点（选填）<textarea maxLength={2000} rows={4} disabled={!editable || saving} value={focus} onChange={e => { setFocus(e.target.value); onDirty(true) }} placeholder="填写需关注的工作表现、辅导重点或成长建议" /></label>
      <p className="mc-notice">人工评分与工作完成率分别记录，不自动折算月度KPI。</p></section>
    <section className="mc-history"><h3>历史周评估 <button className="text-action" onClick={() => setHistory(!history)}>{history ? '收起' : '查看历史 ›'}</button></h3>
      {reference.data.history.slice(0,history ? 160 : 2).map(row => <p key={row.week_start}>{row.week_start} <b>{row.score}分</b>{history && <span> · {row.focus || '未填写关注重点'}</span>}</p>)}
      {!reference.loading && !reference.data.history.length && <small>暂无历史评估</small>}</section>
    {error && <p className="inline-error" role="alert">{error}</p>}
  </article>{editable && <footer className="mc-save-bar"><button className="secondary" disabled={saving} onClick={() => void save(false)}>{saving ? '保存中…' : '保存草稿'}</button>
      <button className="primary" disabled={saving} onClick={() => void save(true)}>保存并评估下一人</button></footer>}</div>
}
