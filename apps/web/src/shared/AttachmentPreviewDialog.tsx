import { useEffect } from 'react'

export { resolveAttachmentMediaType } from './attachmentPreview'

export type AttachmentPreview = {
  url: string
  name: string
  mediaType: string
}

export function AttachmentPreviewDialog({
  preview,
  onClose,
}: {
  preview: AttachmentPreview
  onClose: () => void
}) {
  useEffect(() => () => URL.revokeObjectURL(preview.url), [preview.url])
  const image = preview.mediaType.startsWith('image/')
  const pdf = preview.mediaType === 'application/pdf'
  return <div className="attachment-lightbox" role="dialog" aria-modal="true" aria-label={`查看附件：${preview.name}`}>
    <div>
      <header><span><strong>{preview.name}</strong><small>工作证据预览</small></span><button type="button" className="close" onClick={onClose} aria-label="关闭附件预览">×</button></header>
      {image ? <img src={preview.url} alt={preview.name} /> : pdf ? <iframe src={preview.url} title={preview.name} /> : <div className="attachment-download"><p>该格式不能在浏览器内直接预览，可下载后使用对应应用打开。</p><a className="primary" href={preview.url} download={preview.name}>下载附件</a></div>}
    </div>
  </div>
}
