import { useEffect, useRef, useState } from 'react'
import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
import './aiData.css'

type Catalog = {
  datasets: { id: string; name: string; project: string; available: boolean }[]
  organizations: { id: string; name: string }[]
  otaBindings: { id: string; source_name: string }[]
  notice: string
  automaticReview: { enabled: boolean; status: string; detail: string }
}
type Query = { dataset: string; from: string; to: string; offset: number; orgUnitId?: string; bindingId?: string }
type Snapshot = {
  snapshotId: string; query: Query; rows: Record<string, unknown>[]; notice: string
  hasMore: boolean; nextOffset?: number; capturedAt: string
  coverage: { state: string; observedDaysInPage?: number; expectedDays?: number }
}
type Connection = { id: string; name: string; allow_report_write: boolean; expires_at: string; revoked_at?: string }
type Report = { id: string; title: string; source_type: string; period_start: string; period_end: string; created_at: string; body?: string; evidence?: unknown }
const tabs = ['数据目录', 'Codex 接入', '自动复盘', '报告中心'] as const
type Tab = typeof tabs[number]
const sourceNames: Record<string, string> = { PLATFORM: 'AI 中台', OTA: '盯盘助手', COMBINED: '跨项目' }
const columnNames: Record<string, string> = {
  id: '记录标识', business_date: '业务日期', status: '状态', record_kind: '记录类型',
  org_unit_id: '组织标识', target_org_unit_id: '目标组织', position_assignment_id: '任职标识',
  submitted_at: '提交时间', occurred_at: '发生时间', work_package_item_id: '工作项', attempt_no: '提交次数',
  hotel_org_unit_id: '门店标识', report_status: '日报状态', review_status: '审核状态', current_revision_no: '修订号',
  metric_code: '指标代码', value: '指标值', numerator: '分子', denominator: '分母', data_state: '数据状态',
  lifecycle_status: '任务状态', sla_status: '时效状态', priority: '优先级', due_at: '截止时间', created_at: '创建时间',
  room_revenue: '客房收入', sold_room_nights: '已售房晚', effective_sellable_room_nights: '可售房晚',
  available_rooms: '剩余房量', occupancy_rate: '出租率', adr: 'ADR', revpar: 'RevPAR', completeness: '完整性',
}
function display(value: unknown): string { return value == null ? '—' : typeof value === 'object' ? JSON.stringify(value) : String(value) }
function localDate(date: Date) { return new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai' }).format(date) }
function message(error: unknown) { return error instanceof Error ? error.message : '请求未完成，请重试' }
const weekAgo = () => localDate(new Date(Date.now() - 7 * 86400000))
const yesterday = () => localDate(new Date(Date.now() - 86400000))

export default function AiDataFeature({ identity }: { identity: RoleContext }) {
  const [tab, setTab] = useState<Tab>('数据目录')
  const [catalog, setCatalog] = useState<Catalog>()
  const [connections, setConnections] = useState<Connection[]>([])
  const [reports, setReports] = useState<Report[]>([])
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [reload, setReload] = useState(0)
  const [dataset, setDataset] = useState('WORK_RECORDS')
  const [from, setFrom] = useState(weekAgo)
  const [to, setTo] = useState(yesterday)
  const [organization, setOrganization] = useState('')
  const [binding, setBinding] = useState('')
  const [snapshot, setSnapshot] = useState<Snapshot>()
  const [connectionName, setConnectionName] = useState('我的 Codex 设备')
  const [days, setDays] = useState(7)
  const [allowWrite, setAllowWrite] = useState(false)
  const [secret, setSecret] = useState<{ token: string; expiresAt: string; id: string }>()
  const [reportTitle, setReportTitle] = useState('')
  const [reportBody, setReportBody] = useState('')
  const [selectedReport, setSelectedReport] = useState<Report>()
  const life = useRef<AbortController | null>(null)
  useEffect(() => {
    const controller = new AbortController()
    life.current = controller
    return () => controller.abort()
  }, [])
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true); setError('')
    Promise.all([
      apiRequest<Catalog>('/ai-data/catalog', identity, { signal: controller.signal }),
      apiRequest<Connection[]>('/ai-data/connections', identity, { signal: controller.signal }),
      apiRequest<Report[]>('/ai-data/reports', identity, { signal: controller.signal }),
    ]).then(([data, devices, saved]) => {
      if (controller.signal.aborted) return
      setCatalog(data); setConnections(devices); setReports(saved)
      setDataset(current => data.datasets.some(item => item.id === current && item.available) ? current : (data.datasets.find(item => item.available)?.id ?? ''))
    }).catch(e => { if (!controller.signal.aborted) setError(message(e)) })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identity, reload])

  async function action(work: (signal: AbortSignal) => Promise<void>) {
    if (busy || !life.current) return
    const signal = life.current.signal
    setBusy(true); setError(''); setNotice('')
    try { await work(signal) } catch (e) { if (!signal.aborted) setError(message(e)) }
    finally { if (!signal.aborted) setBusy(false) }
  }
  function post<T>(path: string, body: unknown, signal: AbortSignal) {
    return apiRequest<T>(`/ai-data/${path}`, identity, { method: 'POST', headers: { 'X-SFG-AI-Action': '1' }, body: JSON.stringify(body), signal })
  }
  function query(next?: Query) {
    void action(async signal => {
      const input = next ?? { dataset, from, to, offset: 0, ...(dataset.startsWith('OTA') ? { bindingId: binding } : organization ? { orgUnitId: organization } : {}) }
      const result = await post<Snapshot>('query', input, signal)
      if (!signal.aborted) { setSnapshot(result); setReportTitle(''); setReportBody('') }
    })
  }
  const currentDataset = catalog?.datasets.find(item => item.id === dataset)
  const validQuery = Boolean(currentDataset?.available && from && to && from <= to && (!dataset.startsWith('OTA') || binding))
  const mcpUrl = `${window.location.origin}/api/v1/ai-data/mcp`
  return <section className="ai-data page-section">
    <header className="ai-data-heading"><div><p className="ai-data-eyebrow">统一数据入口 · 两个独立项目</p><h2>AI 数据与复盘</h2><p>从云端取数，保留分析证据，把报告存回中台。</p></div><span className="ai-data-badge">按当前账号及岗位授权</span></header>
    <nav className="ai-data-tabs" aria-label="AI 数据功能">{tabs.map(item => <button type="button" key={item} aria-pressed={tab === item} onClick={() => { setTab(item); setError(''); setNotice('') }}>{item}</button>)}</nav>
    {error && <div className="ai-data-error" role="alert">{error} <button type="button" onClick={() => setReload(n => n + 1)}>重新读取</button></div>}
    {notice && <p className="ai-data-note" role="status">{notice}</p>}
    {loading ? <p role="status">正在读取云端目录与权限…</p> : catalog && <>
      {tab === '数据目录' && <>
        <div className="ai-data-projects">{(['PLATFORM', 'OTA'] as const).map(project => <article className="ai-data-card" key={project}><span className="ai-data-eyebrow">{project === 'PLATFORM' ? '管理与执行' : '经营与房态'}</span><h3>{sourceNames[project]}</h3><p>{project === 'PLATFORM' ? '工作记录、日报状态、任务状态、绩效指标' : `已授权绑定 ${catalog.otaBindings.length} 家门店 · 每日经营事实`}</p><span className="ai-data-badge">{project === 'PLATFORM' ? '结构化字段已接入' : catalog.otaBindings.length ? '按明确门店绑定查询' : '尚无可用门店绑定'}</span></article>)}</div>
        <p className="ai-data-muted">{catalog.notice}</p>
        <article className="ai-data-card"><h3>按期间查询</h3><div className="ai-data-fields">
          <label>数据集<select value={dataset} disabled={busy} onChange={e => setDataset(e.target.value)}>{catalog.datasets.map(item => <option key={item.id} value={item.id} disabled={!item.available}>{item.name}{item.available ? '' : '（无权限）'}</option>)}</select></label>
          {dataset.startsWith('OTA') ? <label>绑定门店<select value={binding} disabled={busy} onChange={e => setBinding(e.target.value)}><option value="">请选择已授权门店</option>{catalog.otaBindings.map(item => <option key={item.id} value={item.id}>{item.source_name}</option>)}</select></label> : <label>组织范围<select value={organization} disabled={busy} onChange={e => setOrganization(e.target.value)}><option value="">当前权限范围</option>{catalog.organizations.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>}
          <label>开始日期<input type="date" value={from} disabled={busy} onChange={e => setFrom(e.target.value)} /></label><label>结束日期<input type="date" value={to} disabled={busy} onChange={e => setTo(e.target.value)} /></label>
          <button className="ai-data-primary" disabled={busy || !validQuery} onClick={() => query()}>{busy ? '处理中…' : '查询并保留快照'}</button>
        </div><p className="ai-data-muted">每页最多 500 条。缺失值不等于 0；未完成期间、跨指标汇总和历史覆盖不足都须在报告中说明。</p></article>
        {snapshot && <article className="ai-data-card"><div className="ai-data-row"><h3>取数结果 · {snapshot.query.from} 至 {snapshot.query.to}</h3><span className="ai-data-badge">本页 {snapshot.rows.length} 条</span></div><p>{snapshot.notice}</p>
          <p className="ai-data-muted">{snapshot.coverage.state === 'COMPLETE' ? '本次日事实查询覆盖完整。' : snapshot.coverage.state === 'PARTIAL' ? `当前页覆盖 ${snapshot.coverage.observedDaysInPage} / ${snapshot.coverage.expectedDays} 天，不能假设完整。` : '仅返回已观察记录，未核验应有总量。'} 快照：{snapshot.snapshotId}</p>
          {snapshot.rows.length ? <div className="ai-data-table-wrap"><table><thead><tr>{Object.keys(snapshot.rows[0]).map(key => <th key={key}>{columnNames[key] ?? key}</th>)}</tr></thead><tbody>{snapshot.rows.slice(0, 30).map((row, index) => <tr key={index}>{Object.keys(snapshot.rows[0]).map(key => <td key={key}>{display(row[key])}</td>)}</tr>)}</tbody></table></div> : <p>这个范围没有可见记录。不会补零或编造历史数据。</p>}
          {snapshot.rows.length > 30 && <p className="ai-data-muted">页面预览前 30 条；快照包含本页全部 {snapshot.rows.length} 条。</p>}
          {snapshot.hasMore && <button disabled={busy} onClick={() => query({ ...snapshot.query, offset: snapshot.nextOffset ?? snapshot.query.offset + 500 })}>读取下一页（仍属同一查询期间）</button>}
          <details><summary>根据本页快照保存人工报告草稿</summary><p>这里只保存你填写的内容，不会自动生成 AI 结论。完整复盘请用 Codex 读取全部分页及对比期间，再保存报告。</p><label>报告标题<input value={reportTitle} maxLength={160} onChange={e => setReportTitle(e.target.value)} /></label><label>分析内容<textarea rows={6} value={reportBody} maxLength={60000} onChange={e => setReportBody(e.target.value)} /></label><button className="ai-data-primary" disabled={busy || !reportTitle.trim() || !reportBody.trim()} onClick={() => void action(async signal => {
            const saved = await post<Report>('reports', { requestId: crypto.randomUUID(), title: reportTitle, body: reportBody, from: snapshot.query.from, to: snapshot.query.to, snapshotIds: [snapshot.snapshotId], authoringTool: '中台人工整理' }, signal)
            if (!signal.aborted) { setSelectedReport(saved); setTab('报告中心'); setReload(n => n + 1); setNotice('报告草稿已保存，待人工核验。') }
          })}>保存报告草稿</button></details>
        </article>}
      </>}
      {tab === 'Codex 接入' && <>
        <article className="ai-data-card"><h3>让授权设备通过中台取数</h3><p>每台设备单独创建连接。它只能读取你当前有权查看的数据；“保存报告”单独授权，不允许改动任务、价格、库存或绩效事实。</p><label>云端 MCP 地址<input readOnly value={mcpUrl} /></label><div className="ai-data-fields"><label>设备备注<input maxLength={80} value={connectionName} onChange={e => setConnectionName(e.target.value)} /></label><label>有效期<select value={days} onChange={e => setDays(Number(e.target.value))}><option value={1}>1 天</option><option value={7}>7 天</option><option value={30}>30 天</option></select></label><label className="ai-data-checkbox"><input type="checkbox" checked={allowWrite} onChange={e => setAllowWrite(e.target.checked)} />允许保存分析报告</label><button className="ai-data-primary" disabled={busy || !connectionName.trim() || Boolean(secret)} onClick={() => void action(async signal => {
          const result = await post<{ token: string; expiresAt: string; id: string }>('connections', { name: connectionName, days, allowReportWrite: allowWrite }, signal)
          if (!signal.aborted) { setSecret(result); setReload(n => n + 1) }
        })}>创建设备连接</button></div>
          {secret && <div className="ai-data-secret"><strong>密钥仅显示这一次，请保存在该设备的安全凭据中</strong><input aria-label="本次连接密钥" readOnly type="password" value={secret.token} autoComplete="off" /><p>有效至 {new Date(secret.expiresAt).toLocaleString('zh-CN')}。不要截图、发群或粘贴到报告中。</p><button onClick={() => void action(async () => { await navigator.clipboard.writeText(secret.token); setNotice('已复制密钥。请尽快配置并清理剪贴板，勿向他人发送。') })}>复制密钥</button><button onClick={() => setSecret(undefined)}>已保存，隐藏密钥</button></div>}
          <details><summary>设备连接配置说明</summary><p>在 Codex 的 MCP 连接设置中添加上方地址，并使用 Bearer 密钥认证。不同设备需要各自配置与授权，登录同一个 Codex 账号不会自动获得企业数据权限。</p><p>使用配置文件的设备可参考以下内容；SFG_AI_DATA_TOKEN 是该设备安全保存的环境变量，不能把密钥写进项目或报告。</p><pre>{`[mcp_servers.sifangguan]\nurl = "${mcpUrl}"\nbearer_token_env_var = "SFG_AI_DATA_TOKEN"`}</pre><p>连接后先读取 data_catalog，再使用 query_data 分页取数。允许保存报告的连接会额外提供 save_report。</p></details>
        </article>
        <article className="ai-data-card"><h3>我的连接</h3>{connections.length === 0 ? <p>尚未创建连接。</p> : <div className="ai-data-list">{connections.map(item => <div className="ai-data-row" key={item.id}><div><strong>{item.name}</strong><p>{item.allow_report_write ? '读取 + 保存报告' : '只读'} · {item.revoked_at ? '已撤销' : Date.parse(item.expires_at) <= Date.now() ? '已过期' : `有效至 ${new Date(item.expires_at).toLocaleString('zh-CN')}`}</p></div>{!item.revoked_at && Date.parse(item.expires_at) > Date.now() && <button disabled={busy} onClick={() => void action(async signal => { await post(`connections/${item.id}/revoke`, {}, signal); if (!signal.aborted) { if (secret?.id === item.id) setSecret(undefined); setReload(n => n + 1); setNotice('连接已撤销，后续请求将被拒绝。') } })}>撤销连接</button>}</div>)}</div>}</article>
      </>}
      {tab === '自动复盘' && <article className="ai-data-card"><span className="ai-data-badge">尚未启用</span><h3>周、月、年复盘</h3><p>{catalog.automaticReview.detail}</p><p>当前可以由已连接的 Codex 主动取数、分析并回存报告。服务器不会在无人操作时自动生成报告，也未建立成功的定时任务。</p><p>启用定时执行还需要独立的 AI 服务授权、可用网络、执行身份及运行时间。你的本地备份设备仍只负责保管加密备份，不承担分析数据库。</p></article>}
      {tab === '报告中心' && <><article className="ai-data-card"><div className="ai-data-row"><h3>我的复盘报告</h3><span className="ai-data-badge">待人工核验</span></div><p className="ai-data-muted">报告保存在云端，仅本人且保留原权限时可读。正文与引用证据固定留存，修改应保存新版本。</p>{reports.length === 0 ? <p>尚无报告。请先取数分析，再保存草稿。</p> : <div className="ai-data-list">{reports.map(report => <button className="ai-data-report" key={report.id} disabled={busy} onClick={() => void action(async signal => { const result = await apiRequest<Report>(`/ai-data/reports/${report.id}`, identity, { signal }); if (!signal.aborted) setSelectedReport(result) })}><strong>{report.title}</strong><span>{sourceNames[report.source_type]} · {report.period_start} 至 {report.period_end}</span></button>)}</div>}</article>{selectedReport && <article className="ai-data-card"><h3>{selectedReport.title}</h3><p className="ai-data-muted">{selectedReport.period_start} 至 {selectedReport.period_end} · 待人工核验</p><pre className="ai-data-report-body">{selectedReport.body}</pre><details><summary>查看固定取数证据</summary><pre>{JSON.stringify(selectedReport.evidence, null, 2)}</pre></details></article>}</>}
    </>}
  </section>
}
