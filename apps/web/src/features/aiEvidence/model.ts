export type CriterionCode = 'UNIFORM' | 'BADGE' | 'EQUIPMENT' | 'MAKEUP'
export type Criterion = { code: CriterionCode; enabled: boolean; standard: string }
export type RuleConfig = { hotelIds: string[]; itemCodes: string[]; criteria: Criterion[]; exceptions: string; sampleIds: string[]; subjectScope?: 'POSITION' | 'WORK_PARTICIPANTS'; sourceRuleId?: string; sourceRuleVersion?: number }
export type Rule = { id: string; position_id: string; version_no: number; state: string; configuration: RuleConfig }
export type Option = { id: string; name: string }
export type Evidence = { id: string; original_name: string; media_type: string; sha256?: string; source?: 'AI' | 'WORK' | 'SAMPLE' }
export type Finding = { code: CriterionCode; status: string; observation: string; evidenceId: string; box?: number[] }
export type Check = {
  id: string; work_record_id: string; subject_assignment_id: string; subject_name?: string; position_name?: string;
  state: string; human_outcome: string; human_reason?: string; appeal_reason?: string; failure_code?: string;
  requested_by?: string; row_version: number; rule_id?: string; rule_version?: number; created_at: string;
  input_tokens?: number; output_tokens?: number; supersedes_id?: string; evidence: Evidence[];
  result: { overall?: string; checks?: Finding[] }; hotel_name?: string; business_date?: string;
}
export type ReviewContext = { subjects: (Option & { position_name: string; configured: boolean })[]; checks: Check[]; providerReady: boolean; attachments: Evidence[]; canSubmit: boolean; defaultSubjectId: string; pilotEnabled?: boolean; participantScope?: boolean }
export type RuleReference = { id: string; version_no: number; state: string; position_name: string; source_position_name?: string; sourceRuleVersion?: number; subjectScope: 'POSITION' | 'WORK_PARTICIPANTS'; criteria: Criterion[]; exceptions: string; samples: Evidence[]; itemCodes: string[]; hotels: Option[]; pilotEnabled: boolean }
export const criterionNames: Record<CriterionCode, string> = { UNIFORM: '制服', BADGE: '工牌', EQUIPMENT: '规定装备', MAKEUP: '妆容' }
export const emptyConfig = (): RuleConfig => ({ hotelIds: [], itemCodes: [], criteria: [
  { code: 'UNIFORM', enabled: true, standard: '' }, { code: 'BADGE', enabled: true, standard: '' }, { code: 'EQUIPMENT', enabled: true, standard: '' },
  { code: 'MAKEUP', enabled: false, standard: '' },
], exceptions: '', sampleIds: [] })
/** Old rules remain unchanged: newly available criteria must be explicitly enabled. */
export function normalizeConfig(config?: RuleConfig): RuleConfig {
  if (!config) return emptyConfig()
  return { ...config, hotelIds: [...config.hotelIds], itemCodes: [...config.itemCodes],
    sampleIds: [...(config.sampleIds ?? [])], exceptions: config.exceptions ?? '',
    criteria: emptyConfig().criteria.map(item => ({ ...(config.criteria.find(c => c.code === item.code) ?? { ...item, enabled: false }) })),
  }
}
export function withSavedRule(rules: Rule[], saved: Rule): Rule[] {
  return [saved, ...rules.filter(rule => rule.id !== saved.id).map(rule =>
    saved.state === 'SHADOW' && rule.state === 'SHADOW' ? { ...rule, state: 'RETIRED' } : rule)]
}
export function ruleStatus(rules: Rule[], dirty: boolean) {
  const latest = rules[0], active = rules.find(rule => rule.state === 'SHADOW')
  const activeText = active ? `V${active.version_no}` : '暂无'
  if (dirty) return { tone: 'warning', title: '有未保存修改', detail: `修改尚未保存；线上生效版本：${activeText}。保存草稿不会启用新修改。` }
  if (latest?.state === 'DRAFT') return { tone: 'warning', title: `草稿 V${latest.version_no} · 尚未启用`,
    detail: `${active ? `线上仍使用 ${activeText}。` : '规则已保存。'}点击“保存并启用 AI 检查”后生效。` }
  if (active) return { tone: 'success', title: `已启用 ${activeText} · 等待选图检查`, detail: '规则已生效；仅处理人工选定的照片，不追溯扫描历史照片。' }
  return { tone: 'warning', title: '尚未启用 AI 检查', detail: '请选择岗位，填写并保存明确规范后启用。' }
}
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
