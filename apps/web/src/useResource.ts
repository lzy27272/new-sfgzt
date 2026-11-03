import { useCallback, useEffect, useRef, useState } from 'react'
import type { ApiSource } from './domain'

type Loaded<T> = { data: T; source: ApiSource }

/**
 * Only the newest request may update a resource. This prevents a slow response
 * from the previously selected role or organization from overwriting the new
 * screen after the user switches assignments.
 */
export function createLatestRequestGuard() {
  let generation = 0
  return {
    next: () => ++generation,
    isCurrent: (candidate: number) => candidate === generation,
    invalidate: () => { generation += 1 },
  }
}

export function createResourceRequestGuard(cancellable = false) {
  const latest = createLatestRequestGuard()
  let controller: AbortController | undefined
  return {
    start(silent = false) {
      if (cancellable && silent && controller) return undefined
      if (cancellable) controller?.abort()
      controller = new AbortController()
      return { generation: latest.next(), signal: controller.signal }
    },
    isCurrent: latest.isCurrent,
    finish(generation: number) { if (latest.isCurrent(generation)) controller = undefined },
    invalidate() {
      latest.invalidate()
      if (cancellable) controller?.abort()
      controller = undefined
    },
  }
}

export function useResource<T>(key: string, loader: (signal?: AbortSignal) => Promise<Loaded<T>>, initial: T,
  refreshMs = 0, showRefreshErrors = false, options: { cancellable?: boolean } = {}) {
  const [data, setData] = useState<T>(initial)
  const [source, setSource] = useState<ApiSource>('api')
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [settledKey, setSettledKey] = useState<string>()
  const requestGuard = useRef(createResourceRequestGuard(options.cancellable)).current

  const load = useCallback(async (silent: boolean) => {
    const request = requestGuard.start(silent)
    if (!request) return
    const requestGeneration = request.generation
    if (!silent) {
      setLoading(true)
      setError(undefined)
    }
    try {
      const loaded = await loader(request.signal)
      if (!requestGuard.isCurrent(requestGeneration)) return
      setData(loaded.data)
      setSource(loaded.source)
      setError(undefined)
      setSettledKey(key)
    } catch (reason) {
      if ((!silent || showRefreshErrors) && requestGuard.isCurrent(requestGeneration)) {
        setData(initial)
        setSource('api')
        setError(reason instanceof Error ? reason.message : '数据加载失败')
        setSettledKey(key)
      }
    } finally {
      if (requestGuard.isCurrent(requestGeneration)) setLoading(false)
      requestGuard.finish(requestGeneration)
    }
  // key deliberately represents every loader dependency.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [key])

  const reload = useCallback(() => load(false), [load])
  useEffect(() => {
    void reload()
    return () => requestGuard.invalidate()
  }, [reload, requestGuard])
  useEffect(() => {
    if (refreshMs <= 0) return
    const timer = window.setInterval(() => {
      if (!document.hidden) void load(true)
    }, refreshMs)
    return () => window.clearInterval(timer)
  }, [load, refreshMs])
  const awaitingKey = options.cancellable && settledKey !== key
  return { data: awaitingKey ? initial : data, source, loading: awaitingKey || loading,
    error: awaitingKey ? undefined : error, reload, setData }
}
