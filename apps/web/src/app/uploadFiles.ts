const NETWORK_UPLOAD_FAILURE = /(?:load failed|failed to fetch|network(?:error| request failed)|internet connection appears to be offline)/i

async function materializeFile(file: File, staleDraft: boolean): Promise<File> {
  let bytes: ArrayBuffer
  try {
    bytes = await file.arrayBuffer()
  } catch {
    throw new Error(staleDraft
      ? `${file.name} 已无法从本机草稿读取，请重新拍摄或选择该文件。`
      : `${file.name} 无法读取，请重新拍摄或选择该文件。`)
  }

  if (bytes.byteLength !== file.size || bytes.byteLength === 0) {
    throw new Error(staleDraft
      ? `${file.name} 的本机草稿内容不完整，请重新拍摄或选择该文件。`
      : `${file.name} 内容不完整，请重新拍摄或选择该文件。`)
  }

  // iOS/WKWebView can retain a File backed by a temporary picker URL even after
  // the metadata and Blob have been restored from IndexedDB. Streaming that File
  // directly through FormData may then reject fetch with the opaque "Load failed".
  // Materializing the bytes creates a self-contained upload body before fetch.
  return new File([bytes], file.name, {
    type: file.type,
    lastModified: file.lastModified,
  })
}

/**
 * Copies a freshly selected file while the mobile picker still owns a readable
 * handle. IndexedDB then stores this self-contained Blob instead of a temporary
 * WKWebView file URL that can disappear after WeCom recycles the page.
 */
export function materializeFileForDraft(file: File): Promise<File> {
  return materializeFile(file, false)
}

export function materializeFileForUpload(file: File): Promise<File> {
  return materializeFile(file, true)
}

type FileEntry<T extends { file: File }> = {
  accepted: T[]
  rejected: Array<{ entry: T; reason: string }>
}

async function materializeEntries<T extends { file: File }>(entries: T[], staleDraft: boolean): Promise<FileEntry<T>> {
  const settled = await Promise.all(entries.map(async (entry) => {
    try {
      return { accepted: true as const, entry: { ...entry, file: await materializeFile(entry.file, staleDraft) } }
    } catch (reason) {
      return {
        accepted: false as const,
        entry,
        reason: reason instanceof Error ? reason.message : `${entry.file.name} 无法读取。`,
      }
    }
  }))
  const result: FileEntry<T> = { accepted: [], rejected: [] }
  for (const item of settled) {
    if (item.accepted) result.accepted.push(item.entry)
    else result.rejected.push({ entry: item.entry, reason: item.reason })
  }
  return result
}

export function materializeSelectedFiles<T extends { file: File }>(entries: T[]): Promise<FileEntry<T>> {
  return materializeEntries(entries, false)
}

export function restoreReadableDraftFiles<T extends { file: File }>(entries: T[]): Promise<FileEntry<T>> {
  return materializeEntries(entries, true)
}

export function readableUploadError(reason: unknown, fileName: string): Error {
  if (reason instanceof Error && NETWORK_UPLOAD_FAILURE.test(reason.message)) {
    return new Error(`${fileName} 上传连接中断，文件仍保存在本机草稿中。请确认网络稳定后直接重试；若仍失败，请移除该文件后重新拍摄或选择。`)
  }
  return reason instanceof Error ? reason : new Error(`${fileName} 上传失败，请稍后重试。`)
}
