import type { EvidenceExtraction, EvidenceKind } from './model'
import { recognitionCandidates } from './recognitionModel'
import s from './finance.module.css'
import o from './recognition.module.css'

const statuses: Record<string, string> = { CANDIDATES: '已识别候选，待人工核对', SUCCEEDED: '已识别候选，待人工核对', REVIEW_REQUIRED: '候选有歧义，需人工核对', NO_CANDIDATES: '未识别到完整字段', DISABLED: '本地识别未启用', UNAVAILABLE: '识别服务暂不可用', FAILED: '本次识别未完成' }
export function RecognitionUploadAction({ pending, busy, ready, online, conflict, onUpload }: { pending: number; busy: boolean; ready: boolean; online: boolean; conflict: boolean; onUpload: () => void }) {
  return pending > 0 ? <div className={s.actions}><button type="button" disabled={busy || !ready || !online || conflict} onClick={onUpload}>上传并识别凭证</button><small className={s.muted}>{pending} 份待上传；只保存草稿，不会自动提交审核。</small></div> : null
}
export function RecognitionReview({ extraction, kind = 'INVOICE', retrying, onRetry, onUse, disabled }: { extraction?: EvidenceExtraction; kind?: EvidenceKind; retrying?: boolean; onRetry?: () => void; onUse?: (field: string, value: string) => void; disabled?: boolean }) {
  const candidates = recognitionCandidates(extraction).filter(candidate => kind === 'INVOICE' ? !['transactionAmount', 'paymentAmount'].includes(candidate.field) : candidate.field === (kind === 'TRANSACTION' ? 'transactionAmount' : 'paymentAmount'))
  const usable = kind === 'INVOICE' ? ['invoiceNumber', 'invoiceCode', 'amount', 'buyerTaxNumber'] : [kind === 'TRANSACTION' ? 'transactionAmount' : 'paymentAmount']
  const status = extraction?.status ? statuses[extraction.status] ?? '识别结果待核对' : candidates.length ? '电子文件文字候选，待人工核对' : '待人工核对'
  return <section className={o.review} aria-label="原件识别结果">
    <p className={o.status} role="status">{retrying ? '正在重新识别已保存原件…' : status}{extraction?.source === 'LOCAL_OCR' ? ' · 本地识别' : ''}</p>
    <p className={s.muted}>{extraction?.notice || '原件已经保留。识别只提供候选，不代表号码已核对或税务验真。'}</p>
    {extraction?.similarityWarning && <p className={s.warning}>{extraction.similarityWarning} 相似度只提示人工核对，不据此自动拒绝。</p>}
    {(extraction?.warnings ?? []).filter(value => typeof value === 'string').map((warning, index) => <p className={s.warning} key={`${index}:${warning}`}>{warning}</p>)}
    {candidates.length > 0 && <dl className={o.candidates}>{candidates.map(candidate => <div key={`${candidate.field}:${candidate.value}`}><dt>{candidate.label}{!candidate.automatic && usable.includes(candidate.field) && <small> · 候选待选择</small>}</dt><dd><span>{candidate.value}</span>{onUse && usable.includes(candidate.field) ? <button type="button" disabled={disabled} onClick={() => onUse(candidate.field, candidate.value)} aria-label={`使用${candidate.label}候选 ${candidate.value}`}>使用候选</button> : <small>仅供对照原件</small>}</dd></div>)}</dl>}
    {onRetry && <button type="button" disabled={disabled || retrying} onClick={onRetry}>{retrying ? '识别中…' : '重新识别已保存原件'}</button>}
    <small className={s.muted}>无需重新选图。未识别字段可继续保存草稿或交财务核对；正式提交仍校验完整票号并执行数据库排重。</small>
  </section>
}
