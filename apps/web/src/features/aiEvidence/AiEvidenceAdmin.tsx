import { useEffect, useState } from 'react'
import type { RoleContext } from '../../domain'
import { apiRequest } from '../../api/client'
import { useScopedResource } from '../../shared/useScopedResource'
import { criterionNames, emptyConfig, errorText, validateConfig } from './model'
import type { Option, Rule, RuleConfig } from './model'
import { EvidencePhoto } from './AiEvidenceReview'
import './aiEvidence.css'

type Options = { hotels: Option[]; positions: Option[]; items: { code: string; name: string }[]; providerReady: boolean; dailyRequestLimit: number }
const emptyOptions: Options = { hotels: [], positions: [], items: [], providerReady: false, dailyRequestLimit: 0 }
export function AiEvidenceAdmin({ identity, onClose }: { identity: RoleContext; onClose: () => void }) {
  const options = useScopedResource(`ai-options:${identity.key}`, signal => apiRequest<Options>('/ai-evidence/options', identity, { signal }), emptyOptions)
  const [position, setPosition] = useState('')
  const versions = useScopedResource<Rule[]>(`ai-rules:${identity.key}:${position}`, signal => position ? apiRequest(`/ai-evidence/rules?positionId=${position}`, identity, { signal }) : Promise.resolve([]), [])
  const [config, setConfig] = useState<RuleConfig>(emptyConfig)
  const [busy, setBusy] = useState(false)
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')
  const [preview, setPreview] = useState(false)
  const [itemQuery, setItemQuery] = useState('')
  useEffect(() => {
    if (!versions.loading) setConfig(versions.data[0]?.configuration ?? emptyConfig())
  }, [versions.data, versions.loading, position])
  const toggle = (key: 'hotelIds' | 'itemCodes', id: string) => setConfig(current => ({ ...current, [key]: current[key].includes(id) ? current[key].filter(value => value !== id) : [...current[key], id] }))
  const issue = validateConfig(config, position)
  const save = async (shadow: boolean) => {
    if (issue) { setError(issue); return }
    setBusy(true); setError(''); setMessage('')
    try {
      await apiRequest('/ai-evidence/rules', identity, { method: 'POST', body: JSON.stringify({ positionId: position, expectedLatestId: versions.data[0]?.id ?? null, configuration: config, shadow }) })
      await versions.reload()
      setMessage(shadow ? '影子规则新版本已保存；不改变任务状态或绩效。模型未接通时仍转人工。' : '规则已保存为新草稿版本，尚未改变当前启用规则。')
    } catch (reason) { setError(errorText(reason)) } finally { setBusy(false) }
  }
  const sample = async (kind: 'PASS' | 'CONCERN', file?: File) => {
    if (!file || !position) return
    setBusy(true); setError('')
    try {
      const body = new FormData(); body.append('file', file); body.append('positionId', position); body.append('kind', kind)
      const result = await apiRequest<{ id: string }>('/ai-evidence/samples', identity, { method: 'POST', body })
      setConfig(current => ({ ...current, sampleIds: [...(current.sampleIds ?? []), result.id] }))
      setMessage('样例已上传；保存规则后关联，仍遵循现有图片保留期限。')
    } catch (reason) { setError(errorText(reason)) } finally { setBusy(false) }
  }
  return <section className="ai-page" aria-label="AI检查规则配置">
    <div className="ai-breadcrumb">配置中心 / 岗位工作模板 / 仪容仪表</div>
    <header className="ai-heading"><h1>仪容仪表 · AI 检查规则</h1><button className="secondary" onClick={onClose}>返回岗位工作模板</button></header>
    <div className="ai-banner">先影子试运行：只生成审核建议，不改变任务状态或绩效</div>
    {(options.loading || versions.loading) && <p role="status">正在读取规则…</p>}
    {!!(error || options.error || versions.error) && <p className="ai-error" role="alert">{error || errorText(options.error || versions.error)}</p>}
    {message && <p className="ai-success" role="status">{message}</p>}
    <div className="ai-config-layout">
      <div className="ai-column">
        <section className="ai-card"><h2>适用范围</h2>
          <label>适用岗位<select aria-label="适用岗位" value={position} disabled={busy} onChange={e => { setPosition(e.target.value); setMessage(''); setError('') }}><option value="">请选择岗位</option>{options.data.positions.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
          <fieldset><legend>适用门店</legend><div className="ai-choice-list">{options.data.hotels.map(hotel => <label className="ai-checkbox" key={hotel.id}><input type="checkbox" checked={config.hotelIds.includes(hotel.id)} disabled={busy} onChange={() => toggle('hotelIds', hotel.id)} />{hotel.name}</label>)}</div></fieldset>
          <fieldset><legend>关联原工作项</legend><input aria-label="搜索工作项" placeholder="搜索仪容仪表、晨会等工作" value={itemQuery} onChange={e => setItemQuery(e.target.value)} /><div className="ai-choice-list ai-scroll-options">{options.data.items.filter(item => item.name.includes(itemQuery) || config.itemCodes.includes(item.code)).map(item => <label className="ai-checkbox" key={`${item.code}:${item.name}`}><input type="checkbox" checked={config.itemCodes.includes(item.code)} onChange={() => toggle('itemCodes', item.code)} disabled={busy} />{item.name}</label>)}</div></fieldset>
        </section>
        <section className="ai-card"><h2>检查项目</h2><div className="ai-rule-table">
          {config.criteria.map((item, index) => <div className="ai-rule-row" key={item.code}><label className="ai-checkbox"><input type="checkbox" checked={item.enabled} disabled={busy} onChange={e => setConfig(current => ({ ...current, criteria: current.criteria.map((c, i) => i === index ? { ...c, enabled: e.target.checked } : c) }))} />{criterionNames[item.code]}</label><textarea aria-label={`${criterionNames[item.code]}规范`} value={item.standard} maxLength={1000} placeholder="填写已确认的岗位规范，不由AI猜测" disabled={busy || !item.enabled} onChange={e => setConfig(current => ({ ...current, criteria: current.criteria.map((c, i) => i === index ? { ...c, standard: e.target.value } : c) }))} /><span className={item.enabled ? 'ai-pill pass' : 'ai-pill'}>{item.enabled ? '启用' : '不启用'}</span></div>)}
          <div className="ai-rule-row ai-disabled"><span>—　妆容</span><span>需人工判断</span><span className="ai-pill">本期不启用</span></div></div>
          <label>允许例外<textarea value={config.exceptions ?? ''} maxLength={1000} placeholder="按已确认制度填写合理例外；未配置不代表可以自行推断" disabled={busy} onChange={e => setConfig(current => ({ ...current, exceptions: e.target.value }))} /></label>
        </section>
        <section className="ai-card"><h2>规范样例</h2><div className="ai-sample-grid">{(['PASS', 'CONCERN'] as const).map(kind => <label className="ai-upload" key={kind}><span aria-hidden="true">↥</span><strong>上传{kind === 'PASS' ? '合格' : '不合格'}样例</strong><small>使用门店已确认的规范样例</small><input aria-label={`上传${kind === 'PASS' ? '合格' : '不合格'}样例`} type="file" accept="image/jpeg,image/png" disabled={!position || busy || (config.sampleIds?.length ?? 0) >= 6} onChange={e => { void sample(kind, e.target.files?.[0]); e.currentTarget.value = '' }} /></label>)}</div>
          {!!config.sampleIds?.length && <div className="ai-evidence-grid">{config.sampleIds.map((id, index) => <div key={id}><EvidencePhoto identity={identity} item={{ id, source: 'SAMPLE', media_type: 'image/jpeg', original_name: `规范样例 ${index + 1}` }} /><button type="button" className="secondary" disabled={busy} onClick={() => setConfig(current => ({ ...current, sampleIds: current.sampleIds.filter(value => value !== id) }))}>移除样例 {index + 1} 关联</button></div>)}</div>}
          <p className="ai-warning">未配置岗位规范前，不能启用自动处理。样例到期后需重新提供，不延长原图留存。</p>
        </section>
      </div>
      <div className="ai-column">
        <section className="ai-card"><h2>运行方式</h2><label className="ai-checkbox"><input type="radio" name="ai-mode" checked readOnly />影子试运行</label><p className="ai-muted">只生成审核建议，不改变任务状态或绩效</p><label className="ai-checkbox ai-disabled"><input type="radio" name="ai-mode" disabled />受控自动处理</label><p className="ai-muted">完成样本验收与启用确认后，再开启自动通过与整改提醒。</p><p className="ai-warning">{options.data.providerReady ? `模型通道已配置；每日调用上限 ${options.data.dailyRequestLimit} 次` : '模型服务尚未接通；不会发送真实员工照片'}</p></section>
        <section className="ai-card"><h2>异常处理</h2><dl className="ai-definitions"><div><dt>疑似不符合</dt><dd>提交主管待核实</dd></div><div><dt>无法判断</dt><dd>补拍或人工复核</dd></div><div><dt>服务异常</dt><dd>重试或转人工</dd></div></dl></section>
        <section className="ai-card"><h2>考核与留存</h2><dl className="ai-definitions">{['最终完成率', '按时完成率', '首次合格率'].map(name => <div key={name}><dt>{name}</dt><dd>影子期不影响正式统计</dd></div>)}<div><dt>不自动扣绩效</dt><dd>本规则不触发或影响绩效发放</dd></div><div><dt>普通照片保留</dt><dd>沿用后台留存策略（已约定 5 天）</dd></div></dl><p className="ai-warning">争议证据留存期限：待确认</p></section>
        {!!versions.data.length && <section className="ai-card"><h2>规则版本</h2><div className="ai-version-list">{versions.data.map(rule => <span className="ai-pill" key={rule.id}>V{rule.version_no} · {rule.state === 'SHADOW' ? '影子运行' : rule.state === 'DRAFT' ? '草稿' : '已停用'}</span>)}</div><small>新保存创建新版本，不覆盖历史检查结果。</small></section>}
      </div>
    </div>
    <footer className="ai-actions"><small>规则变更创建新版本，历史结果不覆盖</small><button className="secondary" disabled={busy || !!issue} onClick={() => setPreview(true)}>预览检查规则</button><button className="secondary" disabled={busy || versions.loading || !!issue} onClick={() => void save(false)}>保存草稿</button><button className="primary" disabled={busy || versions.loading || !!issue} onClick={() => void save(true)}>{busy ? '保存中…' : '启用影子试运行'}</button><button className="secondary" disabled title="需完成模型验收及另行启用确认">启用自动处理</button></footer>
    {issue && <p className="ai-muted">{issue}</p>}
    {preview && <div className="ai-modal" role="dialog" aria-modal="true" aria-label="规则预览"><section className="ai-card"><header className="ai-heading"><h2>检查规则预览</h2><button className="secondary" onClick={() => setPreview(false)}>关闭</button></header><p>岗位：{options.data.positions.find(item => item.id === position)?.name}</p><p>门店：{options.data.hotels.filter(item => config.hotelIds.includes(item.id)).map(item => item.name).join('、')}</p>{config.criteria.filter(item => item.enabled).map(item => <p key={item.code}><strong>{criterionNames[item.code]}：</strong>{item.standard}</p>)}<p>运行方式：仅影子建议；不自动通过、不退回正式工作、不扣绩效。</p></section></div>}
  </section>
}
