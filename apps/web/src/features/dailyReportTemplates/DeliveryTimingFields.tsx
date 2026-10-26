import type { DailyReportDeliveryTiming } from './types'

export function DeliveryTimingFields({ value, disabled, onChange }: {
  value: DailyReportDeliveryTiming; disabled: boolean; onChange: (next: DailyReportDeliveryTiming) => void
}) {
  return <fieldset><legend>日报填报时段</legend>
    <label>时间规则<select disabled={disabled} value={value.mode} onChange={event => onChange(event.target.value === 'SHIFT_END'
      ? { mode: 'SHIFT_END', openOffsetMinutes: -30, dueOffsetMinutes: 15 }
      : { mode: 'FIXED_LOCAL', openLocalTime: '', dueLocalTime: '' })}><option value="FIXED_LOCAL">门店固定时间</option><option value="SHIFT_END">相对实际交班时间</option></select></label>
    {value.mode === 'SHIFT_END' ? <>
      <label>开放偏移（分钟）<input type="number" min="-1440" max="1440" disabled={disabled} value={value.openOffsetMinutes ?? ''} onChange={event => onChange({ ...value, openOffsetMinutes: event.target.value === '' ? undefined : Number(event.target.value) })} /></label>
      <label>截止偏移（分钟）<input type="number" min="-1440" max="1440" disabled={disabled} value={value.dueOffsetMinutes ?? ''} onChange={event => onChange({ ...value, dueOffsetMinutes: event.target.value === '' ? undefined : Number(event.target.value) })} /></label>
      <p>负数为交班前，正数为交班后。按唯一有效实际班次计算；班次未确定时暂停生成，不使用凌晨固定时间替代。</p>
    </> : <>
      <label>开放填报时间<input type="time" disabled={disabled} value={value.openLocalTime ?? ''} onChange={event => onChange({ ...value, openLocalTime: event.target.value })} /></label>
      <label>截止时间<input type="time" disabled={disabled} value={value.dueLocalTime ?? ''} onChange={event => onChange({ ...value, dueLocalTime: event.target.value })} /></label>
    </>}
    <p>时段修改随新模板版本生效；历史日报保留创建时的时段。</p>
  </fieldset>
}
