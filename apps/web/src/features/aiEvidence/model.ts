export type CriterionCode = 'UNIFORM' | 'BADGE' | 'EQUIPMENT'
export type Criterion = { code: CriterionCode; enabled: boolean; standard: string }
export type RuleConfig = { hotelIds: string[]; itemCodes: string[]; criteria: Criterion[]; exceptions: string; sampleIds: string[] }
export type Rule = { id: string; position_id: string; version_no: number; state: string; configuration: RuleConfig }
export type Option = { id: string; name: string }
export type Evidence = { id: string; original_name: string; media_type: string; sha256?: string; source?: 'AI' | 'WORK' | 'SAMPLE' }
export type Finding = { code: CriterionCode; status: string; observation: string; evidenceId: string; box?: number[] }
export type Check = {
  id: string; work_record_id: string; subject_assignment_id: string; subject_name?: string; position_name?: string;
  state: string; human_outcome: string; human_reason?: string; appeal_reason?: string; failure_code?: string;
  requested_by?: string; row_version: number; rule_version?: number; created_at: string;
  input_tokens?: number; output_tokens?: number; supersedes_id?: string; evidence: Evidence[];
  result: { overall?: string; checks?: Finding[] }; hotel_name?: string; business_date?: string;
}
export type ReviewContext = { subjects: (Option & { position_name: string; configured: boolean })[]; checks: Check[]; providerReady: boolean; attachments: Evidence[]; canSubmit: boolean; defaultSubjectId: string }
export const criterionNames: Record<CriterionCode, string> = { UNIFORM: '制服', BADGE: '工牌', EQUIPMENT: '规定装备' }
export const emptyConfig = (): RuleConfig => ({ hotelIds: [], itemCodes: [], criteria: [
  { code: 'UNIFORM', enabled: true, standard: '' }, { code: 'BADGE', enabled: true, standard: '' }, { code: 'EQUIPMENT', enabled: true, standard: '' },
], exceptions: '', sampleIds: [] })
export const statusText: Record<string, string> = {
  QUEUED: '等待检查', RUNNING: '正在检查', COMPLETE: '检查完成', UNAVAILABLE: '暂不可检查', FAILED: '检查未完成', STALE: '证据或结果待核对',
  PASS: '建议符合', CONCERN: '疑似不符合', UNKNOWN: '无法确认', PENDING: '待人工复核', RETAKE: '需要补拍', OVERTURNED: '误判已撤销',
}
export const failureText: Record<string, string> = {
  MODEL_NOT_CONFIGURED: '模型服务或外发审批尚未配置，请人工复核。',
  DAILY_LIMIT: '今日检查调用已达到限额，请人工复核或稍后重试。',
  IDENTITY_CHANGED: '原提交人的任职或权限已变化，请人工复核。',
  SUPERSEDED: '已有新的补拍版本，请查看最新检查。',
  WORKER_INTERRUPTED: '检查处理中断，可重试；不影响原工作提交时间。',
  PROVIDER_OR_EVIDENCE_UNAVAILABLE: '模型服务或图片暂不可用，请重试或人工复核。',
  EVIDENCE_OR_OUTPUT_INVALID: '证据已变化、过期或模型输出无法核对，请人工复核。',
}
export function latestChecks(checks: Check[]): Check[] {
  const superseded = new Set(checks.map(check => check.supersedes_id).filter(Boolean))
  return checks.filter(check => !superseded.has(check.id))
}
export function validateConfig(config: RuleConfig, positionId: string): string | undefined {
  if (!positionId) return '请选择适用岗位'
  if (!config.hotelIds.length) return '请选择至少一家门店'
  if (!config.itemCodes.length) return '请选择要检查的原工作项'
  if (!config.criteria.some(item => item.enabled)) return '至少启用一个检查项目'
  if (config.criteria.some(item => item.enabled && !item.standard.trim())) return '请填写启用项目的明确规范'
}
export const errorText = (error: unknown) => error instanceof Error ? error.message : '操作失败，请重试'
