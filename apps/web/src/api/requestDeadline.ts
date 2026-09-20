/** Bound the complete request (including body decoding), even if a WebView ignores abort. */
export async function withRequestDeadline<T>(
  request: (signal: AbortSignal) => Promise<T>, message: string, timeoutMs = 20_000,
): Promise<T> {
  const controller = new AbortController()
  let timer: ReturnType<typeof setTimeout> | undefined
  const timeout = new Promise<never>((_resolve, reject) => {
    timer = setTimeout(() => {
      reject(new Error(message))
      controller.abort()
    }, timeoutMs)
  })
  try {
    return await Promise.race([request(controller.signal), timeout])
  } finally {
    clearTimeout(timer)
  }
}
