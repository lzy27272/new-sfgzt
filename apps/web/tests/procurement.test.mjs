import test from 'node:test'
import assert from 'node:assert/strict'
import { canProcure, filterCatalog, mergeImport, remaining, requiresFinancialDimensions } from '../src/features/procurement/model.ts'
import { buildNavigationCenters, navigationCenterForSection } from '../src/app/navigationModel.ts'
import { applyPublishedModuleVisibility, desktopModuleForRoute } from '../src/app/rolePresentationPolicy.ts'
import { resolveNotificationNavigation } from '../src/app/notificationNavigation.ts'

test('procurement requires a permission and belongs under the workbench, not a new main center', () => {
  assert.equal(canProcure([]), false); assert.equal(canProcure(['store-resource.read']), false)
  for (const p of ['*', 'procurement.request', 'procurement.read', 'procurement.configure']) assert.equal(canProcure([p]), true)
  assert.equal(desktopModuleForRoute('procurement'), 'workbench')
  const features = [{ id: 'workbench', label: '工作台', icon: '' }, { id: 'procurement', label: '采购', icon: '' }]
  const visible = applyPublishedModuleVisibility(features, ['ui.module.workbench', 'procurement.read'], 'HOTEL_MANAGER')
  assert.equal(visible.length, 2)
  const centers = buildNavigationCenters(visible, '店长'); assert.equal(centers.length, 1); assert.equal(centers[0].id, 'workbench')
  assert.equal(navigationCenterForSection('procurement'), 'workbench')
})
test('procurement notices deep-link with permissions and do not become task links', () => {
  const notice = { sourceType: 'PROCUREMENT', sourceId: 'order', type: 'PROCUREMENT_OVERDUE_30_DAYS' }
  assert.equal(resolveNotificationNavigation(notice, []), undefined)
  assert.deepEqual(resolveNotificationNavigation(notice, ['procurement.read']), { view: 'procurement', params: { orderId: 'order' }, actionLabel: '查看采购与收货' })
})
test('item search, configured units, pending reservations and import duplicates remain explicit', () => {
  const items = [{ id: '1', name: '牙刷', sku: 'TOOTH', specification: '100支一箱', unit: '支', purchase_unit: '箱' }, { id: '2', name: '浴巾', sku: 'TOWEL', specification: '纯棉' }]
  assert.equal(filterCatalog(items, ' tooth ')[0].unit, '支'); assert.equal(filterCatalog(items, '纯棉')[0].id, '2')
  assert.equal(remaining({ quantity: 100, approved_quantity: 70, pending_quantity: 20 }), 10)
  assert.throws(() => mergeImport([{ itemId: '1' }], [{ itemId: '1' }]), /重复/)
  assert.deepEqual(mergeImport([{ itemId: '1' }], [{ itemId: '2' }]), [{ itemId: '1' }, { itemId: '2' }])
})

test('installed procurement transition uses only the explicit switch, not finance schema availability', () => {
  assert.equal(requiresFinancialDimensions({ financialTransitionAvailable: true, financialMappingEnabled: true, config: { financial_dimensions_required: false } }), false)
  assert.equal(requiresFinancialDimensions({ financialTransitionAvailable: true, financialMappingEnabled: true, config: { financial_dimensions_required: true } }), true)
  assert.equal(requiresFinancialDimensions({ financialTransitionAvailable: true, financialMappingEnabled: true, config: {} }), false)
  assert.equal(requiresFinancialDimensions({ financialTransitionAvailable: false, financialMappingEnabled: true, config: {} }), true)
  assert.equal(requiresFinancialDimensions({ financialMappingEnabled: true, config: {} }), true)
  assert.equal(requiresFinancialDimensions({ config: {} }), false)
})
