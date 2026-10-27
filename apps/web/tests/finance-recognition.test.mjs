import assert from 'node:assert/strict'
import test from 'node:test'
import { acceptIntakeRecognition, applyEvidenceRecognition, automaticCandidate, mergeInvoiceCandidates, mergeIntakeCandidates, recognitionCandidates, reconcileEvidenceAmounts, touchedRecognition } from '../src/features/finance/recognitionModel.ts'

const extraction = (fields = {}) => ({ source: 'LOCAL_OCR', status: 'CANDIDATES', confirmationRequired: true, ...fields })
const evidence = (id, kind, fields = {}) => ({ id, kind, extraction: extraction(fields), original_name: `${id}.jpg`, media_type: 'image/jpeg', sha256: id.repeat(64).slice(0, 64), size_bytes: 20 })
const invoice = (fields = {}) => ({ id: 'invoice', attachmentId: 'a', invoiceCode: '', invoiceNumber: '', amount: '', buyerTaxNumber: '', confirmed: false, ...fields })
const draft = (fields = {}) => ({ dirty: false, files: [], form: { expectedVersion: 0, title: '客房用品', businessDate: '2026-10-26', categoryId: null, transactionAmount: '0.00', paymentAmount: '0.00', requestedAmount: '0.00', differenceNote: '', invoices: [], ...fields } })

