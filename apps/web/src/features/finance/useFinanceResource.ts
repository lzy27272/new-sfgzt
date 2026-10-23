import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiIdentity } from '../../api/client'
import { financeRequest } from './api'
import { financeScope } from './model'

export function useFinanceResource<T>(identity: ApiIdentity, path: string | undefined) {
  const key = `${financeScope(identity)}:${identity.orgScopes.join(',')}:${path ?? 'disabled'}`
  const identityRef = useRef(identity); identityRef.current = identity
  const [state, setState] = useState<{ key: string; data?: T; error?: unknown; loading: boolean }>({ key, loading: !!path })
  const [revision, setRevision] = useState(0)
  const reload = useCallback(() => setRevision(value => value + 1), [])
  useEffect(() => {
    if (!path) { setState({ key, loading: false }); return }
    const controller = new AbortController()
    setState({ key, loading: true })
    void financeRequest<T>(identityRef.current, path, 'GET', undefined, controller.signal)
      .then(data => { if (!controller.signal.aborted) setState({ key, data, loading: false }) })
      .catch(error => { if (!controller.signal.aborted) setState({ key, error, loading: false }) })
    return () => controller.abort()
  }, [key, path, revision])
  useEffect(() => {
    const visible = () => { if (!document.hidden) reload() }
    window.addEventListener('focus', visible); window.addEventListener('finance:changed', reload)
    document.addEventListener('visibilitychange', visible)
    return () => { window.removeEventListener('focus', visible); window.removeEventListener('finance:changed', reload); document.removeEventListener('visibilitychange', visible) }
  }, [reload])
  // Do not paint even one frame of a different assignment's cached financial data.
  return { ...(state.key === key ? state : { loading: !!path, data: undefined, error: undefined }), reload }
}
