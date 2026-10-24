import test from 'node:test'
import assert from 'node:assert/strict'
import { policySpec } from '../src/features/finance/controlModel.ts'
import { draftFromClaim } from '../src/features/finance/model.ts'
test('budget freezes actual category ID/code and explicit project instead of inferring identifiers', () => {
 const spec = policySpec('BUDGET', { month:'2026-10', amount:'123.45', categoryId:'actual-random-category', categoryCode:'CLEAN_COST', projectKind:'CONTRACT', projectId:'actual-approved-contract', budgetKind:'EXPENSE', control:'BLOCK' })
 assert.equal(spec.categoryId,'actual-random-category'); assert.equal(spec.categoryCode,'CLEAN_COST'); assert.equal(spec.projectKind,'CONTRACT'); assert.equal(spec.projectId,'actual-approved-contract')
 const total = policySpec('BUDGET', { categoryId:'same-real-id', categoryCode:'CLEAN_COST' })
 assert.equal(total.projectKind,'ALL'); assert.equal(total.projectId,null); assert.equal(total.categoryId,'same-real-id')
 const none = policySpec('BUDGET', { categoryCode:'CLEAN_COST', projectKind:'NONE' })
 assert.equal(none.projectKind,'NONE'); assert.equal(none.categoryId,null)
})
test('claim draft roundtrip keeps selected real project but does not invent missing history', () => {
 const base = {version:2,title:'真实费用',business_date:'2026-10-24',category_id:'real',transaction_amount:'10',payment_amount:'10',requested_amount:'10',difference_note:'',invoices:[]}
 const form = draftFromClaim({...base,items:[{id:'line',quantity:'1',unitPrice:'10',amount:'10',invoiceIds:[],projectKind:'CONTRACT',projectId:'actual-contract',projectSnapshot:{name:'实际合同',sourceVersion:5},categoryCode:'REAL'}]})
 assert.equal(form.items[0].projectId,'actual-contract'); assert.equal(form.items[0].projectSnapshot.sourceVersion,5); assert.equal(form.items[0].categoryCode,'REAL')
 const historical = draftFromClaim({...base,items:[{id:'old',quantity:'1',unitPrice:'10',amount:'10',invoiceIds:[]}]})
 assert.equal(historical.items[0].projectSnapshot,undefined)
})
