import { useState } from 'react'
import type { RoleContext } from '../../domain'
import { apiRequest } from '../../api/client'
import { useScopedResource } from '../../shared/useScopedResource'
import { criterionNames, errorText, normalizeConfig, ruleStatus, validateConfig, withSavedRule } from './model'
import type { Option, Rule, RuleConfig } from './model'
import { EvidencePhoto } from './AiEvidenceReview'
import './aiEvidence.css'

type Options = { hotels: Option[]; positions: Option[]; items: { code: string; name: string }[]; providerReady: boolean; dailyRequestLimit: number }
const emptyOptions: Options = { hotels: [], positions: [], items: [], providerReady: false, dailyRequestLimit: 0 }
type Props = { identity: RoleContext; onClose: () => void }
export function AiEvidenceAdmin(props: Props) {
  return <AdminScope key={props.identity.key} {...props} />
}
function AdminScope({ identity, onClose }: Props) {
  const options = useScopedResource(`ai-options:${identity.key}`, signal => apiRequest<Options>('/ai-evidence/options', identity, { signal }), emptyOptions)
  const [position, setPosition] = useState('')
  // A new role gets an isolated editor; late reads/saves cannot overwrite another role's form.
  return <RuleEditor key={position} identity={identity} onClose={onClose} position={position} setPosition={setPosition} options={options} />
}
function StateIcon({ success = false }: { success?: boolean }) {
  return <svg className="ai-state-icon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="11" fill="currentColor" />{success ? <path d="m6.5 12 3.6 3.6 7.4-7.2" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" /> : <path d="M12 6v7m0 4v.1" stroke="white" strokeWidth="2" strokeLinecap="round" />}</svg>
}
function RuleEditor({ identity, onClose, position, setPosition, options }: Props & {
  position: string; setPosition: (value: string) => void;
  options: ReturnType<typeof useScopedResource<Options>>;
}) {
  const versions = useScopedResource<Rule[]>(`ai-rules:${identity.key}:${position}`, signal => position ? apiRequest(`/ai-evidence/rules?positionId=${position}`, identity, { signal }) : Promise.resolve([]), [])
  const [draft, setDraft] = useState<RuleConfig | null>(null)
  const [busy, setBusy] = useState<'draft' | 'enable' | 'sample' | null>(null)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [needsRefresh, setNeedsRefresh] = useState(false)
  const [preview, setPreview] = useState(false)
  const [itemQuery, setItemQuery] = useState('')
  const [scopeExpanded, setScopeExpanded] = useState<boolean | null>(null)
  const [statusExpanded, setStatusExpanded] = useState(false)
  const latest = versions.data[0]
  const active = versions.data.find(rule => rule.state === 'SHADOW')
  const baseline = normalizeConfig(latest?.configuration)
  const config = draft ?? baseline
  const dirty = JSON.stringify(config) !== JSON.stringify(baseline)
  const loading = options.loading || versions.loading
  const readError = options.error || versions.error
  const disabled = !!busy || loading || !!readError || needsRefresh
  const status = ruleStatus(versions.data, dirty)
  const statusKnown = !loading && !readError && !needsRefresh
  const issue = validateConfig(config, position)
  const currentPosition = options.data.positions.find(item => item.id === position)?.name
  const update = (change: (current: RuleConfig) => RuleConfig) => { setDraft(change(config)); setMessage('') }
  const toggle = (key: 'hotelIds' | 'itemCodes', id: string) => update(current => ({ ...current, [key]: current[key].includes(id) ? current[key].filter(value => value !== id) : [...current[key], id] }))
  const refresh = () => {
    setError(''); setMessage(''); setNeedsRefresh(false)
    // Preserve unsaved form input while obtaining a fresh compare-and-swap base.
    void versions.reload()
    if (options.error) void options.reload()
  }
  const save = async (shadow: boolean) => {
    if (disabled) return
    if (issue) { setError(issue); return }
    setDraft(config); setBusy(shadow ? 'enable' : 'draft'); setError(''); setMessage('')
    try {
      const saved = await apiRequest<Rule>('/ai-evidence/rules', identity, { method: 'POST', body: JSON.stringify({ positionId: position, expectedLatestId: latest?.id ?? null, configuration: config, shadow }) })
      // The POST returns the persisted version. A follow-up failed GET must not erase a successful save.
      if (!saved?.id || saved.position_id !== position || !Number.isInteger(saved.version_no)
          || saved.state !== (shadow ? 'SHADOW' : 'DRAFT') || !saved.configuration) throw new Error('保存结果暂无法确认，请重新读取版本后核对，不要重复提交。')
      versions.setData(current => withSavedRule(current, saved))
      setDraft(null)
      setMessage(shadow ? `已保存并启用 V${saved.version_no}，等待原提交人选择照片发起检查；不会自动扫描历史照片。` : `草稿 V${saved.version_no} 已保存，尚未启用；${active ? `线上仍使用 V${active.version_no}` : '当前没有生效规则'}。`)
    } catch (reason) {
      setError(`${errorText(reason)} 请重新读取版本，核对后再保存；当前填写内容会保留。`)
      setNeedsRefresh(true)
    } finally { setBusy(null) }
  }
  const sample = async (kind: 'PASS' | 'CONCERN', file?: File) => {
    if (!file || !position || disabled) return
    setBusy('sample'); setError(''); setMessage('')
    try {
      const body = new FormData(); body.append('file', file); body.append('positionId', position); body.append('kind', kind)
      const result = await apiRequest<{ id: string }>('/ai-evidence/samples', identity, { method: 'POST', body })
      setDraft({ ...config, sampleIds: [...config.sampleIds, result.id] })
      setMessage('样例已上传；保存规则后关联，仍遵循现有图片保留期限。')
    } catch (reason) { setError(errorText(reason)) } finally { setBusy(null) }
  }
  return <section className="ai-page ai-admin" aria-label="AI检查规则配置">
    <div className="ai-breadcrumb">配置中心 / 岗位工作模板 / 仪容仪表</div>
    <header className="ai-heading"><div><h1>仪容仪表 · AI 检查规则</h1>{currentPosition && <p className="ai-mobile-position">{currentPosition}</p>}</div><button className="secondary" disabled={!!busy} onClick={onClose}>返回岗位工作模板</button></header>
    <div className="ai-banner">AI 提供检查建议，由主管人工复核；不自动退回或扣分</div>
    <div className={`ai-rule-status ai-${statusKnown ? status.tone : 'banner'}`} role="status">
      <StateIcon success={statusKnown && status.tone === 'success'} /><div><strong>{loading ? '正在读取规则…' : !statusKnown ? '规则状态待核对' : status.title}</strong><span>{statusKnown ? status.detail : '以服务器保存的版本为准；状态确认前不能保存或启用。'}</span></div>
    </div>
    {!!(error || readError) && <div className="ai-error" role="alert">{error || errorText(readError)}{(needsRefresh || !!readError) && <button className="secondary" disabled={!!busy || loading} onClick={refresh}>重新读取版本</button>}</div>}
    {message && <p className="ai-success" role="status">{message}</p>}
    <div className="ai-config-layout">
      <div className="ai-column">
        <section className={`ai-card ai-scope ${scopeExpanded ?? !active ? 'expanded' : ''}`}><h2>适用范围</h2>
          <button className="ai-scope-toggle" aria-expanded={scopeExpanded ?? !active} onClick={() => setScopeExpanded(!(scopeExpanded ?? !active))}>
            <strong>{options.data.hotels.filter(hotel => config.hotelIds.includes(hotel.id)).map(hotel => hotel.name).join(' · ') || '选择岗位与试点门店'}</strong><span>{scopeExpanded ?? !active ? '收起' : '调整范围'}</span>
          </button>
          <div className="ai-scope-fields">
            <label>适用岗位<select aria-label="适用岗位" value={position} disabled={!!busy || options.loading} onChange={e => setPosition(e.target.value)}><option value="">请选择岗位</option>{options.data.positions.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
            <fieldset><legend>适用门店</legend><div className="ai-choice-list">{options.data.hotels.map(hotel => <label className="ai-checkbox" key={hotel.id}><input type="checkbox" checked={config.hotelIds.includes(hotel.id)} disabled={disabled || !position} onChange={() => toggle('hotelIds', hotel.id)} />{hotel.name}</label>)}</div></fieldset>
            <fieldset><legend>关联原工作项</legend><input aria-label="搜索工作项" placeholder="搜索仪容仪表、晨会等工作" value={itemQuery} onChange={e => setItemQuery(e.target.value)} /><div className="ai-choice-list ai-scroll-options">{options.data.items.filter(item => item.name.includes(itemQuery) || config.itemCodes.includes(item.code)).map(item => <label className="ai-checkbox" key={`${item.code}:${item.name}`}><input type="checkbox" checked={config.itemCodes.includes(item.code)} onChange={() => toggle('itemCodes', item.code)} disabled={disabled || !position} />{item.name}</label>)}</div></fieldset>
          </div>
        </section>
        <section className="ai-card"><h2>检查项目</h2><p className="ai-muted">按门店已确认的规范填写，不由 AI 猜测。</p><div className="ai-rule-table">
          {config.criteria.map((item, index) => <div className={`ai-rule-row ${item.code === 'MAKEUP' ? 'ai-makeup-row' : ''}`} key={item.code}>
            <label className="ai-checkbox"><input type="checkbox" checked={item.enabled} disabled={disabled || !position} onChange={e => update(current => ({ ...current, criteria: current.criteria.map((c, i) => i === index ? { ...c, enabled: e.target.checked } : c) }))} />{criterionNames[item.code]}</label>
            <div className="ai-standard-field"><textarea aria-label={`${criterionNames[item.code]}规范`} value={item.standard} maxLength={1000} placeholder="填写已确认的岗位规范" disabled={disabled || !position || !item.enabled} onChange={e => update(current => ({ ...current, criteria: current.criteria.map((c, i) => i === index ? { ...c, standard: e.target.value } : c) }))} />
              {item.code === 'MAKEUP' && <small>需清晰正面近照；淡妆、模糊或遮挡不直接判为不合格</small>}</div>
          </div>)}
        </div>
          <p className="ai-warning ai-mobile-makeup-note">妆容看不清，不等于未化妆</p>
          <label>允许例外<textarea aria-label="允许例外" value={config.exceptions} maxLength={1000} placeholder="填写经门店确认的合理例外，不由 AI 推断" disabled={disabled || !position} onChange={e => update(current => ({ ...current, exceptions: e.target.value }))} /></label>
        </section>
        <section className="ai-card"><h2>规范样例</h2><div className="ai-sample-grid">{(['PASS', 'CONCERN'] as const).map(kind => <label className="ai-upload" key={kind}>
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 17V3m-5 5 5-5 5 5M5 17v4h14v-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
          <strong>上传{kind === 'PASS' ? '合格' : '不合格'}样例</strong><small>使用门店已确认的规范样例</small><input aria-label={`上传${kind === 'PASS' ? '合格' : '不合格'}样例`} type="file" accept="image/jpeg,image/png" disabled={!position || disabled || config.sampleIds.length >= 6} onChange={e => { void sample(kind, e.target.files?.[0]); e.currentTarget.value = '' }} /></label>)}</div>
          {!!config.sampleIds.length && <div className="ai-evidence-grid">{config.sampleIds.map((id, index) => <div key={id}><EvidencePhoto identity={identity} item={{ id, source: 'SAMPLE', media_type: 'image/jpeg', original_name: `规范样例 ${index + 1}` }} /><button type="button" className="secondary" disabled={disabled} onClick={() => update(current => ({ ...current, sampleIds: current.sampleIds.filter(value => value !== id) }))}>移除样例 {index + 1} 关联</button></div>)}</div>}
          <p className="ai-warning">样例用于对照规范，不用于识别人脸；沿用照片留存策略。到期后需重新提供。</p>
        </section>
        {active && statusKnown && <section className="ai-card ai-next-steps"><h2>下一步</h2><ol><li>原提交人在工作详情选择照片发起检查</li><li>主管在“仪容仪表复核”查看建议</li></ol></section>}
      </div>
      <aside className={`ai-admin-status ${statusExpanded ? 'expanded' : ''}`}>
        <button className="ai-status-toggle secondary" aria-expanded={statusExpanded} onClick={() => setStatusExpanded(!statusExpanded)}>{statusExpanded ? '收起状态与规则说明' : '查看状态与规则说明'}</button>
        <div className="ai-column ai-status-content">
        <section className="ai-card"><h2>当前状态</h2><p className={`ai-${statusKnown ? status.tone : 'banner'}`}><strong>{statusKnown ? dirty ? '有未保存修改' : latest?.state === 'DRAFT' ? '草稿未生效' : active ? 'AI 检查已启用' : '尚未配置规则' : '状态待核对'}</strong></p>
          <dl className="ai-definitions"><div><dt>当前草稿</dt><dd>{statusKnown ? latest?.state === 'DRAFT' ? `V${latest.version_no}` : '暂无' : '待核对'}</dd></div><div><dt>线上生效版本</dt><dd>{statusKnown ? active ? `V${active.version_no}` : '暂无' : '待核对'}</dd></div><div><dt>运行方式</dt><dd>AI 建议 + 人工复核</dd></div></dl>
          <p className="ai-muted">保存草稿不会启动检查。</p>
          {!options.loading && !options.error && !options.data.providerReady && <p className="ai-warning">模型服务尚未接通；可以保存规则，但暂不能发送照片检查，请人工复核。</p>}
        </section>
        <section className="ai-card"><h2>识别边界</h2><dl className="ai-definitions"><div><dt>疑似不符合</dt><dd>主管核实</dd></div><div><dt>无法判断</dt><dd>补拍或人工复核</dd></div><div><dt>服务异常</dt><dd>明确提示，可重试</dd></div></dl><p className="ai-warning">妆容看不清，不等于未化妆。</p></section>
        <section className="ai-card"><h2>试点与留存</h2><ul className="ai-retention-list"><li>仅限后台批准的试点门店：{options.data.hotels.map(hotel => hotel.name).join('、') || '待读取'}</li><li>试点门店合计每天最多 {options.data.dailyRequestLimit || '—'} 次（含重试）</li><li>普通照片保留 5 天，沿用后台留存策略</li><li>不改变正式工作状态或 KPI。</li></ul></section>
        {!!versions.data.length && <section className="ai-card"><details><summary>规则版本</summary><div className="ai-version-list">{versions.data.map(rule => <span className="ai-pill" key={rule.id}>V{rule.version_no} · {rule.state === 'SHADOW' ? '已启用' : rule.state === 'DRAFT' ? '草稿' : '已停用'}</span>)}</div></details></section>}
        </div>
      </aside>
    </div>
    <footer className="ai-actions"><small>规则变更创建新版本，历史结果不覆盖</small><button className="secondary ai-preview-button" disabled={disabled || !!issue} onClick={() => setPreview(true)}>预览检查规则</button><button className="secondary" disabled={disabled || !!issue} onClick={() => void save(false)}>{busy === 'draft' ? '保存中…' : '保存草稿'}</button><button className="primary" disabled={disabled || !!issue} onClick={() => void save(true)}>{busy === 'enable' ? '启用中…' : '保存并启用 AI 检查'}</button>
      {issue && <p className="ai-muted ai-config-issue">{issue}</p>}
    </footer>
    {preview && <div className="ai-modal" role="dialog" aria-modal="true" aria-label="规则预览"><section className="ai-card"><header className="ai-heading"><h2>检查规则预览</h2><button className="secondary" onClick={() => setPreview(false)}>关闭</button></header><p>岗位：{currentPosition}</p><p>门店：{options.data.hotels.filter(item => config.hotelIds.includes(item.id)).map(item => item.name).join('、')}</p>{config.criteria.filter(item => item.enabled).map(item => <p key={item.code}><strong>{criterionNames[item.code]}：</strong>{item.standard}</p>)}<p>允许例外：{config.exceptions || '未配置'}</p><p>运行方式：AI 建议 + 人工复核；不自动通过、不退回正式工作、不扣绩效。</p></section></div>}
  </section>
}
