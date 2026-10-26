import test from 'node:test'
import assert from 'node:assert/strict'
import { supplyLineAmount, supplyMoneyDifference, supplyRows, supplyObject } from '../src/features/finance/supplyModel.ts'
test('supply quantity and unit price calculate half-up cents without IEEE-754 loss', () => {
 assert.equal(supplyLineAmount('3', '0.10'), '0.30')
 assert.equal(supplyLineAmount('0.3333', '10.0000'), '3.33')
 assert.equal(supplyLineAmount('1', '0.0050'), '0.01')
 assert.equal(supplyLineAmount('90071992547409', '1'), '90071992547409.00')
 assert.throws(() => supplyLineAmount('1e3', '1'))
 assert.throws(() => supplyLineAmount('-1', '1'))
})
test('supply differences preserve exact signed money and do not flatten snapshots', () => {
 assert.equal(supplyMoneyDifference('100.00', '100.01'), '-0.01')
 assert.equal(supplyMoneyDifference('90071992547409.93', '0.01'), '90071992547409.92')
 assert.deepEqual(supplyRows([null, 'x', [], { id: 'a' }]), [{ id: 'a' }])
 assert.deepEqual(supplyObject(null), {})
})
