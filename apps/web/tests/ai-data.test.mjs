import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/features/aiData/AiDataFeature.tsx', import.meta.url), 'utf8')
const navigation = readFileSync(new URL('../src/app/navigationAccess.ts', import.meta.url), 'utf8')
test('AI entry requires an explicit module grant and retains separate source labels', () => {
  assert.match(navigation, /id: 'ai-data'.*permissions: \['ui\.module\.ai-data'\]/)
  assert.match(source, /PLATFORM: 'AI 中台', OTA: '盯盘助手'/)
  assert.match(source, /尚无可用门店绑定/)
})
test('AI credentials stay ephemeral and report write is opt-in', () => {
  assert.doesNotMatch(source, /localStorage|sessionStorage|dangerouslySetInnerHTML/)
  assert.match(source, /\[allowWrite, setAllowWrite\] = useState\(false\)/)
  assert.match(source, /type="password" value=\{secret.token\}/)
  assert.match(source, /setSecret\(undefined\)/)
})
test('AI screen reports inactive automation and never creates fake conclusions', () => {
  assert.match(source, /服务器不会在无人操作时自动生成报告/)
  assert.match(source, /authoringTool: '中台人工整理'/)
  assert.match(source, /snapshotIds: \[snapshot.snapshotId\]/)
  assert.match(source, /snapshot.query.from/)
  assert.match(source, /snapshot.hasMore/)
})
