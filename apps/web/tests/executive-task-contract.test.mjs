import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const apiSource = readFileSync(new URL('../src/features/executiveTasks/api.ts', import.meta.url), 'utf8')
const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../src/features/executiveTasks/ExecutiveTaskRoutes.tsx', import.meta.url), 'utf8')
const workbenchSource = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')

test('chairman feature uses only the dedicated executive-task API family', () => {
  assert.match(apiSource, /'\/executive-tasks'/)
  assert.match(apiSource, /\/executive-tasks\/targets/)
  assert.match(apiSource, /\/actions\/approve/)
  assert.match(apiSource, /\/actions\/rework/)
  assert.doesNotMatch(apiSource, /['"`]\/tasks(?:\/|['"`])/)
})

test('create command binds canonical UUID idempotency key to clientCommandId', () => {
  assert.match(apiSource, /const clientCommandId = crypto\.randomUUID\(\)/)
  assert.match(apiSource, /body: \{ \.\.\.input, clientCommandId \}/)
  assert.match(apiSource, /idempotencyKey: clientCommandId/)
})

test('chairman task UI never asks the client for protected source or reviewer fields', () => {
  assert.doesNotMatch(pageSource, /name=["'](?:creationSource|reviewerAssignmentId|orgUnitId|sourceSnapshot)["']/)
  assert.match(pageSource, /请选择集团总经理\/副总经理/)
  assert.match(pageSource, /首期不提供证据内容访问/)
})

test('chairman workbench selects the dedicated loader and gates it by server capability', () => {
  assert.match(appSource, /isChairman \? loadExecutiveTasks\(identity\) : loadTasks/)
  assert.match(appSource, /executiveTasksEnabled && hasAssignment/)
  assert.match(appSource, /account-self-service/)
})

test('chairman quick dispatch uses the dedicated executive task route and permission', () => {
  assert.match(workbenchSource, /resolveTaskDispatchMode\(presentationKey, executiveTasksEnabled, permissions\)/)
  assert.match(workbenchSource, /canExecutiveDispatch\s*\? go\('tasks', \{ create: 'true' \}\)/)
  assert.match(workbenchSource, /creatingTask && canManageDispatch && <TaskCreateDialog/)
})
