import assert from 'node:assert/strict'
import { test } from 'node:test'
import { dispatchValidation, laundryDecimal, laundryFeePreview, laundryStatus, receiveValidation } from '../src/features/storeResources/laundry.ts'

const item = { itemId: 'item-1', itemName: '浴巾', baseUnit: '条', itemVersion: 0, rateVersion: 1, unitPrice: 1.25, ownedQuantity: 100, pendingQuantity: 20, availableQuantity: 80 }
const dispatch = { supplier: '洗涤厂', dispatchedOn: '2026-09-18', note: '', lines: [{ itemId: 'item-1', quantity: '20', expectedRateVersion: 1, expectedItemVersion: 0 }] }
const order = { id: 'order-1', dispatchedOn: '2026-09-18', status: 'SENT', rowVersion: 0, lines: [{ id: 'line-1', itemId: 'item-1', itemName: '浴巾', quantity: 20, pendingQuantity: 20, baseUnit: '条', unitPrice: 1.25 }] }
const receive = { receivedOn: '2026-09-18', expectedVersion: 0, note: '', confirmWriteOff: false, lines: [{ lineId: 'line-1', acceptedQuantity: '8', rewashQuantity: '2', writtenOffQuantity: '0', differenceReason: '污渍返洗' }] }

test('laundry price preview uses exact decimals and four-place posting rounding', () => {
  assert.equal(laundryFeePreview('8', 1.25), '10.0000')
  assert.equal(laundryFeePreview('0.3', 0.1), '0.0300')
  assert.equal(laundryFeePreview('0.0001', 0.5), '0.0001')
  for (const value of ['-1', '1e2', 'Infinity', '0.00001', '', '100000000000000']) assert.equal(laundryDecimal(value), undefined)
})
test('dispatch requires configured price, available custody, unique items and fresh versions', () => {
  assert.equal(dispatchValidation(dispatch, [item]), undefined)
  assert.match(dispatchValidation(dispatch, [{ ...item, unitPrice: null }]), /配置/)
  assert.match(dispatchValidation(dispatch, [{ ...item, availableQuantity: 1 }]), /超过/)
  assert.match(dispatchValidation(dispatch, [{ ...item, rateVersion: 2 }]), /变化/)
  assert.match(dispatchValidation(dispatch, [{ ...item, itemVersion: 2 }]), /变化/)
  assert.match(dispatchValidation({ ...dispatch, lines: [...dispatch.lines, ...dispatch.lines] }, [item]), /重复/)
  assert.match(dispatchValidation({ ...dispatch, lines: [] }, [item]), /添加/)
})
test('qualified return and rewash stay separate and must fit remaining quantity', () => {
  assert.equal(receiveValidation(receive, order), undefined)
  assert.match(receiveValidation({ ...receive, lines: [{ ...receive.lines[0], acceptedQuantity: '19' }] }, order), /不超过/)
  assert.match(receiveValidation({ ...receive, lines: [{ ...receive.lines[0], differenceReason: '' }] }, order), /原因/)
  assert.match(receiveValidation({ ...receive, lines: [{ ...receive.lines[0], acceptedQuantity: '', rewashQuantity: '0' }] }, order), /非负/)
})
test('loss requires explicit confirmation as well as a reason', () => {
  const loss = { ...receive, lines: [{ ...receive.lines[0], rewashQuantity: '0', writtenOffQuantity: '1', differenceReason: '损坏' }] }
  assert.match(receiveValidation(loss, order), /确认报损/)
  assert.equal(receiveValidation({ ...loss, confirmWriteOff: true }, order), undefined)
  assert.match(receiveValidation({ ...loss, confirmWriteOff: true, lines: [{ ...loss.lines[0], differenceReason: '' }] }, order), /原因/)
})
test('receipt rejects stale, foreign, duplicate, closed and invalid-date inputs', () => {
  assert.match(receiveValidation(receive, { ...order, rowVersion: 1 }), /变化/)
  assert.match(receiveValidation(receive, { ...order, status: 'COMPLETED' }), /不能/)
  assert.match(receiveValidation({ ...receive, receivedOn: '2026-09-17' }, order), /日期/)
  assert.match(receiveValidation({ ...receive, lines: [{ ...receive.lines[0], lineId: 'other' }] }, order), /不存在/)
  assert.match(receiveValidation({ ...receive, lines: [...receive.lines, ...receive.lines] }, order), /重复/)
  assert.match(receiveValidation({ ...receive, lines: [] }, order), /至少一项/)
  assert.equal(laundryStatus('PARTIAL'), '部分回收 / 仍有在洗')
})
