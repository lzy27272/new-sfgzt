import { useEffect, useRef, useState } from 'react'
import { apiBlob, apiRequest, type ApiIdentity } from '../../api/client'
import { buildResumableDraftKey, readResumableDraft, removeResumableDraft, writeResumableDraft } from '../../app/resumableDrafts'
import styles from './activities.module.css'

type PendingPhoto = { id: string; file: Blob; name: string }
function Photo({ identity, path, label }: { identity: ApiIdentity; path: string; label: string }) {
  const [url, setUrl] = useState<string>()
  const [error, setError] = useState(false)
  const [attempt, setAttempt] = useState(0)
  useEffect(() => {
    const controller = new AbortController(); let objectUrl: string | undefined
    setUrl(undefined); setError(false)
    void apiBlob(path, identity, { signal: controller.signal }).then(blob => {
      if (controller.signal.aborted) return
      objectUrl = URL.createObjectURL(blob); setUrl(objectUrl)
    }).catch(() => { if (!controller.signal.aborted) setError(true) })
    return () => { controller.abort(); if (objectUrl) URL.revokeObjectURL(objectUrl) }
  }, [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, path, attempt])
  return url ? <a href={url} target="_blank" rel="noreferrer" aria-label={label + '，打开原图'}><img src={url} alt={label} /></a>
    : <div className={styles.photoPlaceholder}>{error ? <button type="button" onClick={() => setAttempt(value => value + 1)}>重新加载照片</button> : '照片加载中…'}</div>
}
export function ActivityPhotos({ identity, base, entryId, ids, editable, disabled, onAdd, onRemove, onPendingChange }: {
  identity: ApiIdentity; base: string; entryId: string; ids: string[]; editable: boolean; disabled?: boolean;
  onAdd?: (id: string) => void; onRemove?: (id: string) => void; onPendingChange?: (id: string, pending: boolean) => void
}) {
  const key = buildResumableDraftKey('daily-activity-photos', [identity.tenantId, identity.actorId, identity.businessActorAssignmentId, base, entryId])
  const [pending, setPending] = useState<PendingPhoto[]>([])
  const [busy, setBusy] = useState(false)
  const [ready, setReady] = useState(false)
  const [error, setError] = useState<string>()
  const [restoreAttempt, setRestoreAttempt] = useState(0)
  const queue = useRef<PendingPhoto[]>([])
  const completed = useRef<PendingPhoto[]>([])
  const running = useRef(false)
  const mounted = useRef(false)
  const inFlight = useRef<AbortController | undefined>(undefined)
  const latest = useRef({ ids, onAdd, onPendingChange })
  latest.current = { ids, onAdd, onPendingChange }
  useEffect(() => {
    mounted.current = true
    let active = true
    setReady(false); setError(undefined)
    latest.current.onPendingChange?.(entryId, editable)
    const cleanup = () => { active = false; mounted.current = false; inFlight.current?.abort(); latest.current.onPendingChange?.(entryId, false) }
    if (!editable) {
      queue.current = []; completed.current = []; setPending([]); setReady(true)
      void removeResumableDraft(key).catch(() => {})
      return cleanup
    }
    void readResumableDraft<{ pending: PendingPhoto[]; completed?: PendingPhoto[] }>(key).then(saved => {
      if (!active) return
      // Keep uploaded receipts until the parent draft has durably recorded their IDs.
      const restored = [...(saved?.pending ?? []), ...(saved?.completed ?? [])]
      if (restored.some(photo => typeof photo.id !== 'string' || typeof photo.name !== 'string' || !(photo.file instanceof Blob))) throw new Error('照片草稿格式异常')
      queue.current = [...new Map(restored.filter(photo => !latest.current.ids.includes(photo.id)).map(photo => [photo.id, photo])).values()]
      completed.current = []
      setPending(queue.current); setReady(true)
      latest.current.onPendingChange?.(entryId, queue.current.length > 0)
    }).catch(() => { if (active) setError('无法恢复本机待上传照片，已暂停新增和提交，避免覆盖原草稿。请重试。') })
    return cleanup
  }, [key, editable, entryId, restoreAttempt])
  const persist = async () => {
    if (queue.current.length || completed.current.length) await writeResumableDraft(key, { pending: queue.current, completed: completed.current })
    else await removeResumableDraft(key)
    if (mounted.current) {
      setPending([...queue.current])
      latest.current.onPendingChange?.(entryId, queue.current.length > 0)
    }
  }
  const upload = async () => {
    if (running.current) return
    running.current = true; setBusy(true); setError(undefined)
    try {
      await persist()
      while (queue.current.length && mounted.current) {
        const next = queue.current[0]
        if (!latest.current.ids.includes(next.id)) {
          const form = new FormData(); form.append('file', next.file, next.name)
          const controller = new AbortController()
          inFlight.current = controller
          const timeout = window.setTimeout(() => controller.abort(), 90000)
          try { await apiRequest(base + '/' + next.id, identity, { method: 'PUT', body: form, signal: controller.signal }) }
          finally { window.clearTimeout(timeout) }
          completed.current = [...completed.current.filter(item => item.id !== next.id), next]
          // Persist the upload receipt before telling the form it can reference the photo.
          await writeResumableDraft(key, { pending: queue.current.slice(1), completed: completed.current })
          if (mounted.current) latest.current.onAdd?.(next.id)
        }
        queue.current = queue.current.slice(1)
        await persist()
      }
    } catch (cause) { if (mounted.current) setError(cause instanceof Error && cause.name !== 'AbortError' ? cause.message : '上传中断，照片已在本机保留，可继续上传。') }
    finally { running.current = false; if (mounted.current) setBusy(false) }
  }
  const choose = async (files: File[]) => {
    if (!files.length || running.current) return
    if (files.some(file => !['image/jpeg', 'image/png'].includes(file.type) || file.size > 20 * 1024 * 1024)) {
      setError('请选择20MB以内的JPG或PNG照片。'); return
    }
    if (ids.length + queue.current.length + files.length > 20) { setError('每条记录最多20张照片。'); return }
    queue.current = [...queue.current, ...files.map(file => ({ id: crypto.randomUUID(), file, name: file.name }))]
    try { await persist(); await upload() }
    catch { setError('设备空间不足，照片尚未暂存。请保留相册原图并重试。'); setPending([...queue.current]); latest.current.onPendingChange?.(entryId, true) }
  }
  return <div className={styles.photos}>
    <div className={styles.gallery}>{ids.map((id, index) => <figure key={id}>
      <Photo identity={identity} path={base + '/' + id} label={'照片 ' + (index + 1)} />
      <figcaption>照片 {index + 1}{editable && <button type="button" disabled={disabled || busy} onClick={() => {
        completed.current = completed.current.filter(photo => photo.id !== id)
        void persist().then(() => onRemove?.(id)).catch(() => setError('无法更新本机照片草稿，请重试。'))
      }}>移除</button>}</figcaption>
    </figure>)}</div>
    {editable && <label className={styles.upload}>从相册选择 / 拍照（最多20张）<input type="file" accept="image/jpeg,image/png" multiple disabled={!ready || disabled || busy} onChange={event => { const files = Array.from(event.target.files ?? []); event.target.value = ''; void choose(files) }} /></label>}
    {pending.length > 0 && <div role="status">{busy ? '正在上传' : '待上传'} {pending.length} 张（本机暂存）
      {!busy && <><button type="button" disabled={disabled} onClick={() => void upload()}>继续上传</button><button type="button" disabled={disabled} onClick={() => { queue.current = []; void persist().catch(() => setError('清除失败，请重试。')) }}>移除待上传照片</button></>}
    </div>}
    {error && <p role="alert" className="inline-error">{error}</p>}
    {editable && !ready && error && <button type="button" disabled={disabled} onClick={() => setRestoreAttempt(value => value + 1)}>重新恢复照片草稿</button>}
  </div>
}
