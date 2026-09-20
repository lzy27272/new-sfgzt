import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, normalizeDetail, DailyReportAutoSummary
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;({ normalizeDetail } = await vite.ssrLoadModule('/src/features/dailyReports/api.ts'))
  ;({ DailyReportAutoSummary } = await vite.ssrLoadModule('/src/features/dailyReports/DailyReportAutoSummary.tsx'))
})
after(async () => { await vite?.close() })

const raw = (summary, status = 'DRAFT') => ({ id: 'report', reportStatus: status, positionAssignmentId: 'own', currentRevisionId: 'rev',
  revisions: [{ id: 'rev', revisionStatus: status, payloadSnapshot: { dailySummary: summary,
    resolvedTemplate: { sections: [{ id: 'section', title: '客房日报', items: [
      { id: 'usage', label: '耗用', dataSourceType: 'WORK_RECORD', required: false },
      { id: 'ai', label: 'AI事实', dataSourceType: 'AI_SUMMARY', required: false },
      { id: 'manual', label: '其他工作', dataSourceType: 'MANUAL', required: false },
    ] }] } } }],
  aiRecommendations: [{ id: 'ai', facts: { expectedCount: 2, completedCount: 1, pendingReviewCount: 1 }, analysis: '按实际提交记录分析', recommendation: '优先复核耗用数量', sourceLabels: ['DAILY_REPORT:report'] }],
})
const normalize = (summary, status) => normalizeDetail(raw(summary, status), { businessActorAssignmentId: 'own', actorId: 'account' })
const render = (summary, status) => renderToStaticMarkup(React.createElement(DailyReportAutoSummary, { report: normalize(summary, status) }))
const populated = { mode: 'FACT_ONLY_INTERNAL_RULES', usageRecordCount: 1, excludedUsageRecordCount: 0,
  expectedCount: 2, completedCount: 1, pendingReviewCount: 1,
  workItems: [{ label: '客房服务员日报', status: 'SUBMITTED', completion: '已核对客房清洁工作', formData: { checkoutRooms: 6, stayoverRooms: 3, issueFound: false, technicalId: 'should-not-display' } }],
  usageItems: [
    { itemName: '浴巾', category: 'LINEN', unit: '条', approvedQuantity: '6', pendingQuantity: '0' },
    { itemName: '牙刷', category: 'CONSUMABLE', unit: '支', approvedQuantity: '0', pendingQuantity: '12' },
    { itemName: '清洁液', category: 'CLEANING_SUPPLY', unit: '毫升', approvedQuantity: '125.5', pendingQuantity: '0' },
  ] }

test('日报展示三个类别的审核用量和待审用量，保留精确数量和单位', () => {
  const html = render(populated)
  for (const label of ['当日物品使用数量汇总', '布草', '耗品', '清洁用品', '浴巾', '牙刷', '125.5', '毫升', '已审核用量', '待审核用量', '只计算一次']) assert.ok(html.includes(label), label)
  assert.match(html, /待审核耗品、清洁用品尚未扣减库存或计入成本/)
})
test('工作摘要展示真实房数并中文化，规则分析不冒充大模型', () => {
  const html = render(populated)
  for (const label of ['当日工作自动汇总', '退房打扫数：6', '续住房打扫数：3', '是否有异常：否', '已提交待审核', '事实', '分析', '建议', '未调用大模型']) assert.ok(html.includes(label), label)
  assert.doesNotMatch(html, /technicalId|should-not-display|SUBMITTED|NOT_REQUIRED/)
})
test('无记录与已申报无耗用明确区分，不误报零耗用或全部完成', () => {
  const empty = { ...populated, workItems: [], usageItems: [], usageRecordCount: 0 }
  assert.match(render(empty), /未登记不代表用量为零/)
  assert.match(render(empty), /不会把没有记录视为完成/)
  assert.match(render({ ...empty, usageRecordCount: 1 }), /已登记本次无耗用/)
})
test('冲销记录不显示为有效用量，历史报告标明冻结快照', () => {
  assert.match(render({ ...populated, usageItems: [], excludedUsageRecordCount: 1 }), /已排除 1 份退回或冲销台账/)
  assert.match(render(populated, 'SUBMITTED'), /提交时快照/)
  assert.match(render(undefined, 'SUBMITTED'), /不能以当前台账替代历史记录/)
})
test('工作与AI字段识别为自动只读，手工补充仍可编辑', () => {
  const report = normalize(populated)
  assert.deepEqual(report.currentRevision.sections[0].items.map(item => item.dataSource), ['SYSTEM', 'SYSTEM', 'EMPLOYEE'])
  assert.deepEqual(report.aiRecommendations[0].sourceLabels, ['本日报当日工作与耗用记录'])
  assert.equal(report.completionRate, 50) // AI placeholders/manual supplements are not extra daily jobs.
})
test('自动汇总仍保留工作明细和证据名称，普通工作对象不泄露JSON字段', () => {
  const payload = raw(populated)
  payload.itemResults = [{ id: 'result', revisionId: 'rev', templateItemId: 'usage', systemPrefilled: true,
    value: { workRecordId: 'secret-record-id', status: 'SUBMITTED', completion: '整理完成', formData: { checkoutRooms: 6, technicalId: 'hidden' } },
    sourceSummary: { sourceType: 'WORK_RECORD', evidenceCount: 1 }, resultStatus: 'PENDING' }]
  payload.evidence = [{ id: 'evidence', revisionId: 'rev', itemResultId: 'result', originalName: '工作车照片.jpg', sensitivity: 'NORMAL' }]
  const report = normalizeDetail(payload, { businessActorAssignmentId: 'own' })
  const html = renderToStaticMarkup(React.createElement(DailyReportAutoSummary, { report }))
  assert.match(html, /工作车照片.jpg/)
  assert.match(html, /系统工作明细与证据/)
  assert.match(html, /整理完成/)
  assert.doesNotMatch(html, /secret-record-id|technicalId|workRecordId|sourceType/)
})
