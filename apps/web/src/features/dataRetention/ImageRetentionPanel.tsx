import { useEffect, useState } from 'react'
import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
import { formatRetentionBytes, formatRetentionTimestamp } from './policy'
import styles from './dataRetention.module.css'
import imageStyles from './imageRetention.module.css'

type ImageRow = {
  id: string; source_type: string; original_name: string; size_bytes: number; store_name: string | null
  uploaded_at: string; expires_at: string | null; permanent_retention: boolean; legal_hold: boolean; hold_reason: string | null
  state: string; last_error: string | null; row_version: number
}
type ImagePage = { items: ImageRow[]; total: number; page: number; active: boolean; workerEnabled: boolean }

export function ImageRetentionPanel({ identity, revision }: { identity: RoleContext; revision: number }) {
  const [result, setResult] = useState<ImagePage>()
  const [page, setPage] = useState(0)
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const [refresh, setRefresh] = useState(0)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [selected, setSelected] = useState<ImageRow>()
  const [reason, setReason] = useState('')
  const [saving, setSaving] = useState(false)
  useEffect(() => {
    const controller = new AbortController()
    setLoading(true)
    setError('')
    apiRequest<ImagePage>(`/admin/image-retention?page=${page}&search=${encodeURIComponent(query)}`, identity, { signal: controller.signal })
      .then((data) => { if (!controller.signal.aborted) setResult(data) })
      .catch((e: unknown) => { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : '图片清单加载失败') })
      .finally(() => { if (!controller.signal.aborted) setLoading(false) })
    return () => controller.abort()
  }, [identity, page, query, refresh, revision])

  const saveHold = async () => {
    if (!selected || selected.permanent_retention || !reason.trim() || saving) return
    if (selected.legal_hold && !window.confirm('解除保留后，已经超过保存期限的图片将进入自动清理，无法恢复。确认解除？')) return
    setSaving(true)
    setError('')
    try {
      await apiRequest(`/admin/image-retention/${selected.id}/hold`, identity, {
        method: 'PUT', body: JSON.stringify({ enabled: !selected.legal_hold, reason: reason.trim(), expectedVersion: selected.row_version }),
      })
      setSelected(undefined)
      setReason('')
      setRefresh((value) => value + 1)
    } catch (e) { setError(e instanceof Error ? e.message : '保留标记保存失败') }
    finally { setSaving(false) }
  }

  return <article className={`${styles.policyPanel} ${imageStyles.panel}`}>
    <header><div><span>IMPORTANT EVIDENCE</span><h2>图片保留与重要证据</h2>
      <p>{result ? result.active ? '自动清理已启用，每5分钟检查到期图片。' : result.workerEnabled ? '执行器已部署，租户清理开关关闭。' : '执行器未启用，当前不会自动删除。' : '正在核对清理状态…'}</p>
      <p>普通工作图片从上传成功起计时，未审核图片同样到期；重要证据标记后暂停清理。规范样例照片长期保留，不参与自动清理。工作记录和经营数据不随图片删除。</p></div></header>
    <form className={styles.formActions} onSubmit={(event) => { event.preventDefault(); setPage(0); setQuery(search.trim()); setRefresh((value) => value + 1) }}>
      <label>搜索图片 <input aria-label="搜索图片名称或编号" value={search} maxLength={240} onChange={(event) => setSearch(event.target.value)} /></label>
      <button className="secondary" disabled={loading || saving}>搜索 / 刷新</button>
    </form>
    {error && <p role="alert" className="inline-error">{error}</p>}
    {selected && <form className={styles.previewNotice} onSubmit={(event) => { event.preventDefault(); void saveHold() }}>
      <strong>{selected.legal_hold ? '解除重要证据保留' : '标记为重要证据'}：{selected.original_name}</strong>
      <label>原因 <input aria-label="保留或解除原因" required maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} disabled={saving} /></label>
      <span><button className="primary" disabled={saving || !reason.trim()}>{saving ? '保存中…' : '确认保存'}</button> <button type="button" className="secondary" disabled={saving} onClick={() => setSelected(undefined)}>取消</button></span>
    </form>}
    <div style={{ overflowX: 'auto' }} aria-busy={loading}>
      <table className={imageStyles.table} aria-label="图片保存期限与保留标记">
        <thead><tr><th>图片 / 来源</th><th>上传 / 到期</th><th>状态</th><th>操作</th></tr></thead>
        <tbody>{result?.items.map((row) => <tr key={row.id}>
          <td>{row.original_name}<br /><small>{row.store_name ?? ({ attachment: '工作记录', task_evidence: '任务证据', daily_report_evidence: '日报证据', ai_evidence_sample: 'AI 规范样例', ai_evidence_photo: 'AI 检查照片' }[row.source_type] ?? '业务图片')} · {formatRetentionBytes(row.size_bytes)}</small></td>
          <td><small>{formatRetentionTimestamp(row.uploaded_at)}<br />{row.permanent_retention ? '长期保留（不过期）' : row.legal_hold ? '重要证据：暂停清理' : formatRetentionTimestamp(row.expires_at)}</small></td>
          <td>{row.state === 'DELETED' ? '已清理' : row.permanent_retention ? '规范样例 · 长期保留' : row.legal_hold ? '重要证据 · 保留' : row.state === 'DELETE_FAILED' ? '清理失败，待重试' : '正常保留'}{row.hold_reason && <div><small>{row.hold_reason}</small></div>}</td>
          <td>{row.permanent_retention ? <span>不参与自动清理</span> : <button type="button" className="secondary" disabled={row.state === 'DELETED' || saving || loading} onClick={() => { setSelected(row); setReason('') }}>{row.legal_hold ? '解除保留' : '标记重要'}</button>}</td>
        </tr>)}</tbody>
      </table>
      {!loading && result?.total === 0 && <p>暂无符合条件的图片。</p>}
    </div>
    <footer className={styles.formActions}><span>共 {result?.total ?? '—'} 张 · 第 {page + 1} 页</span>
      <button className="secondary" disabled={page === 0 || loading || saving} onClick={() => { setSelected(undefined); setPage((value) => value - 1) }}>上一页</button>
      <button className="secondary" disabled={!result || (page + 1) * 20 >= result.total || loading || saving} onClick={() => { setSelected(undefined); setPage((value) => value + 1) }}>下一页</button>
    </footer>
  </article>
}
