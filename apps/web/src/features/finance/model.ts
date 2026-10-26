import type { ApiIdentity } from '../../api/client'

export type EvidenceKind = 'INVOICE' | 'TRANSACTION' | 'PAYMENT'
export const evidenceLabels: Record<EvidenceKind, string> = { INVOICE: '发票', TRANSACTION: '交易记录', PAYMENT: '付款凭证' }
export const statusLabels: Record<string, string> = { DRAFT: '草稿', RETURNED: '退回补充', MANAGER_REVIEW: '待初审', FINANCE_REVIEW: '待门店财务复核', DIRECTOR_REVIEW: '待财务总监审核', LEADER_REVIEW: '待CEO终审', APPROVED: '审批通过', REJECTED: '已拒绝', WITHDRAWN: '已撤回' }
export function approvalStageLabel(stage: string, workflowVersion = 2): string {
  const labels: Record<string, string> = workflowVersion === 1
    ? { MANAGER_REVIEW: '门店负责人', FINANCE_REVIEW: '财务', LEADER_REVIEW: '集团领导' }
    : { MANAGER_REVIEW: '初审', FINANCE_REVIEW: '门店财务', DIRECTOR_REVIEW: '财务总监', LEADER_REVIEW: 'CEO终审' }
  return labels[stage] ?? stage
}
export function claimStatusLabel(claim: { status: string; workflow_version?: number }): string {
  if (claim.workflow_version === 1 && ['MANAGER_REVIEW', 'FINANCE_REVIEW', 'LEADER_REVIEW'].includes(claim.status)) return `待${approvalStageLabel(claim.status, 1)}审核`
  return statusLabels[claim.status] ?? claim.status
}
export function canWithdrawClaim(status: string): boolean {
  return ['DRAFT', 'RETURNED', 'MANAGER_REVIEW', 'FINANCE_REVIEW', 'DIRECTOR_REVIEW', 'LEADER_REVIEW'].includes(status)
}
export const treatmentLabels: Record<string, string> = { EXPENSE: '当期费用', INVENTORY: '库存采购', ASSET: '固定资产', PREPAID: '预付 / 待摊', ALREADY_RECORDED: '已记成本（不重复计入）' }
export type Category = { id: string; hotel_id: string; name: string; treatment: string; keywords: string; debit_account: string; enabled: boolean; version: number; budget_code?: string | null }
export type FinanceContext = {
  today: string; assignments: { assignment_id: string; employee_name: string; hotel_id: string; hotel_name: string; company_name?: string; company_tax_number?: string; enabled: boolean }[]
  hotels: { id: string; name: string }[]; categories: Category[]
}
export type Evidence = { id: string; kind: EvidenceKind; original_name: string; media_type: string; size_bytes: number; sha256: string; extraction: { source: string; notice: string; invoiceNumber?: string; invoiceCode?: string; amount?: string } }
export type Invoice = { id: string; attachmentId: string; invoiceCode: string; invoiceNumber: string; amount: string; buyerTaxNumber: string; confirmed: boolean }
export type ClaimLine = { id: string; categoryId: string | null; description: string; quantity: string; unitPrice: string; amount: string; departmentId?: string | null; allocationMonth?: string | null; invoiceIds: string[]; projectKind?: string; projectId?: string | null; projectSnapshot?: Record<string, unknown>; categoryCode?: string | null }
export type DraftForm = { expectedVersion: number; title: string; businessDate: string; categoryId: string | null; transactionAmount: string; paymentAmount: string; requestedAmount: string; differenceNote: string; invoices: Invoice[]; items?: ClaimLine[] }
export type PendingEvidence = { id: string; kind: EvidenceKind; file: File }
export type LocalDraft = { form: DraftForm; files: PendingEvidence[]; dirty: boolean }
export type ReviewRecord = { stage: string; decision: string; note: string; created_at: string; reviewer_name: string; review_snapshot?: {
  claim: { title: string; requested_amount: string; approved_amount: string | null; category_name: string; workflow_version?: number }
  evidence: { id: string; kind: EvidenceKind; original_name: string; sha256: string }[]
  invoices: { invoice_code: string; invoice_number: string; amount: string; buyer_tax_number: string }[]
  items?: ClaimLine[]
} }
export type Claim = {
  workflow_version?: number; submitted_at?: string; approvalRoute?: { stage: string; label: string; accountId: string | null; accountName: string; skipped: boolean }[];
  id: string; version: number; status: string; assignment_id: string; hotel_id: string; applicant_name: string; hotel_name: string; company_name: string; company_tax_number: string;
  title: string; business_date: string; category_id: string | null; category_name: string; treatment: string; invoice_amount: string; transaction_amount: string; payment_amount: string; requested_amount: string; approved_amount: string | null; difference_note: string; mismatch: boolean; updated_at: string;
  canEdit: boolean; canReview: boolean; canPay: boolean; attachments: Evidence[];
  items?: ClaimLine[]; configurationIssues?: string[]; paidAmount?: string; unpaidAmount?: string; canArchiveReceipts?: boolean;
  invoices: { id: string; attachment_id: string; invoice_code: string; invoice_number: string; amount: string; buyer_tax_number: string; confirmed: boolean }[];
  reviews: ReviewRecord[];
  payments: { id?: string; amount: string; reference: string; paid_on: string; receipt_archived?: boolean; settlement_kind?: 'CASH' | 'LOAN_OFFSET'; receipt_status?: string }[];
  classificationSuggestion: { source: string; categoryId?: string; categoryName?: string };
}
export function draftFromClaim(c: Claim): DraftForm {
  return { expectedVersion: c.version, title: c.title, businessDate: c.business_date, categoryId: c.category_id, transactionAmount: String(c.transaction_amount), paymentAmount: String(c.payment_amount), requestedAmount: String(c.requested_amount), differenceNote: c.difference_note,
    invoices: c.invoices.map(i => ({ id: i.id, attachmentId: i.attachment_id, invoiceCode: i.invoice_code, invoiceNumber: i.invoice_number, amount: i.amount == null ? '' : String(i.amount), buyerTaxNumber: i.buyer_tax_number, confirmed: i.confirmed })), items: (c.items ?? []).map(item => ({ ...item, quantity: item.quantity == null ? '' : String(item.quantity), unitPrice: item.unitPrice == null ? '' : String(item.unitPrice), amount: item.amount == null ? '' : String(item.amount), invoiceIds: item.invoiceIds ?? [] })) }
}
export function financeScope(identity: ApiIdentity): string {
  return `finance:v1:${identity.tenantId}:${identity.actorId}:${identity.businessActorAssignmentId ?? 'account'}`
}
export function cents(value: string): bigint | null {
  if (!/^\d{1,14}(\.\d{1,2})?$/.test(value)) return null
  const [whole, fraction = ''] = value.split('.')
  return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0'))
}
export function money(value: string | number | null | undefined): string {
  const [whole, fraction = ''] = String(value ?? '0').split('.')
  return `¥${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${fraction.padEnd(2, '0').slice(0, 2)}`
}
export function invoiceTotal(invoices: Invoice[]): string {
  const total = invoices.reduce((sum, invoice) => sum + (cents(invoice.amount) ?? 0n), 0n)
  return `${total / 100n}.${String(total % 100n).padStart(2, '0')}`
}
export function claimLineAmount(quantity: string, unitPrice: string): string | null {
  const scaled = (value: string): bigint | null => {
    if (!/^\d{1,14}(\.\d{1,4})?$/.test(value)) return null
    const [whole, fraction = ''] = value.split('.')
    return BigInt(whole) * 10000n + BigInt(fraction.padEnd(4, '0'))
  }
  const q = scaled(quantity); const price = scaled(unitPrice)
  if (q === null || price === null) return null
  // Both inputs have four decimal places. Round the eight-place product HALF_UP to cents.
  const result = (q * price + 500000n) / 1000000n
  return `${result / 100n}.${String(result % 100n).padStart(2, '0')}`
}
export function differs(form: DraftForm): boolean {
  const amounts = [invoiceTotal(form.invoices), form.transactionAmount, form.paymentAmount, form.requestedAmount].map(cents)
  return amounts.some(a => a === null || a !== amounts[0])
}
export function draftValidation(form: DraftForm): string | undefined {
  if ([form.transactionAmount, form.paymentAmount, form.requestedAmount].some(v => cents(v) === null)) return '金额请填写非负数字，最多两位小数'
  for (const i of form.invoices) {
    const number = i.invoiceNumber.normalize('NFKC').replace(/\s/g, '')
    const code = i.invoiceCode.normalize('NFKC').replace(/\s/g, '')
    if (!((/^\d{20}$/.test(number) && !code) || (/^\d{8}$/.test(number) && /^(\d{10}|\d{12})$/.test(code)))) return '请补全发票号：数电20位；传统8位号码加10/12位代码。未填完的内容仍可保存在本机。'
    if (!i.buyerTaxNumber.trim() || (cents(i.amount) ?? 0n) <= 0n) return '请补全发票金额及购买方税号；未填完的内容仍可保存在本机。'
  }
}
