import type { FinanceRow } from './unifiedModel'
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[a-f\d]{8}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{4}-[a-f\d]{12}$/i.test(value)
const cents = (value: unknown): bigint | null => { if (typeof value !== 'string' || !/^\d{1,16}(\.\d{1,2})?$/.test(value)) return null; const [w, f = ''] = value.split('.'); return BigInt(w) * 100n + BigInt(f.padEnd(2, '0')) }
export const taxSourceKey = (row: FinanceRow) => `${String(row.sourceKind)}:${String(row.invoiceId)}:${String(row.sourceParentId)}`
export function taxBasisRequest(source: FinanceRow, hotelId: string, v: Record<string, string>, requestId: string) {
 if (!uuid(source.invoiceId) || !uuid(source.sourceParentId) || source.hotelId !== hotelId || !['CLAIM', 'PROCUREMENT', 'PROCUREMENT_RED'].includes(String(source.sourceKind))) throw new Error('请选择本组织真实原票及原始事项，不能手填编号。')
 const net = cents(v.netAmount), tax = cents(v.taxAmount), gross = cents(String(source.grossAmount ?? '')), eligible = cents(v.eligibleTax)
 if (net === null || tax === null || gross === null || eligible === null || net + tax !== gross || eligible > tax) throw new Error('按实际原件拆分净额和税额，合计须等于原票；不能按含税金额猜税率。')
 if (!uuid(v.reviewerId) || source.sourceKind === 'PROCUREMENT_RED' && !uuid(source.redOfBasisId)) throw new Error('请选择独立配置审核人；红字须有真实蓝票税额确认。')
 return { requestId, hotelId, sourceKind: source.sourceKind, invoiceId: source.invoiceId, sourceParentId: source.sourceParentId, redOfBasisId: source.redOfBasisId ?? null, netAmount: v.netAmount, taxAmount: v.taxAmount, eligibleTax: v.eligibleTax, purpose: v.purpose, reviewerId: v.reviewerId, note: v.note }
}
export function taxDecisionRequest(basis: FinanceRow, v: Record<string, string>, requestId: string) {
 if (!uuid(basis.id) || basis.status !== 'CONFIRMED' || basis.documentKind !== 'BLUE') throw new Error('须先独立确认真实蓝票税额。')
 const c = cents(v.claimedTax), t = cents(v.transferOut), net = cents(String(basis.netClaimedTax ?? '')), ceiling = cents(String(basis.availableEligibleTax ?? ''))
 if (c === null || t === null || net === null || ceiling === null || c + t === 0n || t > net + c || net + c - t > ceiling) throw new Error('抵扣／转出须为精确金额，并在真实确认及红字后的余额内；缺失余额不能当零。')
 if (!uuid(v.policyId)) throw new Error('请选择本主体真实已发布政策。')
 return { requestId, basisId: basis.id, month: v.month, policyId: v.policyId, claimedTax: v.claimedTax, transferOut: v.transferOut, purpose: v.purpose, note: v.note }
}
export function taxAccrualRequest(source: FinanceRow, v: Record<string, string>, requestId: string) {
 if (!uuid(source.id) || typeof source.sourceHash !== 'string' || !/^[a-f\d]{64}$/i.test(source.sourceHash) || (cents(String(source.declaredAmount ?? '')) ?? 0n) <= 0n) throw new Error('须选择有实际应纳额和冻结版本的真实税务取数单。')
 if (!uuid(v.reviewerId)) throw new Error('请选择本组织配置的独立财务复核人。')
 return { requestId, returnId: source.id, sourceHash: source.sourceHash, amount: String(source.declaredAmount), taxKind: v.taxKind, accountingOn: v.accountingOn, reviewerId: v.reviewerId, note: v.note }
}
export function taxPaymentRequest(accrual: FinanceRow, receipt: FinanceRow, bank: FinanceRow, v: Record<string, string>, requestId: string) {
 if (!uuid(accrual.id) || accrual.status !== 'CONFIRMED' || accrual.posted !== true || typeof accrual.version !== 'number') throw new Error('先独立确认并正式过账原税费计提，不能把审批当已记账。')
 if (!uuid(receipt.id) || receipt.kind !== 'PAYMENT' || !uuid(bank.id) || bank.hotelId !== accrual.hotelId || bank.companyId !== accrual.companyId) throw new Error('请选择同主体实际完税回执及银行OUT流水，申报回执不是付款。')
 const amount = cents(v.amount), due = cents(String(accrual.outstandingAmount ?? ''))
 if (amount === null || amount <= 0n || due === null || amount > due) throw new Error('清偿不能超过实际净未付税款，余额缺失请刷新。')
 return { requestId, expectedVersion: accrual.version, taxReceiptId: receipt.id, bankTransactionId: bank.id, amount: v.amount, accountingOn: v.accountingOn, note: v.note }
}