test('invoice candidates fill blank fields including buyer tax number without approving OCR facts', () => {
  const attached = evidence('a', 'INVOICE', { invoiceNumber: '00000000000000123456', amount: '100.00', buyerTaxNumber: '91520000123456789X' })
  const result = applyEvidenceRecognition(draft(), attached, [attached], 'new-invoice')
  assert.equal(result.form.invoices[0].invoiceNumber, '00000000000000123456')
  assert.equal(result.form.invoices[0].buyerTaxNumber, '91520000123456789X')
  assert.equal(result.form.invoices[0].amount, '100.00')
  assert.equal(result.form.invoices[0].confirmed, false)
  assert.equal(result.form.requestedAmount, '0.00')
  assert.equal(result.dirty, true)
})
test('retry neither overwrites typed invoice values nor silently refills a manually cleared field', () => {
  const value = invoice({ invoiceNumber: '00000000000000999999', amount: '90.00', buyerTaxNumber: '', confirmed: true })
  const result = mergeInvoiceCandidates(value, extraction({ invoiceNumber: '00000000000000123456', amount: '100.00', buyerTaxNumber: '91520000123456789X' }), ['invoice:invoice:buyerTaxNumber'])
  assert.deepEqual(result, value)
  const changed = mergeInvoiceCandidates(value, extraction({ buyerTaxNumber: '91520000123456789X' }), [])
  assert.equal(changed.buyerTaxNumber, '91520000123456789X')
  assert.equal(changed.confirmed, false)
})
test('each evidence kind fills only its own amount, never a generic OCR amount or invoice total', () => {
  const payment = evidence('p', 'PAYMENT', { amount: '500.00', transactionAmount: '400.00', paymentAmount: '90.00' })
  const result = applyEvidenceRecognition(draft(), payment, [payment], 'unused')
  assert.equal(result.form.paymentAmount, '90.00')
  assert.equal(result.form.transactionAmount, '0.00')
  assert.equal(result.form.invoices.length, 0)
  const transaction = evidence('t', 'TRANSACTION', { amount: '500.00', transactionAmount: '100.00', paymentAmount: '90.00' })
  const combined = applyEvidenceRecognition(result, transaction, [payment, transaction], 'unused')
  assert.equal(combined.form.transactionAmount, '100.00')
  assert.equal(combined.form.paymentAmount, '90.00')
})
test('second payment removes an unreviewed single-document autofill instead of using the last or summing', () => {
  const a = evidence('a', 'PAYMENT', { paymentAmount: '90.00' }); const b = evidence('b', 'PAYMENT', { paymentAmount: '10.00' })
  const first = applyEvidenceRecognition(draft(), a, [a], 'unused')
  assert.equal(first.form.paymentAmount, '90.00')
  const second = applyEvidenceRecognition(first, b, [a, b], 'unused')
  assert.equal(second.form.paymentAmount, '')
  assert.equal(second.recognition.amounts.paymentAmount, undefined)
  const roundTrip = JSON.parse(JSON.stringify(first))
  assert.equal(applyEvidenceRecognition(roundTrip, b, [a, b], 'unused').form.paymentAmount, '')
})
test('several queued transaction documents are ambiguous before all uploads complete', () => {
  const a = evidence('a', 'TRANSACTION', { transactionAmount: '100.00' })
  const pending = { id: 'b', kind: 'TRANSACTION', extraction: { source: 'MANUAL_REQUIRED' } }
  const result = applyEvidenceRecognition(draft(), a, [a, pending], 'unused')
  assert.equal(result.form.transactionAmount, '0.00')
  assert.equal(result.recognition.amounts.transactionAmount, undefined)
})
test('manually checked amounts including zero survive later receipts and automatic retries', () => {
  const a = evidence('a', 'PAYMENT', { paymentAmount: '90.00' }); const b = evidence('b', 'PAYMENT', { paymentAmount: '10.00' })
  const value = draft({ paymentAmount: '0.00' }); value.recognition = touchedRecognition(value, ['paymentAmount'])
  assert.equal(applyEvidenceRecognition(value, a, [a], 'unused').form.paymentAmount, '0.00')
  const first = applyEvidenceRecognition(draft(), a, [a], 'unused')
  const manual = { ...first, form: { ...first.form, paymentAmount: '100.00' }, recognition: touchedRecognition(first, ['paymentAmount']) }
  assert.equal(reconcileEvidenceAmounts(manual, [a, b]).form.paymentAmount, '100.00')
  assert.equal(reconcileEvidenceAmounts(draft({ paymentAmount: '73.00' }), [a]).form.paymentAmount, '73.00')
})
test('saved zero amounts survive a new device without local provenance while new defaults still fill', () => {
  const payment = evidence('p', 'PAYMENT', { paymentAmount: '90.00' })
  const transaction = evidence('t', 'TRANSACTION', { transactionAmount: '100.00' })
  const saved = draft({ expectedVersion: 2 })
  assert.equal(saved.recognition, undefined)
  const preserved = reconcileEvidenceAmounts(saved, [payment, transaction])
  assert.equal(preserved.form.paymentAmount, '0.00')
  assert.equal(preserved.form.transactionAmount, '0.00')
  assert.deepEqual(preserved.recognition.amounts, {})
  const fresh = reconcileEvidenceAmounts(draft(), [payment, transaction])
  assert.equal(fresh.form.paymentAmount, '90.00')
  assert.equal(fresh.form.transactionAmount, '100.00')
  const blank = reconcileEvidenceAmounts(draft({ expectedVersion: 2, paymentAmount: '' }), [payment])
  assert.equal(blank.form.paymentAmount, '90.00')
})
test('saved OCR provenance may update its own candidate but never a manually touched zero', () => {
  const payment = evidence('p', 'PAYMENT', { paymentAmount: '0.00' })
  const first = reconcileEvidenceAmounts(draft(), [payment])
  const saved = { ...first, form: { ...first.form, expectedVersion: 2 } }
  const retry = evidence('p', 'PAYMENT', { paymentAmount: '90.00' })
  assert.equal(reconcileEvidenceAmounts(saved, [retry]).form.paymentAmount, '90.00')
  const manual = { ...saved, recognition: touchedRecognition(saved, ['paymentAmount']) }
  assert.equal(reconcileEvidenceAmounts(manual, [retry]).form.paymentAmount, '0.00')
})
test('failed or disabled OCR keeps original partial content, and PDF text remains compatible', () => {
  for (const status of ['DISABLED', 'UNAVAILABLE', 'FAILED', 'NO_CANDIDATES']) assert.equal(automaticCandidate(extraction({ status, invoiceNumber: '00000000000000123456' }), 'invoiceNumber'), '')
  assert.equal(automaticCandidate({ source: 'XML_TEXT', invoiceNumber: '00000000000000123456' }, 'invoiceNumber'), '00000000000000123456')
  assert.equal(automaticCandidate({ source: 'MANUAL_REQUIRED', amount: '80.00' }, 'amount'), '')
  const result = mergeInvoiceCandidates(invoice({ invoiceNumber: '123' }), extraction({ status: 'FAILED' }), [])
  assert.equal(result.invoiceNumber, '123')
})
test('ambiguous field candidates are offered separately, never stringified or automatically chosen', () => {
  const value = extraction({ fieldCandidates: { invoiceNumber: [{ value: '00000000000000123456', confidence: .8 }, { value: '00000000000000654321', confidence: .8 }], amount: [{ value: '0.001', confidence: 1 }] } })
  assert.equal(automaticCandidate(value, 'invoiceNumber'), '')
  assert.deepEqual(recognitionCandidates(value).map(candidate => [candidate.value, candidate.automatic]), [['00000000000000123456', false], ['00000000000000654321', false]])
  assert.equal(automaticCandidate(extraction({ invoiceNumber: ['00000000000000123456'] }), 'invoiceNumber'), '')
})
test('intake merges blank candidates without overwriting unsaved manual edits or auto-confirming', () => {
  const values = { invoiceNumber: '00000000000000999999', invoiceCode: '', amount: '90.00', buyerTaxNumber: '', confirmed: 'true' }
  const result = mergeIntakeCandidates(values, extraction({ invoiceNumber: '00000000000000123456', amount: '100.00', buyerTaxNumber: '91520000123456789X' }), [])
  assert.equal(result.invoiceNumber, values.invoiceNumber); assert.equal(result.amount, values.amount)
  assert.equal(result.buyerTaxNumber, '91520000123456789X'); assert.equal(result.confirmed, 'false')
  assert.deepEqual(mergeIntakeCandidates(values, extraction({ buyerTaxNumber: '91520000123456789X' }), ['buyerTaxNumber']), values)
})
test('intake retry accepts only the same business version and does not adopt another page update', () => {
  const draft = { version: 3, touched: ['amount'], values: { invoiceNumber: '', invoiceCode: '', amount: '90.00', buyerTaxNumber: '', confirmed: 'false' } }
  const candidate = extraction({ invoiceNumber: '00000000000000123456', amount: '100.00', buyerTaxNumber: '91520000123456789X' })
  const same = acceptIntakeRecognition(draft, 3, candidate, 'RETRY')
  assert.equal(same.conflict, false); assert.equal(same.draft.version, 3)
  assert.equal(same.draft.values.amount, '90.00'); assert.equal(same.draft.values.invoiceNumber, '00000000000000123456')
  for (const version of [2, 4, 5, null, undefined, 'invalid']) {
    const result = acceptIntakeRecognition(draft, version, candidate, 'RETRY')
    assert.equal(result.conflict, true); assert.equal(result.draft, draft)
    assert.equal(result.draft.version, 3); assert.equal(result.draft.values.invoiceNumber, '')
    assert.equal(result.draft.values.amount, '90.00')
  }
})
test('first original upload may advance exactly one version, never silently catch up to another business edit', () => {
  const draft = { version: 3, touched: ['invoiceNumber'], values: { invoiceNumber: '00000000000000999999', invoiceCode: '', amount: '', buyerTaxNumber: '', confirmed: 'true' } }
  const candidate = extraction({ invoiceNumber: '00000000000000123456', amount: '100.00' })
  const accepted = acceptIntakeRecognition(draft, 4, candidate, 'UPLOAD')
  assert.equal(accepted.conflict, false); assert.equal(accepted.draft.version, 4)
  assert.equal(accepted.draft.values.invoiceNumber, draft.values.invoiceNumber)
  assert.equal(accepted.draft.values.amount, '100.00'); assert.equal(accepted.draft.values.confirmed, 'false')
  for (const version of [3, 5, 100, null]) {
    const result = acceptIntakeRecognition(draft, version, candidate, 'UPLOAD')
    assert.equal(result.conflict, true); assert.equal(result.draft, draft)
    assert.equal(result.draft.values.amount, ''); assert.equal(result.draft.values.confirmed, 'true')
  }
})
