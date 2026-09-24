const genericKeys = new Set(['workSummary', 'issueFound', 'workResult', 'hasException'])
const genericTitles = new Set(['工作结果', '是否有异常', '是否发现问题', '班次工作结果', '夜班工作结果', '服务结果', '完成情况'])
const occurrenceCodes = new Set(['HKA_GUEST_SERVICE_EVENT', 'HKA_COMPLAINT_EVENT', 'HKA_LOST_PROPERTY'])
const housekeepingFields: Record<string, Set<string>> = {
  HKA_ITEM_ISSUE: new Set(['inventoryQuantities']),
  HKA_CART_PREPARATION: new Set(['notes']),
  HKA_USAGE_RETURN: new Set(['inventoryQuantities', 'notes']),
  HKA_DAILY_REPORT: new Set(['checkoutRooms', 'stayoverRooms', 'inventoryQuantities', 'plannedHygieneContent', 'plannedHygieneRoomNumbers']),
  HKA_LOST_PROPERTY: new Set(['roomNumber', 'itemName', 'quantity', 'foundAt', 'foundLocation', 'handoverRecipientAssignmentId', 'notes']),
}

export function isGenericWorkQuestion(code: string, title?: string): boolean {
  return genericKeys.has(code) || genericTitles.has(title ?? '')
}

export function isOccurrenceEntry(code?: string): boolean { return occurrenceCodes.has((code ?? '').toUpperCase().replace(/_FACT$/, '')) }

export function allowsGuestServiceAlbumUpload(
  itemCode: string | undefined,
  requirement: { checkpointCode: string; captureSource?: string },
): boolean {
  return (itemCode ?? '').toUpperCase().replace(/_FACT$/, '') === 'HKA_GUEST_SERVICE_EVENT'
    && requirement.checkpointCode === 'guest_service_photo'
    && (!requirement.captureSource || requirement.captureSource === 'FILE_PICKER')
}

export function workPolicyValue(payload: Record<string, unknown>, field: string): unknown {
  if (field === 'issueFound' || field === 'hasException') {
    const details = typeof payload.issueSummary === 'string' ? payload.issueSummary.trim() : ''
    return Boolean(details) && !['无', '无异常', '无问题', '未发现问题', '正常'].includes(details)
  }
  return payload[field]
}

export function focusedWorkFields<T extends { title?: string }>(fields: Record<string, T>, itemCode?: string): Record<string, T> {
  const allowed = housekeepingFields[itemCode ?? '']
  return Object.fromEntries(Object.entries(fields).filter(([key, field]) =>
    !isGenericWorkQuestion(key, field.title) && (!allowed || allowed.has(key))))
}

export function matchesTaskAssignee(
  row: { employeeName: string; positionName: string; orgName: string }, query: string,
): boolean {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  const text = [row.employeeName, row.positionName, row.orgName].join(' ').toLocaleLowerCase()
  return words.every((word) => text.includes(word))
}
