import type { Evidence, EvidenceExtraction, EvidenceKind, Invoice, LocalDraft, RecognitionDraft } from './model'

export const recognitionFields: Record<string, string> = { invoiceNumber: '发票号码', invoiceCode: '发票代码', amount: '价税合计', buyerTaxNumber: '购买方税号', buyerName: '购买方名称', invoiceDate: '开票日期', transactionAmount: '交易记录金额', paymentAmount: '实际支付金额' }
const invoiceFields = ['invoiceNumber', 'invoiceCode', 'amount', 'buyerTaxNumber'] as const
export type InvoiceCandidateField = typeof invoiceFields[number]
export function recognitionState(draft: LocalDraft): RecognitionDraft { return draft.recognition ?? { touched: [], amounts: {} } }
export function touchedRecognition(draft: LocalDraft, fields: string[]): RecognitionDraft {
  const current = recognitionState(draft)
  return { ...current, touched: [...new Set([...current.touched, ...fields])] }
}
export function extractionValue(extraction: EvidenceExtraction | undefined, field: string): string {
  if (!extraction) return ''
  const value = extraction[field as keyof EvidenceExtraction] ?? extraction.candidates?.[field]
  return typeof value === 'string' ? value.trim() : ''
}
export function validRecognitionValue(field: string, value: string): boolean {
  if (['amount', 'transactionAmount', 'paymentAmount'].includes(field)) return /^\d{1,14}(\.\d{1,2})?$/.test(value)
  if (field === 'invoiceNumber') return /^(\d{8}|\d{20})$/.test(value)
  if (field === 'invoiceCode') return /^(\d{10}|\d{12})$/.test(value)
  if (field === 'buyerTaxNumber') return /^[A-Za-z0-9]{8,32}$/.test(value)
  return value.length > 0 && value.length <= 200
}
export function automaticCandidate(extraction: EvidenceExtraction | undefined, field: string): string {
  if (!extraction || (extraction.status && !['CANDIDATES', 'SUCCEEDED', 'REVIEW_REQUIRED'].includes(extraction.status))) return ''
  if (extraction.source === 'MANUAL_REQUIRED') return ''
  const value = extractionValue(extraction, field)
  return validRecognitionValue(field, value) ? value : ''
}
export function recognitionCandidates(extraction: EvidenceExtraction | undefined): { field: string; label: string; value: string; automatic: boolean }[] {
  if (!extraction) return []
  return Object.entries(recognitionFields).flatMap(([field, label]) => {
    const automatic = automaticCandidate(extraction, field)
    const values = [...new Set([extractionValue(extraction, field), ...(extraction.fieldCandidates?.[field] ?? []).map(value => value.value)].filter(value => typeof value === 'string' && validRecognitionValue(field, value)))]
    return values.map(value => ({ field, label, value, automatic: value === automatic }))
  })
}
export function mergeInvoiceCandidates(invoice: Invoice, extraction: EvidenceExtraction | undefined, touched: string[]): Invoice {
  let changed = false
  const next = { ...invoice }
  for (const field of invoiceFields) {
    const value = automaticCandidate(extraction, field)
    if (!next[field].trim() && value && !touched.includes(`invoice:${invoice.id}:${field}`)) { next[field] = value; changed = true }
  }
  return changed ? { ...next, confirmed: false } : invoice
}
export type IntakeCandidateValues = { invoiceNumber: string; invoiceCode: string; amount: string; buyerTaxNumber: string; confirmed: string }
export function mergeIntakeCandidates(values: IntakeCandidateValues, extraction: EvidenceExtraction | undefined, touched: string[]): IntakeCandidateValues {
  const next = { ...values }; let changed = false
  for (const field of invoiceFields) {
    const value = automaticCandidate(extraction, field)
    if (!next[field].trim() && value && !touched.includes(field)) { next[field] = value; changed = true }
  }
  return changed ? { ...next, confirmed: 'false' } : values
}
export type IntakeRecognitionDraft = { values: IntakeCandidateValues; version: number; touched: string[] }
export function acceptIntakeRecognition(draft: IntakeRecognitionDraft, returnedVersion: unknown, extraction: EvidenceExtraction | undefined, operation: 'RETRY' | 'UPLOAD'): { draft: IntakeRecognitionDraft; conflict: boolean } {
  const version = typeof returnedVersion === 'number' ? returnedVersion : typeof returnedVersion === 'string' && /^\d+$/.test(returnedVersion) ? Number(returnedVersion) : Number.NaN
  const expected = draft.version + (operation === 'UPLOAD' ? 1 : 0)
  // A retry appends OCR evidence only. A different business version belongs to another edit, never this request.
  if (!Number.isSafeInteger(version) || !Number.isSafeInteger(draft.version) || draft.version < 0 || version !== expected) return { draft, conflict: true }
  return { draft: { ...draft, version, values: mergeIntakeCandidates(draft.values, extraction, draft.touched) }, conflict: false }
}
// Multiple documents can describe one payment or several payments. Do not infer a sum or take the last upload.
export function reconcileEvidenceAmounts(draft: LocalDraft, evidence: Pick<Evidence, 'id' | 'kind' | 'extraction'>[]): LocalDraft {
  const recognition = recognitionState(draft)
  const form = { ...draft.form }; const amounts = { ...recognition.amounts }
  for (const [kind, field] of [['TRANSACTION', 'transactionAmount'], ['PAYMENT', 'paymentAmount']] as const) {
    if (recognition.touched.includes(field)) continue
    const documents = evidence.filter(item => item.kind === kind)
    const applied = amounts[field]
    if (documents.length !== 1) {
      if (applied && form[field] === applied.value) form[field] = ''
      delete amounts[field]
      continue
    }
    const document = documents[0]; const value = automaticCandidate(document.extraction, field)
    // A saved zero is also a cloud fact. Only a new draft's default zero or our own candidate is replaceable.
    const replaceable = !form[field].trim() || (form.expectedVersion === 0 && /^0+(?:\.0{1,2})?$/.test(form[field])) || applied?.value === form[field]
    if (value && replaceable) { form[field] = value; amounts[field] = { attachmentId: document.id, value } }
  }
  return { ...draft, form, recognition: { ...recognition, amounts } }
}
export function applyEvidenceRecognition(draft: LocalDraft, attached: Evidence, evidence: Pick<Evidence, 'id' | 'kind' | 'extraction'>[], invoiceId: string): LocalDraft {
  const recognition = recognitionState(draft)
  let invoices = draft.form.invoices
  if (attached.kind === 'INVOICE') {
    const existing = invoices.find(invoice => invoice.attachmentId === attached.id)
    const candidate: Invoice = existing ?? { id: invoiceId, attachmentId: attached.id, invoiceCode: '', invoiceNumber: '', amount: '', buyerTaxNumber: '', confirmed: false }
    const merged = mergeInvoiceCandidates(candidate, attached.extraction, recognition.touched)
    invoices = existing ? invoices.map(invoice => invoice.id === existing.id ? merged : invoice) : invoices.length < 30 ? [...invoices, merged] : invoices
  }
  return reconcileEvidenceAmounts({ ...draft, form: { ...draft.form, invoices }, dirty: true }, evidence)
}
export function emptyRecognition(kind: EvidenceKind): EvidenceExtraction { return { source: 'MANUAL_REQUIRED', notice: `${kind === 'INVOICE' ? '发票' : '凭证'}尚未识别`, confirmationRequired: true } }
