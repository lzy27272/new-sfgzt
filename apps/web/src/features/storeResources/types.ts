export type StoreResourceTab = 'items' | 'inventory' | 'assets' | 'handovers'

export type StoreResourceHotel = {
  id: string
  name: string
  code?: string
  city?: string
}

export type StoreResourceItemCategory = 'LINEN' | 'CONSUMABLE' | 'CLEANING_SUPPLY' | 'OPERATING_ITEM'

export type StoreResourceItem = {
  id: string
  hotelOrgUnitId: string
  sku: string
  name: string
  category: StoreResourceItemCategory
  unit: string
  unitCost: number
  minimumQuantity: number
  status: string
  rowVersion: number
}

export type StoreInventoryBalance = {
  itemId: string
  sku: string
  itemName: string
  category: StoreResourceItemCategory
  unit: string
  unitCost: number
  minimumQuantity: number
  quantity: number
  inventoryValue: number
}

export type InventoryMovement = {
  id: string
  itemId: string
  itemName: string
  movementType: string
  quantityDelta: number
  quantityBefore: number
  quantityAfter: number
  unit: string
  reason: string
  operatorName: string
  occurredAt: string
  sourceType?: string
}

export type FixedAsset = {
  id: string
  hotelOrgUnitId: string
  assetCode: string
  name: string
  category: string
  specification?: string
  serialNumber?: string
  unit: string
  location?: string
  responsibleAssignmentId?: string
  responsibleName?: string
  status: string
  acquiredOn?: string
  originalValue: number
  rowVersion: number
}

export type HandoverParticipant = {
  assignmentId: string
  employeeId: string
  employeeName: string
  positionName: string
  canConfirmHandover: boolean
  canApproveHandover: boolean
}

export type StoreResourceHandover = {
  id: string
  handoverNo?: string
  status: string
  transferorAssignmentId: string
  recipientAssignmentId: string
  approverAssignmentId: string
  transferorName: string
  recipientName: string
  approverName: string
  snapshotAt?: string
  disputeDecision?: string
  disputeReason?: string
  hasDispute: boolean
  physicalCountRequired: boolean
  itemCount: number
  assetCount: number
  lineCount: number
  allowedActions: string[]
  createdAt: string
  updatedAt?: string
  rowVersion: number
}

export type StoreResourceHandoverLine = {
  id: string
  resourceType: 'INVENTORY' | 'ASSET'
  resourceCode: string
  resourceName: string
  unit?: string
  bookQuantity?: number
  bookState?: string
  bookValue: number
  physicalQuantity?: number
  physicalState?: string
  differenceReason?: string
}

export type StoreResourceHandoverDetail = StoreResourceHandover & { lines: StoreResourceHandoverLine[] }

export type StoreResourceItemInput = {
  sku: string
  name: string
  category: StoreResourceItemCategory
  unit: string
  unitCost: number
  minimumQuantity: number
}

export type InventoryAdjustmentInput = {
  itemId: string
  movementType: 'RECEIPT' | 'CONSUMPTION' | 'RETURN' | 'DAMAGE' | 'ADJUSTMENT'
  quantity: number
  unitCost?: number
  reason: string
}

export type FixedAssetInput = {
  assetCode: string
  name: string
  category: string
  specification?: string
  serialNumber?: string
  unit: string
  originalValue: number
  acquiredOn?: string
  location?: string
  responsibleAssignmentId?: string
  status: string
}

export type StoreResourceHandoverInput = {
  transferorAssignmentId: string
  recipientAssignmentId: string
  approverAssignmentId: string
  note?: string
}

export type StoreResourceImportError = {
  sheetName: string
  rowNumber: number
  field: string
  message: string
  value?: string
}

export type StoreResourceImportPreviewItem = {
  rowNumber: number
  sku: string
  name: string
  category: StoreResourceItemCategory
  unit: string
  unitCost: number
  minimumQuantity: number
  openingQuantity: number
}

export type StoreResourceImportPreviewAsset = {
  rowNumber: number
  assetCode: string
  name: string
  category: string
  unit: string
  originalValue: number
  acquiredOn?: string
  location?: string
  status: string
}

