import { useEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import type { RoleContext } from '../../domain'
import { apiRequest } from '../../api/client'
import { useScopedResource } from '../../shared/useScopedResource'
import { criterionNames, errorText } from './model'
import type { RuleReference } from './model'
import { EvidencePhoto } from './EvidencePhoto'
import './aiEvidence.css'

type Props = { identity: RoleContext; recordId?: string; itemCode?: string; preferredRuleId?: string; label?: string }
export function AiRuleReference(props: Props) {
  return <ReferenceEntry key={`${props.identity.key}:${props.recordId ?? ''}:${props.itemCode ?? ''}`} {...props} />
}
function ReferenceEntry(props: Props) {
  const [open, setOpen] = useState(false)
  return <><button type="button" className="secondary" onClick={() => setOpen(true)}>{props.label ?? '查看执行标准'}</button>
    {open && <ReferenceDialog {...props} onClose={() => setOpen(false)} />}</>
}
function ReferenceDialog({ identity, recordId, itemCode, preferredRuleId, onClose }: Props & { onClose: () => void }) {
  const resource = useScopedResource<RuleReference[]>(`ai-reference:${identity.key}:${recordId ?? 'mine'}`,
    signal => apiRequest(recordId ? `/ai-evidence/records/${recordId}/rules` : '/ai-evidence/my-rules', identity, { signal }), [])
  const [selected, setSelected] = useState(preferredRuleId ?? '')
  const panel = useRef<HTMLElement>(null)
  const rules = resource.data.filter(rule => !itemCode || rule.itemCodes.includes(itemCode))
  const current = rules.find(rule => rule.id === selected) ?? rules.find(rule => rule.state === 'SHADOW' && rule.subjectScope === 'WORK_PARTICIPANTS') ?? rules[0]
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    panel.current?.focus()
    return () => { previous?.focus() }
  }, [])
  return createPortal(<div className="ai-modal ai-reference-modal"><section ref={panel} tabIndex={-1} className="ai-card ai-rule-reference" role="dialog" aria-modal="true" aria-label="仪容仪表执行标准" onKeyDown={event => {
    if (event.key === 'Escape') { event.stopPropagation(); onClose() }
    if (event.key === 'Tab') {
      const controls = [...(panel.current?.querySelectorAll<HTMLElement>('button:not(:disabled),select:not(:disabled),summary,[tabindex="0"]') ?? [])].filter(el => el.getClientRects().length)
      const first = controls[0], last = controls.at(-1)
      if (event.shiftKey && (document.activeElement === first || document.activeElement === panel.current)) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
    }
  }}>
    <header className="ai-heading"><div><h2>仪容仪表规则</h2><span className="ai-pill">只读执行标准</span></div><button type="button" className="secondary" onClick={onClose}>返回工作</button></header>
    {resource.loading && <p role="status">正在读取适用规则…</p>}
    {!!resource.error && <div className="ai-error" role="alert">{errorText(resource.error)}<button type="button" className="secondary" onClick={() => void resource.reload()}>重新读取</button></div>}
    {!resource.loading && !resource.error && !rules.length && <p className="ai-empty">当前任职或这项工作尚未配置仪容仪表标准，请联系管理员。此处不会显示其他门店的规则。</p>}
    {!resource.loading && !resource.error && current && <>
      {rules.length > 1 && <label>查看规则版本<select value={current.id} onChange={event => setSelected(event.target.value)}>{rules.map(rule => <option key={rule.id} value={rule.id}>{rule.position_name} · V{rule.version_no} · {rule.state === 'SHADOW' ? '当前标准' : '历史检查标准'}</option>)}</select></label>}
      <div className="ai-banner"><strong>{current.subjectScope === 'WORK_PARTICIPANTS' ? '适用：本门店本次参与员工，不限岗位' : `适用岗位：${current.position_name}`}</strong><p>{current.hotels.map(h => h.name).join('、')} · 执行负责人：{current.position_name}</p><p>规则 V{current.version_no}{current.state === 'SHADOW' ? ' · 当前生效' : ' · 历史版本，仅供本次检查追溯'}</p>
        {current.sourceRuleVersion && <p>标准来源：{current.source_position_name ?? '店长'}规则 V{current.sourceRuleVersion} · 独立版本，不随源规则自动改写</p>}</div>
      <dl className="ai-reference-standards">{current.criteria.filter(criterion => criterion.enabled).map(criterion => <div key={criterion.code}><dt>{criterionNames[criterion.code]}</dt><dd>{criterion.standard}</dd></div>)}</dl>
      <section><h3>允许例外</h3><p className="ai-reference-text">{current.exceptions || '暂无配置；有特殊情况请由负责人说明并人工核实，不由 AI 推断。'}</p></section>
      <section><h3>规范样例 · 永久保留</h3>{current.samples.length ? <div className="ai-evidence-grid">{current.samples.map(sample => <EvidencePhoto key={`${current.id}:${sample.id}`} item={sample} identity={identity}
        contentPath={`/ai-evidence/rules/${current.id}/samples/${sample.id}/content${recordId ? `?recordId=${encodeURIComponent(recordId)}` : ''}`} />)}</div> : <p className="ai-muted">本版本未关联规范样例</p>}</section>
      <p className="ai-warning">由提交人确认照片对应人员，不使用人脸识别匹配姓名。模糊、遮挡或无法判断时需人工复核，不直接判定违规。</p>
      <p className="ai-muted">{current.pilotEnabled ? '本门店在已批准的 AI 试点范围，需人工选择照片发起检查。' : '本门店可查看执行标准，尚未开放照片外发 AI 检查。'} 不自动退回工作，不自动扣绩效。</p>
    </>}
  </section></div>, document.body)
}
