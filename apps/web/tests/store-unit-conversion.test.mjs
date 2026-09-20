import assert from 'node:assert/strict'
import test from 'node:test'
import { baseQuantityPreview, entryUnits, unitConfigurationError } from '../src/features/storeResources/unitConversion.ts'
import { parseStoreResourceItem, parseInventoryMovements } from '../src/features/storeResources/types.ts'

test('per-item unit aliases are deduplicated and preserve a base-unit option', () => {
  assert.deepEqual(entryUnits({ unit: '支', purchaseUnit: '箱', purchaseFactor: 100, issueUnit: '支', issueFactor: 1 }), [
    { unit: '支', factor: 1 }, { unit: '箱', factor: 100 },
  ])
  assert.deepEqual(entryUnits({ unit: 'ML', purchaseUnit: 'ml', purchaseFactor: 1, issueUnit: 'ML', issueFactor: 1 }), [{ unit: 'ML', factor: 1 }])
})

test('quantity preview uses exact decimals and never rounds lost base quantities to zero', () => {
  assert.equal(baseQuantityPreview('2', 100).quantity, '200')
  assert.equal(baseQuantityPreview('0.15', 1000).quantity, '150')
  assert.equal(baseQuantityPreview('0.1', 0.1).quantity, '0.01')
  assert.equal(baseQuantityPreview('-2', 100).quantity, '-200')
  assert.equal(baseQuantityPreview('0.0001', 1).quantity, '0.0001')
  assert.match(baseQuantityPreview('0.0001', 0.1).error, /超过4位小数/)
  assert.match(baseQuantityPreview('1000000000000', 100).error, /过大/)
  for (const invalid of ['0', 'abc', '0.00001', 'Infinity']) assert.ok(baseQuantityPreview(invalid, 1).error)
  assert.deepEqual(baseQuantityPreview('', 100), {})
})

test('unit configuration disallows conflicting same-name factors and missing non-base ratios', () => {
  assert.equal(unitConfigurationError('支', '箱', '100', '支', '1'), undefined)
  assert.equal(unitConfigurationError('支', '', '', '', ''), undefined)
  assert.ok(unitConfigurationError('支', '箱', '', '支', '1'))
  assert.ok(unitConfigurationError('支', '箱', '100', '箱', '10'))
  assert.ok(unitConfigurationError('ML', 'ml', '100', 'ml', '1'))
  for (const invalid of ['0', '-1', '0.0000001', '1000000000000']) assert.ok(unitConfigurationError('支', '箱', invalid, '支', '1'))
})

test('responses preserve immutable input-unit facts and the base-unit lock', () => {
  const item = parseStoreResourceItem({ id: 'i', sku: 'A', name: '牙刷', unit: '支', specification: '100支/箱', purchaseUnit: '箱', purchaseFactor: 120, issueUnit: '支', issueFactor: 1, baseUnitLocked: true })
  assert.equal(item.purchaseFactor, 120)
  assert.equal(item.specification, '100支/箱')
  assert.equal(item.baseUnitLocked, true)
  const [movement] = parseInventoryMovements([{ id: 'm', itemId: 'i', itemName: '牙刷', movementType: 'RECEIPT', unit: '支', quantityDelta: 200, quantityBefore: 0, quantityAfter: 200, inputUnit: '箱', inputQuantity: 2, conversionFactor: 100, inputUnitCost: 50, valueDelta: 100, unitCostSnapshot: 0.5, occurredAt: '2026-09-18T01:00:00Z' }])
  assert.equal(movement.conversionFactor, 100, 'history must not use the current item factor')
  assert.equal(movement.inputQuantity, 2)
  assert.equal(movement.inputUnitCost, 50)
  assert.equal(movement.valueDelta, 100)
})
