import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const dialogSource = await readFile(new URL('../src/Pilot6Pages.tsx', import.meta.url), 'utf8')
const resourceSource = await readFile(new URL('../src/api/resources.ts', import.meta.url), 'utf8')

test('manual task dispatch accepts optional image notes and mobile camera capture', () => {
  assert.match(dialogSource, /图片说明（选填）/)
  assert.match(dialogSource, /下达参考图片（选填）/)
  assert.match(dialogSource, /capture="environment"/)
  assert.match(dialogSource, /materializeSelectedFiles/)
  assert.match(resourceSource, /\/dispatch-attachments\/upload/)
})

test('multi-step task creation and dispatch reuse stable idempotency keys on retry', () => {
  assert.match(dialogSource, /createIdempotencyKey = useRef/)
  assert.match(dialogSource, /createManagementTask\([\s\S]*createIdempotencyKey\.current\)/)
  assert.match(dialogSource, /dispatchIdempotencyKey = useRef/)
  assert.match(dialogSource, /dispatchManagementTask\([\s\S]*dispatchIdempotencyKey\.current\)/)
})
