const NETWORK_UPLOAD_FAILURE = /(?:load failed|failed to fetch|network(?:error| request failed)|internet connection appears to be offline)/i

export async function materializeFileForUpload(file: File): Promise<File> {
  let bytes: ArrayBuffer
  try {
    bytes = await file.arrayBuffer()
  } catch {
    throw new Error(`${file.name} 已无法从本机草稿读取，请移除后重新拍摄或选择该文件。`)
  }

  if (bytes.byteLength !== file.size || bytes.byteLength === 0) {
    throw new Error(`${file.name} 的本机草稿内容不完整，请移除后重新拍摄或选择该文件。`)
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

export function readableUploadError(reason: unknown, fileName: string): Error {
  if (reason instanceof Error && NETWORK_UPLOAD_FAILURE.test(reason.message)) {
    return new Error(`${fileName} 上传连接中断，文件仍保存在本机草稿中。请确认网络稳定后直接重试；若仍失败，请移除该文件后重新拍摄或选择。`)
  }
  return reason instanceof Error ? reason : new Error(`${fileName} 上传失败，请稍后重试。`)
}
