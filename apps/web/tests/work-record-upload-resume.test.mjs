import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const resources = readFileSync(new URL('../src/api/resources.ts', import.meta.url), 'utf8')

test('work record upload reports per-file security scan progress', () => {
  assert.match(source, /正在上传并安全扫描第/)
  assert.match(source, /<progress value=\{saveProgress\.completed\}/)
  assert.match(source, /role="status" aria-live="polite"/)
})

test('successfully uploaded evidence leaves the pending queue for retry safety', () => {
  assert.match(source, /const pendingAttachments = \[\.\.\.attachments\]/)
  assert.match(source, /candidate\.draftFileId !== entry\.draftFileId/)
  assert.match(source, /await \(remaining\.length[\s\S]*writeResumableDraft\(filesDraftKey/)
  assert.match(source, /已安全保存 \$\{uploadedCount\} 个附件/)
  assert.match(source, /await loadWorkRecord\(identity, activeRecordId\)/)
})

test('work record cannot be closed while save or upload is active', () => {
  assert.match(source, /aria-label="关闭" disabled=\{!!saving \|\| validatingAttachments\}/)
  assert.match(source, /<button className="secondary" disabled=\{!!saving \|\| validatingAttachments\} onClick=\{onClose\}>关闭<\/button>/)
})

test('work and task uploads materialize restored draft files before FormData streaming', () => {
  assert.equal((resources.match(/await materializeFileForUpload\(file\)/g) ?? []).length, 3)
  assert.equal((resources.match(/throw readableUploadError\(reason, file\.name\)/g) ?? []).length, 3)
})