export type StoreResourceImportJob = {
  id: string
  fileName: string
  status: 'INVALID' | 'READY' | 'APPLIED' | 'CANCELLED'
  rowCount: number
  validRowCount: number
  errorCount: number
  canApply: boolean
  duplicateUpload: boolean
  summary: {
    itemCount: number
    assetCount: number
    openingQuantity: number
    openingInventoryValue: number
    assetOriginalValue: number
  }
  preview: {
    items: StoreResourceImportPreviewItem[]
    assets: StoreResourceImportPreviewAsset[]
  }
  errors: StoreResourceImportError[]
  result?: Record<string, unknown>
  createdAt?: string
  appliedAt?: string
}

export type StoreResourceImportHistory = {
  id: string
  originalName: string
  status: string
  rowCount: number
  validRowCount: number
  errorCount: number
  createdAt: string
  appliedAt?: string
}

type JsonObject = Record<string, unknown>

function object(value: unknown, label: string): JsonObject {
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error(`STORE_RESOURCE_RESPONSE_INVALID:${label}`)
  return value as JsonObject
}

function list(value: unknown): unknown[] {
  if (Array.isArray(value)) return value
  if (value && typeof value === 'object') {
    const row = value as JsonObject
    for (const key of ['items', 'content', 'data', 'records', 'results']) {
      if (Array.isArray(row[key])) return row[key] as unknown[]
    }
  }
  throw new Error('STORE_RESOURCE_RESPONSE_INVALID:list')
}

function text(row: JsonObject, key: string, fallback?: string): string {
  const value = row[key]
  if (typeof value === 'string' && value.trim()) return value
  if (fallback !== undefined) return fallback
  throw new Error(`STORE_RESOURCE_RESPONSE_INVALID:${key}`)
}

function optionalText(row: JsonObject, key: string): string | undefined {
  const value = row[key]
  return typeof value === 'string' && value.trim() ? value : undefined
}

function numberValue(row: JsonObject, key: string, fallback = 0): number {
  const raw = row[key]
  const value = typeof raw === 'number' ? raw : typeof raw === 'string' && raw.trim() ? Number(raw) : fallback
  if (!Number.isFinite(value)) throw new Error(`STORE_RESOURCE_RESPONSE_INVALID:${key}`)
  return value
}

function booleanValue(row: JsonObject, key: string, fallback = false): boolean {
  const value = row[key]
  if (typeof value === 'boolean') return value
  if (value === undefined || value === null) return fallback
  throw new Error(`STORE_RESOURCE_RESPONSE_INVALID:${key}`)
}

function category(value: string): StoreResourceItemCategory {
  return ['LINEN', 'CONSUMABLE', 'CLEANING_SUPPLY', 'OPERATING_ITEM'].includes(value)
    ? value as StoreResourceItemCategory
    : 'OPERATING_ITEM'
}

export function parseStoreResourceHotels(value: unknown): StoreResourceHotel[] {
  return list(value).map((entry) => {
    const row = object(entry, 'hotel')
    return { id: text(row, 'id'), name: text(row, 'name'), code: optionalText(row, 'code'), city: optionalText(row, 'city') }
  })
}

export function parseStoreResourceItem(value: unknown): StoreResourceItem {
  const row = object(value, 'item')
  return {
    id: text(row, 'id'), hotelOrgUnitId: text(row, 'hotelOrgUnitId', text(row, 'hotelId', '')), sku: text(row, 'sku'), name: text(row, 'name'),
    category: category(text(row, 'category', 'OPERATING_ITEM')), unit: text(row, 'unit'),
    unitCost: numberValue(row, 'unitCost'), minimumQuantity: numberValue(row, 'minimumQuantity'),
    status: text(row, 'status', 'ACTIVE'), rowVersion: numberValue(row, 'rowVersion'),
  }
}

export function parseStoreResourceItems(value: unknown): StoreResourceItem[] {
  return list(value).map(parseStoreResourceItem)
}

export function parseStoreInventoryBalances(value: unknown): StoreInventoryBalance[] {
  return list(value).map((entry) => {
    const row = object(entry, 'balance')
    return {
      itemId: text(row, 'itemId'), sku: text(row, 'sku'), itemName: text(row, 'itemName', text(row, 'name', '未命名物品')),
      category: category(text(row, 'category', 'OPERATING_ITEM')), unit: text(row, 'unit'), unitCost: numberValue(row, 'unitCost'),
      minimumQuantity: numberValue(row, 'minimumQuantity'), quantity: numberValue(row, 'quantity'), inventoryValue: numberValue(row, 'inventoryValue'),
    }
  })
}

