import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const api = readFileSync(new URL('../src/features/dailyReports/api.ts', import.meta.url), 'utf8')
const routes = readFileSync(new URL('../src/features/dailyReports/DailyReportRoutes.tsx', import.meta.url), 'utf8')

test('待主管审核的工作进入日报但不计入完成率', () => {
  assert.match(api, /resultStatus === 'COMPLETED'/)
  assert.match(api, /resultStatus === 'NOT_APPLICABLE'/)
  assert.doesNotMatch(api, /systemValue !== undefined\)\s*$/m)
  assert.match(routes, /resultStatus === 'PENDING'/)
  assert.match(routes, /已提交待审核/)
})

test('日报智能分析把结构化事实转换为可读汇总', () => {
  assert.match(api, /pendingReviewCount/)
  assert.match(api, /待审核/)
  assert.match(api, /displayFactSummary\(recommendation\.facts\)/)
})
