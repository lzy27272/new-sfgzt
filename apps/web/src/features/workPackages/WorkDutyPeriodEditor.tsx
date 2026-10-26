import { useEffect, useState } from 'react'
import { apiRequest, asList } from '../../api/client'
import type { RoleContext } from '../../domain'
import { localDateTimeInstant } from './publicationTiming'

type Row = Record<string, unknown>
type Props = {
  identity: RoleContext
  assignments: Array<{ assignmentId?: string; name: string; positionName?: string; orgUnitId?: string }>
  orgs: Array<{ id: string; name: string; unitType: string }>
}

export function WorkDutyPeriodEditor({ identity, assignments, orgs }: Props) {
  const [assignmentId, setAssignmentId] = useState('')
  const [hotelId, setHotelId] = useState('')
  const [date, setDate] = useState('')
  const [start, setStart] = useState('')
  const [end, setEnd] = useState('')
  const [timeZone, setTimeZone] = useState('')
  const [periods, setPeriods] = useState<Row[]>([])
  const [loading, setLoading] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string>()
  const [message, setMessage] = useState<string>()
  const [refresh, setRefresh] = useState(0)
  useEffect(() => {
    setTimeZone(''); setPeriods([]); setError(undefined)
    if (!assignmentId || !hotelId || !date) return
    const abort = new AbortController(); setLoading(true)
    const query = new URLSearchParams({ positionAssignmentId: assignmentId, targetOrgUnitId: hotelId, businessDate: date })
    void Promise.all([apiRequest<Row>(`/business-days/current?orgUnitId=${encodeURIComponent(hotelId)}`, identity, { signal: abort.signal }),
      apiRequest<unknown>(`/work-duty-periods?${query}`, identity, { signal: abort.signal })])
      .then(([day, rows]) => { setTimeZone(String(day.timezone ?? day.timeZone ?? '')); setPeriods(asList<Row>(rows)) })
      .catch(reason => { if (!abort.signal.aborted) setError(reason instanceof Error ? reason.message : '排班读取失败，请重试。') })
      .finally(() => { if (!abort.signal.aborted) setLoading(false) })
    return () => abort.abort()
  }, [identity.key, assignmentId, hotelId, date, refresh])
  const active = periods.filter(period => String(period.status) !== 'CANCELLED' && String(period.period_type ?? period.periodType) === 'SHIFT')
  const save = async () => {
    setError(undefined); setMessage(undefined)
    if (!assignmentId || !hotelId || !date || !start || !end || !timeZone) { setError('请选择员工、门店、营业日和完整起止时间，等待读取门店时区。'); return }
    if (active.length) { setError('该营业日已有实际班次，请先核对已有记录，不能重复创建。'); return }
    setBusy(true)
    try {
      const plannedStartAt = localDateTimeInstant(start, timeZone), plannedEndAt = localDateTimeInstant(end, timeZone)
      if (plannedEndAt <= plannedStartAt) throw new Error('结束时间必须晚于开始时间；夜班请明确选择次日日期。')
      await apiRequest('/work-duty-periods', identity, { method: 'POST', body: JSON.stringify({ positionAssignmentId: assignmentId, targetOrgUnitId: hotelId, businessDate: date, periodType: 'SHIFT', plannedStartAt, plannedEndAt }) })
      setRefresh(value => value + 1)
      setMessage('实际班次已保存。匹配的工作按有效模板生成；日报仍须按配置时段填报。')
    } catch (reason) { setError(reason instanceof Error ? reason.message : '实际班次保存失败，请刷新核对后重试。') }
    finally { setBusy(false) }
  }
  return <details className="allocation-box"><summary>实际班次配置</summary><p>用于工作车准备、夜班收尾和日报交班时间。填写真实在岗时间，跨午夜仍选择原营业日；不会自动延长员工班次。</p>
    <div className="form-grid"><label>员工任职<select value={assignmentId} onChange={event => { setAssignmentId(event.target.value); setHotelId(assignments.find(item => item.assignmentId === event.target.value)?.orgUnitId ?? '') }}><option value="">请选择</option>{assignments.filter(item => item.assignmentId).map(item => <option value={item.assignmentId} key={item.assignmentId}>{item.name} · {item.positionName}</option>)}</select></label>
      <label>班次所属门店<select value={hotelId} onChange={event => setHotelId(event.target.value)}><option value="">请选择</option>{orgs.filter(org => org.unitType === 'HOTEL').map(org => <option value={org.id} key={org.id}>{org.name}</option>)}</select></label>
      <label>营业日<input type="date" value={date} onChange={event => setDate(event.target.value)} /></label><label>门店时区<input readOnly value={timeZone || '选择门店后读取'} /></label>
      <label>实际开始时间<input type="datetime-local" value={start} onChange={event => setStart(event.target.value)} /></label><label>实际交班时间<input type="datetime-local" value={end} onChange={event => setEnd(event.target.value)} /></label>
    </div>
    {loading && <p role="status">正在核对实际班次…</p>}
    {active.map(period => <p key={String(period.id)}>已有班次：{String(period.planned_start_at ?? period.plannedStartAt)} 至 {String(period.planned_end_at ?? period.plannedEndAt)}</p>)}
    {active.length > 1 && <p className="inline-warning">存在多个班次，系统无法确定交班时间。请联系管理员核对排班，不会默认凌晨下班。</p>}
    {error && <p className="inline-error" role="alert">{error}</p>}{message && <p role="status">{message}</p>}
    <div className="button-row"><button type="button" className="secondary" disabled={loading || busy || !assignmentId || !hotelId || !date} onClick={() => setRefresh(value => value + 1)}>刷新班次</button><button type="button" className="primary" disabled={loading || busy || !timeZone || active.length > 0} onClick={() => void save()}>{busy ? '保存中…' : '保存实际班次'}</button></div>
  </details>
}