export function parseInventoryMovements(value: unknown): InventoryMovement[] {
  return list(value).map((entry) => {
    const row = object(entry, 'movement')
    return {
      id: text(row, 'id'), itemId: text(row, 'itemId'), itemName: text(row, 'itemName'), movementType: text(row, 'movementType'),
      quantityDelta: numberValue(row, 'quantityDelta'), quantityBefore: numberValue(row, 'quantityBefore'), quantityAfter: numberValue(row, 'quantityAfter'),
      unit: text(row, 'unit'), reason: text(row, 'reason', '—'), operatorName: text(row, 'operatorName', '系统'),
      occurredAt: text(row, 'occurredAt'), sourceType: optionalText(row, 'sourceType'),
    }
  })
}

export function parseFixedAsset(value: unknown): FixedAsset {
  const row = object(value, 'asset')
  return {
    id: text(row, 'id'), hotelOrgUnitId: text(row, 'hotelOrgUnitId', text(row, 'hotelId', '')), assetCode: text(row, 'assetCode'), name: text(row, 'name'),
    category: text(row, 'category'), specification: optionalText(row, 'specification'), serialNumber: optionalText(row, 'serialNumber'),
    unit: text(row, 'unit', '台'), location: optionalText(row, 'location'), responsibleAssignmentId: optionalText(row, 'responsibleAssignmentId'),
    responsibleName: optionalText(row, 'responsibleName'), status: text(row, 'status'), acquiredOn: optionalText(row, 'acquiredOn'),
    originalValue: numberValue(row, 'originalValue'),
    rowVersion: numberValue(row, 'rowVersion'),
  }
}

export function parseFixedAssets(value: unknown): FixedAsset[] {
  return list(value).map(parseFixedAsset)
}

export function parseHandoverParticipants(value: unknown): HandoverParticipant[] {
  return list(value).map((entry) => {
    const row = object(entry, 'participant')
    return {
      assignmentId: text(row, 'assignmentId', text(row, 'id', '')),
      employeeId: text(row, 'employeeId', text(row, 'assignmentId', text(row, 'id', ''))),
      employeeName: text(row, 'employeeName', text(row, 'name', '')),
      positionName: text(row, 'positionName'),
      // Defaults preserve compatibility with a rolling deployment where the web
      // bundle reaches a node before the qualification fields are available.
      canConfirmHandover: booleanValue(row, 'canConfirmHandover', true),
      canApproveHandover: booleanValue(row, 'canApproveHandover', true),
    }
  })
}

export function parseStoreResourceHandover(value: unknown): StoreResourceHandover {
  const row = object(value, 'handover')
  const itemCount = numberValue(row, 'itemCount')
  const assetCount = numberValue(row, 'assetCount')
  return {
    id: text(row, 'id'), handoverNo: optionalText(row, 'handoverNo'), status: text(row, 'status'), transferorAssignmentId: text(row, 'transferorAssignmentId'),
    recipientAssignmentId: text(row, 'recipientAssignmentId'), approverAssignmentId: text(row, 'approverAssignmentId'),
    transferorName: text(row, 'transferorName'), recipientName: text(row, 'recipientName'), approverName: text(row, 'approverName'),
    snapshotAt: optionalText(row, 'snapshotAt'), disputeDecision: optionalText(row, 'disputeDecision'), disputeReason: optionalText(row, 'disputeReason'),
    hasDispute: booleanValue(row, 'hasDispute'), physicalCountRequired: booleanValue(row, 'physicalCountRequired'),
    itemCount, assetCount, lineCount: numberValue(row, 'lineCount', itemCount + assetCount),
    allowedActions: Array.isArray(row.allowedActions) ? row.allowedActions.filter((entry): entry is string => typeof entry === 'string') : [],
    createdAt: text(row, 'createdAt'), updatedAt: optionalText(row, 'updatedAt'),
    rowVersion: numberValue(row, 'rowVersion'),
  }
}

export function parseStoreResourceHandovers(value: unknown): StoreResourceHandover[] {
  return list(value).map(parseStoreResourceHandover)
}

