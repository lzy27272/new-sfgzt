import assert from 'node:assert/strict'
import test from 'node:test'
import { canWithdrawClaim, cents, money, differs, invoiceTotal, financeScope, draftValidation } from '../src/features/finance/model.ts'
import { buildNavigationCenters, navigationCenterForSection } from '../src/app/navigationModel.ts'
import { applyPublishedModuleVisibility } from '../src/app/rolePresentationPolicy.ts'
const form = { expectedVersion: 0, title: '客房用品', businessDate: '2026-09-21', categoryId: null, transactionAmount: '100', paymentAmount: '90', requestedAmount: '90', differenceNote: '补贴', invoices: [{ id: 'i', attachmentId: 'a', invoiceCode: '', invoiceNumber: '00123456789012345678', buyerTaxNumber: '91520000123456789X', amount: '100', confirmed: true }] }

test('applicant may withdraw at each pending stage including director, never after final approval', () => {
  for (const status of ['DRAFT', 'RETURNED', 'MANAGER_REVIEW', 'FINANCE_REVIEW', 'DIRECTOR_REVIEW', 'LEADER_REVIEW']) assert.equal(canWithdrawClaim(status), true)
  for (const status of ['APPROVED', 'REJECTED', 'WITHDRAWN', 'UNKNOWN']) assert.equal(canWithdrawClaim(status), false)
})
test('finance keeps money as exact decimal strings including large values', () => {
  assert.equal(cents('99999999999999.99'), 9999999999999999n)
  assert.equal(cents('0.009'), null)
  assert.equal(cents('-1'), null)
  assert.equal(invoiceTotal([{ amount: '0.10' }, { amount: '0.20' }]), '0.30')
  assert.equal(money('99999999999999.99'), '¥99,999,999,999,999.99')
})
test('subsidy discrepancy is a warning, not invalid input', () => {
  assert.equal(differs(form), true)
  assert.equal(draftValidation(form), undefined)
  assert.equal(differs({ ...form, paymentAmount: '100', requestedAmount: '100.00' }), false)
})
test('incomplete or ambiguous invoice data cannot be silently cloud-saved', () => {
  assert.match(draftValidation({ ...form, invoices: [{ ...form.invoices[0], invoiceNumber: '12345678' }] }), /补全发票号/)
  assert.match(draftValidation({ ...form, invoices: [{ ...form.invoices[0], buyerTaxNumber: '' }] }), /税号/)
  assert.equal(draftValidation({ ...form, invoices: [{ ...form.invoices[0], invoiceNumber: '００１２３４５６７８９０１２３４５６７８' }] }), undefined)
})
test('offline scope is distinct for each tenant, account and assignment', () => {
  const a = { tenantId: 't1', actorId: 'u1', businessActorAssignmentId: 'p1' }
  const values = [a, { ...a, tenantId: 't2' }, { ...a, actorId: 'u2' }, { ...a, businessActorAssignmentId: 'p2' }].map(financeScope)
  assert.equal(new Set(values).size, 4)
})
test('finance has a dedicated center only when its module is explicitly available', () => {
  const features = [{ id: 'finance', label: '报销与费用', icon: '¥' }, { id: 'workbench', label: '工作台', icon: '' }]
  assert.equal(navigationCenterForSection('finance'), 'finance')
  assert.equal(buildNavigationCenters(features, '员工').find(x => x.id === 'finance').label, '财务中心')
  assert.deepEqual(applyPublishedModuleVisibility(features, ['ui.module.workbench'], 'GENERAL_MANAGER').map(x => x.id), ['workbench'])
  assert.deepEqual(applyPublishedModuleVisibility(features, ['ui.module.finance'], 'GENERAL_MANAGER').map(x => x.id), ['finance'])
})
