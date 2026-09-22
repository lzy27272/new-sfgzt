import { useEffect, useRef, useState } from 'react'
import { apiBlob, type ApiIdentity } from '../../api/client'
import { financeDownload, errorMessage } from './api'
import type { Evidence } from './model'
import s from './finance.module.css'

export function EvidencePreview({ identity, claimId, evidence }: { identity: ApiIdentity; claimId: string; evidence: Evidence }) {
  const container = useRef<HTMLDivElement>(null)
  const [visible, setVisible] = useState(false); const [url, setUrl] = useState(''); const [error, setError] = useState('')
  const [retry, setRetry] = useState(0)
  useEffect(() => {
    const observer = new IntersectionObserver(entries => { if (entries.some(e => e.isIntersecting)) { setVisible(true); observer.disconnect() } }, { rootMargin: '200px' })
    if (container.current) observer.observe(container.current)
    return () => observer.disconnect()
  }, [])
  useEffect(() => {
    if (!visible) return
    const abort = new AbortController(); let objectUrl = ''; setError('')
    void apiBlob(`/finance/claims/${claimId}/attachments/${evidence.id}`, identity, { signal: AbortSignal.any([abort.signal, AbortSignal.timeout(30000)]) }).then(blob => {
      if (abort.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl)
    }).catch(e => { if (!abort.signal.aborted) setError(errorMessage(e)) })
    return () => { abort.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [visible, evidence.id, claimId, identity, retry])
  return <div className={s.evidenceCard} ref={container}>
    <div className={s.preview}>{url ? evidence.media_type.startsWith('image/')
      ? <img src={url} alt={evidence.original_name} />
      : <><iframe title={`PDF凭证 ${evidence.original_name}`} src={url} sandbox="" /><small>若手机未显示PDF，请下载原件查看</small></>
      : <span>{error || '正在读取原始凭证…'}</span>}</div>
    <strong>{evidence.original_name}</strong><small>{(evidence.size_bytes / 1024).toFixed(0)} KB · 原件保留</small>
    <button type="button" onClick={() => void financeDownload(identity, `/claims/${claimId}/attachments/${evidence.id}`, evidence.original_name).catch(e => setError(errorMessage(e)))}>下载原件</button>
    {error && <button type="button" onClick={() => setRetry(x => x + 1)}>重试读取</button>}
  </div>
}
