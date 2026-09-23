import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/ConfigurationPages.tsx', import.meta.url), 'utf8')

test('assignment entry is consolidated into employee edit', () => {
  assert.doesNotMatch(source, /＋ 分配任职/)
  assert.doesNotMatch(source, /setModal\('assignment'\)/)
  assert.match(source, /title=\{editingId \? '编辑员工与任职'/)
  assert.match(source, /<h3>当前任职<\/h3>/)
  assert.match(source, /<h3>新增任职<\/h3>/)
})

test('employee edit can save profile only or create a complete assignment', () => {
  assert.match(source, /const assignmentDraftStarted = Boolean/)
  assert.match(source, /saveDisabled=\{!employeeForm\.employeeType \|\| \(Boolean\(editingId\) && \(assignmentDraftInvalid \|\| Boolean\(editingAssignmentId\)\)\)\}/)
  assert.match(source, /if \(assignmentDraftStarted\) await apiRequest\(`\/org\/employees\/\$\{editingId\}\/assignments`/)
  assert.match(source, /item\.id !== editingId && item\.assignmentId/)
})

test('current assignments can be edited or ended without hard deleting history', () => {
  assert.match(source, /openAssignmentEdit/)
  assert.match(source, /保存任职修改/)
  assert.match(source, /method: 'PUT'/)
  assert.match(source, /`\/org\/assignments\/\$\{editingAssignmentId\}`/)
  assert.match(source, /endCurrentAssignment/)
  assert.match(source, /method: 'DELETE'/)
  assert.match(source, /历史任务、日报和证据链保留/)
})
