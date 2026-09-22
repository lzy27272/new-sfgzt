import assert from 'node:assert/strict'
import { test } from 'node:test'
import { configurationFromStore, configurationIssues } from '../src/features/finance/approvalConfiguration.ts'
const accounts = ['initial','finance','director','ceo','cashier'].map(id => ({ id, display_name: id }))
const store = { hotel_id: 'group', hotel_name: '集团总部', company_name: '示例公司', tax_number: '91520115MAAK3E4B34' }
test('company can be saved with no accounts but not enabled; CEO name does not bind an account', () => {
  const form = configurationFromStore(store)
  assert.deepEqual(configurationIssues(form, accounts, false), [])
  assert.equal(configurationIssues(form, accounts, true).length, 5)
  assert.equal(form.leaderName, '刘照阳'); assert.equal(form.leaderAccountId, null)
})
test('activation requires distinct active four-stage reviewers and a cashier', () => {
  const form = { ...configurationFromStore(store), managerAccountId: 'initial', financeAccountId: 'finance', directorAccountId: 'director', leaderAccountId: 'ceo', cashierAccountId: 'cashier' }
  assert.deepEqual(configurationIssues(form, accounts, true), [])
  assert.ok(configurationIssues({ ...form, directorAccountId: 'finance' }, accounts, true).includes('四段审核必须配置不同账号'))
  assert.ok(configurationIssues({ ...form, cashierAccountId: 'deleted' }, accounts, true).some(e => e.includes('已停用')))
})
test('pending draft is loaded without claiming it is published and current optimistic version wins', () => {
  const pending = { ...configurationFromStore(store), companyName: '草稿公司', expectedVersion: 3 }
  const form = configurationFromStore({ ...store, enabled: true, version: 4, draft: pending })
  assert.equal(form.companyName, '草稿公司'); assert.equal(form.expectedVersion, 4); assert.equal(form.enabled, false)
})
