export type ServiceCase = { id: string; roomNumber: string; serviceContent: string; photoIds: string[] }
export type LostPropertyEntry = {
  id: string; itemName: string; quantity: number | null; roomNumber: string; foundLocation: string; foundAt: string;
  recipientAssignmentId: string; recipientLabel?: string; notes: string; photoIds: string[]
}
export type HousekeepingActivities = {
  schemaVersion: 1; serviceCases: ServiceCase[]; lostPropertyOccurred: boolean | null; lostProperties: LostPropertyEntry[]
}
export function emptyActivities(): HousekeepingActivities {
  return { schemaVersion: 1, serviceCases: [], lostPropertyOccurred: null, lostProperties: [] }
}
export function readActivities(value?: string): HousekeepingActivities {
  if (!value) return emptyActivities()
  // Never silently discard a corrupted local draft.
  const parsed = JSON.parse(value) as HousekeepingActivities
  if (parsed?.schemaVersion !== 1 || !Array.isArray(parsed.serviceCases) || !Array.isArray(parsed.lostProperties)) throw new Error('服务案例或遗留物草稿格式异常，请保存原内容并联系管理员。')
  const stringFields = (row: unknown, fields: string[]) => Boolean(row && typeof row === 'object' && fields.every(field => typeof (row as Record<string, unknown>)[field] === 'string'))
  const photos = (row: { photoIds: string[] }) => Array.isArray(row.photoIds) && row.photoIds.every(id => typeof id === 'string')
  if ((parsed.lostPropertyOccurred !== null && typeof parsed.lostPropertyOccurred !== 'boolean')
    || parsed.serviceCases.some(row => !stringFields(row, ['id','roomNumber','serviceContent']) || !photos(row))
    || parsed.lostProperties.some(row => !stringFields(row, ['id','itemName','roomNumber','foundLocation','foundAt','recipientAssignmentId','notes']) || !photos(row)
      || (row.quantity !== null && typeof row.quantity !== 'number'))) throw new Error('服务案例或遗留物草稿内容损坏，请保留草稿并联系管理员。')
  return parsed
}
export function validateActivities(value: HousekeepingActivities): string | undefined {
  if (value.lostPropertyOccurred == null) return '请确认今日是否发现遗留物品（无遗留物请选择“无”）。'
  if (value.serviceCases.length > 30 || value.lostProperties.length > 30) return '服务案例和遗留物各最多填写30条。'
  if (value.serviceCases.some(item => !item.roomNumber.trim() || !item.serviceContent.trim() || !item.photoIds.length)) return '请补全服务案例的房号/区域、服务内容及照片。'
  if (value.lostPropertyOccurred && !value.lostProperties.length) return '发现遗留物时至少填写一条物品记录。'
  if (!value.lostPropertyOccurred && value.lostProperties.length) return '已有遗留物记录，请选择“有”。'
  if (value.lostProperties.some(item => !item.itemName.trim() || !item.roomNumber.trim() || !item.foundLocation.trim() || !item.foundAt
    || !item.recipientAssignmentId || !Number.isSafeInteger(item.quantity) || item.quantity! < 1 || item.quantity! > 10000 || !item.photoIds.length)) return '请补全每条遗留物的物品、数量、房号/区域、发现位置与时间、照片及接收人。'
}