export function parseStoreResourceHandoverDetail(value: unknown): StoreResourceHandoverDetail {
  const row = object(value, 'handover-detail')
  return {
    ...parseStoreResourceHandover(row),
    lines: list(row.lines ?? []).map((entry) => {
      const line = object(entry, 'handover-line')
      const resourceType = text(line, 'resourceType')
      if (resourceType !== 'INVENTORY' && resourceType !== 'ASSET') throw new Error('STORE_RESOURCE_RESPONSE_INVALID:resourceType')
      return {
        id: text(line, 'id'), resourceType, resourceCode: text(line, 'resourceCode'), resourceName: text(line, 'resourceName'),
        unit: optionalText(line, 'unit'), bookQuantity: line.bookQuantity == null ? undefined : numberValue(line, 'bookQuantity'),
        bookState: optionalText(line, 'bookState'), bookValue: numberValue(line, 'bookValue'),
        physicalQuantity: line.physicalQuantity == null ? undefined : numberValue(line, 'physicalQuantity'),
        physicalState: optionalText(line, 'physicalState'), differenceReason: optionalText(line, 'differenceReason'),
      }
    }),
  }
}

export function parseStoreResourceImportJob(value: unknown): StoreResourceImportJob {
  const row = object(value, 'store-resource-import')
  const status = text(row, 'status')
  if (!['INVALID', 'READY', 'APPLIED', 'CANCELLED'].includes(status)) {
    throw new Error('STORE_RESOURCE_RESPONSE_INVALID:importStatus')
  }
  const summary = object(row.summary ?? {}, 'import-summary')
  const preview = object(row.preview ?? {}, 'import-preview')
  const parseItem = (entry: unknown): StoreResourceImportPreviewItem => {
    const item = object(entry, 'import-item')
    return {
      rowNumber: numberValue(item, 'rowNumber'), sku: text(item, 'sku'), name: text(item, 'name'),
      category: category(text(item, 'category', 'OPERATING_ITEM')), unit: text(item, 'unit'),
      unitCost: numberValue(item, 'unitCost'), minimumQuantity: numberValue(item, 'minimumQuantity'),
      openingQuantity: numberValue(item, 'openingQuantity'),
    }
  }
  const parseAsset = (entry: unknown): StoreResourceImportPreviewAsset => {
    const asset = object(entry, 'import-asset')
    return {
      rowNumber: numberValue(asset, 'rowNumber'), assetCode: text(asset, 'assetCode'), name: text(asset, 'name'),
      category: text(asset, 'category'), unit: text(asset, 'unit'), originalValue: numberValue(asset, 'originalValue'),
      acquiredOn: optionalText(asset, 'acquiredOn'), location: optionalText(asset, 'location'), status: text(asset, 'status', 'ACTIVE'),
    }
  }
  const errors = Array.isArray(row.errors) ? row.errors.map((entry) => {
    const error = object(entry, 'import-error')
    return {
      sheetName: text(error, 'sheetName'), rowNumber: numberValue(error, 'rowNumber'), field: text(error, 'field'),
      message: text(error, 'message'), value: optionalText(error, 'value'),
    }
  }) : []
  return {
    id: text(row, 'id'), fileName: text(row, 'fileName'), status: status as StoreResourceImportJob['status'],
    rowCount: numberValue(row, 'rowCount'), validRowCount: numberValue(row, 'validRowCount'), errorCount: numberValue(row, 'errorCount'),
    canApply: booleanValue(row, 'canApply'), duplicateUpload: booleanValue(row, 'duplicateUpload'),
    summary: {
      itemCount: numberValue(summary, 'itemCount'), assetCount: numberValue(summary, 'assetCount'),
      openingQuantity: numberValue(summary, 'openingQuantity'), openingInventoryValue: numberValue(summary, 'openingInventoryValue'),
      assetOriginalValue: numberValue(summary, 'assetOriginalValue'),
    },
    preview: {
      items: Array.isArray(preview.items) ? preview.items.map(parseItem) : [],
      assets: Array.isArray(preview.assets) ? preview.assets.map(parseAsset) : [],
    },
    errors, result: row.result && typeof row.result === 'object' && !Array.isArray(row.result) ? row.result as Record<string, unknown> : undefined,
    createdAt: optionalText(row, 'createdAt'), appliedAt: optionalText(row, 'appliedAt'),
  }
}

export function parseStoreResourceImportHistory(value: unknown): StoreResourceImportHistory[] {
  return list(value).map((entry) => {
    const row = object(entry, 'import-history')
    return {
      id: text(row, 'id'), originalName: text(row, 'originalName'), status: text(row, 'status'),
      rowCount: numberValue(row, 'rowCount'), validRowCount: numberValue(row, 'validRowCount'),
      errorCount: numberValue(row, 'errorCount'), createdAt: text(row, 'createdAt'), appliedAt: optionalText(row, 'appliedAt'),
    }
  })
}
