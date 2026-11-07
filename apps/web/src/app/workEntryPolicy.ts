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
    && (!requirement.captureSource || ['FILE_PICKER', 'CAMERA_OR_FILE'].includes(requirement.captureSource))
}

export function evidenceRequirementLabel(requirement: { label: string; dateOffsetDays?: number }, businessDate: string): string {
  if (requirement.dateOffsetDays === undefined || !Number.isInteger(requirement.dateOffsetDays) || !/^\d{4}-\d{2}-\d{2}$/.test(businessDate)) return requirement.label
  const date = new Date(`${businessDate}T12:00:00Z`)
  if (!Number.isFinite(date.getTime())) return requirement.label
  date.setUTCDate(date.getUTCDate() + requirement.dateOffsetDays)
  return `${date.toISOString().slice(0, 10)} · ${requirement.label}`
}

export function evidenceCaptureSourceMatches(actual: string | undefined, required?: string): boolean {
  if (!required) return true
  if (required === 'CAMERA_OR_FILE') return actual === 'CAMERA' || actual === 'FILE_PICKER'
  return actual === required
}

export function workPolicyValue(payload: Record<string, unknown>, field: string): unknown {
  if (field === 'issueFound' || field === 'hasException') {
    const details = typeof payload.issueSummary === 'string' ? payload.issueSummary.trim() : ''
    return Boolean(details) && !['无', '无异常', '无问题', '未发现问题', '正常'].includes(details)
  }
  return payload[field]
}

export function focusedWorkFields<T extends { title?: string; description?: string }>(fields: Record<string, T>, itemCode?: string): Record<string, T> {
  const allowed = housekeepingFields[itemCode ?? '']
  return Object.fromEntries(Object.entries(fields).filter(([key, field]) =>
    !isGenericWorkQuestion(key, field.title) && (!allowed || allowed.has(key))).map(([key, field]) => [key,
      key === 'issueSummary' ? { ...field, title: '异常及处理',
        description: '仅填写实际问题、异常及处理情况；订单数、好评率、完成数量等普通统计请填上方‘工作结果说明’。正常时可留空；如标注必填，请填‘无异常’。' } : field]))
}

export function matchesTaskAssignee(
  row: { employeeName: string; positionName: string; orgName: string }, query: string,
): boolean {
  const words = query.trim().toLocaleLowerCase().split(/\s+/).filter(Boolean)
  const text = [row.employeeName, row.positionName, row.orgName].join(' ').toLocaleLowerCase()
  return words.every((word) => text.includes(word))
}
