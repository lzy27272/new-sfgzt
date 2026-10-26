import test from 'node:test'
import assert from 'node:assert/strict'
import { payrollNet, parseBusinessJson, businessStatus, businessList } from '../src/features/finance/businessModel.ts'
test('employee net wages preserve exact cents with independent deductions', () => { assert.equal(payrollNet({ BASIC: '100.10', BONUS: '0.20' }, { TAX: '10.10' }), '90.20'); assert.throws(() => payrollNet({ BASIC: '1' }, { TAX: '2' })); assert.throws(() => payrollNet({ BASIC: '0.001' }, {})) })
test('normalized import refuses floating amounts and unsafe numbers', () => { assert.deepEqual(parseBusinessJson('[{"amount":"99999999999999.99","externalId":"00001"}]', '明细'), [{ amount: '99999999999999.99', externalId: '00001' }]); assert.throws(() => parseBusinessJson('[{"amount":0.1}]', '明细')); assert.throws(() => parseBusinessJson('{invalid', '明细')) })
test('external execution and incomplete consolidation are never labelled completed', () => { assert.match(businessStatus('WAIT_EXTERNAL'), /未开票/); assert.match(businessStatus('PREPARATION_NOT_EXTERNALLY_FILED'), /未外部申报/); assert.match(businessStatus('INCOMPLETE_WORKSHEET'), /不完整/); assert.equal(businessList(null).length, 0) })
