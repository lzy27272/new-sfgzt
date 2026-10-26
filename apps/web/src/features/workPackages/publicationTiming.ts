/** Resolve the tenant's local midnight, independent of the administrator's browser timezone. */
export function publicationStart(date: string, timeZone: string): string {
  return localDateTimeInstant(`${date}T00:00`, timeZone)
}

export function localDateTimeInstant(local: string, timeZone: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(local) || !timeZone) throw new Error('请选择日期和时间，并确认服务端时区。')
  const target = Date.parse(`${local}:00Z`)
  if (!Number.isFinite(target) || new Date(target).toISOString().slice(0, 16) !== local) throw new Error('日期或时间无效。')
  const formatter = new Intl.DateTimeFormat('en-CA', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23' })
  const localStamp = (instant: number) => {
    const parts = Object.fromEntries(formatter.formatToParts(new Date(instant)).map(part => [part.type, part.value]))
    return Date.parse(`${parts.year}-${parts.month}-${parts.day}T${parts.hour}:${parts.minute}:${parts.second}Z`)
  }
  let instant = target
  for (let n = 0; n < 4; n++) instant += target - localStamp(instant)
  if (localStamp(instant) !== target) throw new Error('此时刻在门店时区不存在，请核对时间。')
  return new Date(instant).toISOString()
}
