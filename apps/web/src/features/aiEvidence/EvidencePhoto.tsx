import { useEffect, useRef, useState } from 'react'
import type { RoleContext } from '../../domain'
import { apiBlob } from '../../api/client'
import { errorText } from './model'
import type { Evidence } from './model'

export function EvidencePhoto({ item, identity, box, contentPath }: { item: Evidence; identity: RoleContext; box?: number[]; contentPath?: string }) {
  const [url, setUrl] = useState('')
  const [error, setError] = useState('')
  const [large, setLarge] = useState(false)
  const trigger = useRef<HTMLButtonElement>(null)
  const close = () => { setLarge(false); trigger.current?.focus({ preventScroll: true }) }
  useEffect(() => {
    let active = true; let owned = ''
    const abort = new AbortController(); setUrl(''); setError(''); setLarge(false)
    const path = contentPath ?? (item.source === 'SAMPLE' ? `/ai-evidence/samples/${item.id}/content` : item.source === 'AI' ? `/ai-evidence/photos/${item.id}/content` : `/work-data/attachments/${item.id}/content`)
    apiBlob(path, identity, { signal: abort.signal }).then(blob => {
      if (!active) return
      owned = URL.createObjectURL(blob); setUrl(owned)
    }).catch(reason => { if (active) setError(errorText(reason)) })
    return () => { active = false; abort.abort(); if (owned) URL.revokeObjectURL(owned) }
  }, [identity.key, item.id, item.source, contentPath])
  const sample = item.source === 'SAMPLE'
  return <figure className="ai-photo"><button ref={trigger} type="button" aria-label={`查看证据 ${item.original_name}`} onClick={() => setLarge(true)} disabled={!url}>
    {url ? <span className="ai-photo-frame"><img src={url} alt={sample ? item.original_name : '本次提交的工作证据'} />{box && <span className="ai-evidence-box" style={{ left: `${box[0] * 100}%`, top: `${box[1] * 100}%`, width: `${(box[2] - box[0]) * 100}%`, height: `${(box[3] - box[1]) * 100}%` }} />}</span> : <span>{error || '读取图片…'}</span>}
  </button><figcaption>{item.original_name}</figcaption>{large && url && <div className="ai-modal ai-lightbox" role="dialog" aria-modal="true" aria-label="证据图片预览" onKeyDown={event => {
    if (event.key === 'Escape') { event.stopPropagation(); close() }
    if (event.key === 'Tab') { event.preventDefault(); event.stopPropagation() }
  }}><button type="button" autoFocus className="secondary" onClick={close}>关闭图片</button><img src={url} alt={sample ? '规范样例原图' : '工作证据原图'} /></div>}</figure>
}
