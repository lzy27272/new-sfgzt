export function resolveAttachmentMediaType(blob: Blob, fileName: string, declaredType?: string) {
  const preferred = declaredType?.trim() || blob.type
  if (preferred && preferred !== 'application/octet-stream') return preferred
  const extension = fileName.split('.').pop()?.toLowerCase()
  if (['jpg', 'jpeg'].includes(extension ?? '')) return 'image/jpeg'
  if (extension === 'png') return 'image/png'
  if (extension === 'webp') return 'image/webp'
  if (extension === 'gif') return 'image/gif'
  if (extension === 'pdf') return 'application/pdf'
  return preferred || 'application/octet-stream'
}
