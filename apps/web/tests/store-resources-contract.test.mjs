import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  parseFixedAsset,
  parseHandoverParticipants,
  parseInventoryMovements,
  parseStoreInventoryBalances,
  parseStoreResourceHandoverDetail,
  parseStoreResourceItem,
} from '../src/features/storeResources/types.ts'

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8')

test('store resource response parsers accept the backend contract', () => {
  assert.deepEqual(parseStoreResourceItem({
    id: 'item-1', hotelOrgUnitId: 'hotel-1', sku: 'LINEN-001', name: '浴巾', category: 'LINEN',
    unit: '条', unitCost: 18.5, minimumQuantity: 20, status: 'ACTIVE', rowVersion: 2,
  }), {
    id: 'item-1', hotelOrgUnitId: 'hotel-1', sku: 'LINEN-001', name: '浴巾', category: 'LINEN',
    unit: '条', unitCost: 18.5, minimumQuantity: 20, status: 'ACTIVE', rowVersion: 2,
  })
  assert.equal(parseStoreInventoryBalances([{ itemId: 'item-1', sku: 'LINEN-001', itemName: '浴巾', category: 'LINEN', unit: '条', unitCost: 18.5, minimumQuantity: 20, quantity: 12, inventoryValue: 222 }])[0].quantity, 12)
  assert.equal(parseInventoryMovements([{ id: 'movement-1', itemId: 'item-1', itemName: '浴巾', movementType: 'RECEIPT', quantityDelta: 5, quantityBefore: 7, quantityAfter: 12, unit: '条', reason: '采购入库', operatorName: '管理员', occurredAt: '2026-09-17T08:00:00+08:00' }])[0].quantityAfter, 12)
  assert.equal(parseFixedAsset({ id: 'asset-1', hotelOrgUnitId: 'hotel-1', assetCode: 'TV-001', name: '电视', category: '电器', unit: '台', originalValue: 2600, status: 'ACTIVE', rowVersion: 1 }).originalValue, 2600)
  assert.deepEqual(parseHandoverParticipants([
    { assignmentId: 'a1', employeeId: 'e1', employeeName: '移交人', positionName: '店长', canConfirmHandover: true, canApproveHandover: false },
    { assignmentId: 'a2', employeeId: 'e2', employeeName: '审批人', positionName: '集团总经理', canConfirmHandover: false, canApproveHandover: true },
  ]), [
    { assignmentId: 'a1', employeeId: 'e1', employeeName: '移交人', positionName: '店长', canConfirmHandover: true, canApproveHandover: false },
    { assignmentId: 'a2', employeeId: 'e2', employeeName: '审批人', positionName: '集团总经理', canConfirmHandover: false, canApproveHandover: true },
  ])
  assert.equal(parseStoreResourceHandoverDetail({
    id: 'handover-1', status: 'PHYSICAL_COUNT_REQUIRED', transferorAssignmentId: 'a1', recipientAssignmentId: 'a2', approverAssignmentId: 'a3', transferorName: '移交人', recipientName: '签收人', approverName: '审批人', lineCount: 1, createdAt: '2026-09-17T08:00:00+08:00', rowVersion: 1,
    lines: [{ id: 'line-1', resourceType: 'INVENTORY', resourceCode: 'LINEN-001', resourceName: '浴巾', unit: '条', bookQuantity: 12, bookValue: 222 }],
  }).lines[0].bookQuantity, 12)
})

test('navigation, route and permissions expose the administrator store resource module', () => {
  const domain = read('../src/domain.ts')
  const routes = read('../src/app/routeConfig.ts')
  const permissions = read('../src/app/permissions.ts')
  const app = read('../src/App.tsx')
  assert.match(domain, /\| 'store-resources'/)
  assert.match(routes, /'store-resources'/)
  for (const code of [
    'store-resource.read', 'store-resource.item-manage', 'store-resource.movement-create',
    'store-resource.asset-manage', 'store-resource.handover-create',
    'store-resource.handover-confirm', 'store-resource.handover-approve',
  ]) assert.match(permissions, new RegExp(code.replaceAll('.', '\\.')))
  assert.match(app, /StoreResourceRoutes/)
  assert.match(app, /库存与固定资产/)
  assert.match(app, /case 'store-resources'/)
})

test('store resource client uses concrete backend routes and dedicated handover commands', () => {
  const api = read('../src/features/storeResources/api.ts')
  const page = read('../src/features/storeResources/StoreResourceRoutes.tsx')
  const css = read('../src/features/storeResources/storeResources.module.css')
  for (const fragment of [
    '/balances', '/movements', '/assignment-options', '/actions/dispute-decision',
    '/actions/physical-count', '/actions/${party}-confirm', '/actions/approve',
    "enabled ? 'enable' : 'disable'", "method: 'PUT'", "method: 'DELETE'",
  ]) assert.ok(api.includes(fragment), fragment)
  assert.ok(!api.includes('/summary'), 'summary must be derived from supported resources')
  assert.ok(!api.includes('runStoreResourceHandoverAction'), 'generic empty-body action is forbidden')
  for (const call of [
    'decideStoreResourceHandoverDispute', 'submitStoreResourcePhysicalCount',
    'confirmStoreResourceHandover', 'approveStoreResourceHandover',
    'setStoreResourceItemEnabled', 'updateFixedAsset', 'retireFixedAsset',
  ]) assert.match(page, new RegExp(call))
  for (const movementType of ['RECEIPT', 'CONSUMPTION', 'RETURN', 'DAMAGE', 'ADJUSTMENT']) {
    assert.match(page, new RegExp(`value="${movementType}"`))
  }
  assert.match(page, /transferorAssignmentId/)
  assert.match(page, /new Set\(selectedEmployeeIds\)\.size === 3/)
  assert.match(page, /person\.employeeId !== transferorEmployeeId/)
  assert.match(page, /participants\.filter\(\(person\) => person\.canConfirmHandover\)/)
  assert.match(page, /participants\.filter\(\(person\) => person\.canApproveHandover\)/)
  assert.match(page, /key=\{editing\?\.id \?\? 'new-item'\}/)
  assert.match(page, /key=\{editing\?\.id \?\? 'new-asset'\}/)
  assert.match(page, /key=\{`items:\$\{selectedHotelId\}`\}/)
  assert.match(page, /activeItemIds\.has\(balance\.itemId\)/)
  assert.match(page, /asset\.status !== 'RETIRED'/)
  assert.match(css, /@media \(max-width: 760px\)/)
})
