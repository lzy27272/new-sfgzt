import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { resolveAttachmentMediaType } from '../src/shared/attachmentPreview.ts'

const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')

test('mobile evidence preview never relies on a popup opened after an async request', () => {
  assert.doesNotMatch(app, /window\.open\(URL\.createObjectURL/)
  assert.match(app, /<AttachmentPreviewDialog preview=/)
})

test('binary attachment responses infer previewable media from a safe file extension', () => {
  assert.equal(resolveAttachmentMediaType(new Blob(), 'room-101.JPG'), 'image/jpeg')
  assert.equal(resolveAttachmentMediaType(new Blob(), 'inspection.pdf'), 'application/pdf')
  assert.equal(resolveAttachmentMediaType(new Blob(['x'], { type: 'image/png' }), 'photo.bin'), 'image/png')
  assert.equal(resolveAttachmentMediaType(new Blob(), 'record.xlsx'), 'application/octet-stream')
})
