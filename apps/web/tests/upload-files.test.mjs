import assert from 'node:assert/strict'
import test from 'node:test'
import { materializeFileForUpload, readableUploadError } from '../src/app/uploadFiles.ts'

test('restored draft files are materialized before a multipart upload', async () => {
  const source = new File([new Uint8Array([1, 2, 3, 4])], 'room-1208.jpg', {
    type: 'image/jpeg',
    lastModified: 1234,
  })

  const upload = await materializeFileForUpload(source)

  assert.notEqual(upload, source)
  assert.equal(upload.name, source.name)
  assert.equal(upload.type, source.type)
  assert.equal(upload.lastModified, source.lastModified)
  assert.deepEqual(new Uint8Array(await upload.arrayBuffer()), new Uint8Array([1, 2, 3, 4]))
})

test('unreadable or incomplete local draft files require the employee to reselect them', async () => {
  const unreadable = new File([new Uint8Array([1])], 'expired.jpg', { type: 'image/jpeg' })
  Object.defineProperty(unreadable, 'arrayBuffer', {
    value: () => Promise.reject(new Error('temporary file no longer exists')),
  })
  await assert.rejects(materializeFileForUpload(unreadable), /已无法从本机草稿读取/)

  const incomplete = new File([new Uint8Array([1, 2])], 'incomplete.jpg', { type: 'image/jpeg' })
  Object.defineProperty(incomplete, 'arrayBuffer', {
    value: () => Promise.resolve(new Uint8Array([1]).buffer),
  })
  await assert.rejects(materializeFileForUpload(incomplete), /本机草稿内容不完整/)
})

test('opaque WebKit upload failures are converted into an actionable Chinese retry message', () => {
  const error = readableUploadError(new TypeError('Load failed'), 'image.jpg')
  assert.match(error.message, /image\.jpg 上传连接中断/)
  assert.match(error.message, /本机草稿/)
  assert.match(error.message, /直接重试/)

  const serverError = new Error('图片像素尺寸过大')
  assert.equal(readableUploadError(serverError, 'image.jpg'), serverError)
})
