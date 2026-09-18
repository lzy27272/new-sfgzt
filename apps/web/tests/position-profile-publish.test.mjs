import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/features/organization/PositionAdministration.tsx', import.meta.url), 'utf8')
const editorSource = readFileSync(new URL('../src/features/organization/PositionAccessEditor.tsx', import.meta.url), 'utf8')
const modelSource = readFileSync(new URL('../src/features/organization/positionAccessModel.ts', import.meta.url), 'utf8')

test('position profile editor loads position-specific permission boundaries', () => {
  assert.match(source, /function-options\?positionId=/)
  assert.match(source, /profile-context\?positionId=/)
  assert.match(modelSource, /option\.restrictionReason/)
  assert.match(editorSource, /!option\.delegable && !selected\.includes/)
  assert.match(source, /configurationReady \|\| selfSelectionInvalid/)
})

test('publishing reports the exact failed stage instead of always blaming impact preview', () => {
  assert.match(source, /let stage = '保存岗位功能草稿'/)
  assert.match(source, /stage = '获取发布影响预览'/)
  assert.match(source, /stage = '发布岗位功能方案'/)
  assert.match(source, /`\$\{stage\}失败：\$\{reason\.message\}`/)
})

test('copying a profile into a custom position strips context-restricted permissions', () => {
  assert.match(source, /const configurableCodes = new Set\(options\.filter\(\(option\) => option\.delegable\)/)
  assert.match(source, /permissionCodes: \(source\?\.profile\.permissionCodes \?\? draft\.permissionCodes\)\.filter/)
})
