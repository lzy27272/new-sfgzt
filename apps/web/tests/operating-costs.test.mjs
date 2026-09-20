import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
let vite, view, query
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', server: { middlewareMode: true } })
  const module = await vite.ssrLoadModule('/src/features/dailyOperations/OperatingCostPanel.tsx')
  view = module.OperatingCostView; query = module.operatingCostQuery
})
after(async () => { await vite?.close() })
const window = { from: '2026-09-19', to: '2026-09-19', amount: '10.0000', perRoomUse: '5.0000', state: 'READY', reason: '已核对',
  detail: { from: '2026-09-19', to: '2026-09-19', pendingUsageCount: 0, shiftedEntryCount: 0,
    source: { state: 'READY', reason: '已确认全部用房', roomUses: '2', expectedDays: 1, coveredDays: 1, missingDates: [] },
    costs: [{ kind: 'CONSUMABLE', amount: '6.0000', entryCount: 1 }, { kind: 'CLEANING_SUPPLY', amount: '4.0000', entryCount: 1 }, { kind: 'LAUNDRY', amount: '0.0000', entryCount: 0 }] } }
const summary = { hotelId: 'a', today: '2026-09-20', yesterdayDate: '2026-09-19', latestClosedDay: '2026-09-19', generatedAt: '2026-09-20T07:00:00Z', roomUseBasis: 'ALL_ROOM_USES_V1', dateBasis: 'BUSINESS_DATE_LIVE', yesterday: window, monthToYesterday: { ...window, from: '2026-09-01' }, selected: window, notices: ['不包含人工房租等经营费用'] }
const render = (value = summary) => renderToStaticMarkup(React.createElement(view, { summary: value }))
test('operating center exposes all four indicators with visible cost boundaries and actual-use divisor', () => {
  const html = render()
  for (const label of ['昨日总成本', '昨日单房开房成本', '月度累计成本', '截止昨日当月单房开房成本', '¥5.0000/间次', '同房使用2次计2间次', '布草使用不扣总库存', '不包含人工房租', '2026-09-01']) assert.ok(html.includes(label), label)
  assert.match(html, /暂无本类记录/)
})
test('absent costs and missing PMS are not shown as zeros', () => {
  const missing = { ...window, amount: null, perRoomUse: null, state: 'NO_COST_RECORDS', reason: '暂无成本' }
  const html = render({ ...summary, yesterday: missing, monthToYesterday: missing, selected: missing })
  assert.match(html, /暂无已入账数据/); assert.match(html, /暂不可计算/); assert.doesNotMatch(html, /¥0\.0000/)
})
test('old room-night and posting-date data cannot appear as current operating cost', () => {
  for (const patch of [{ roomUseBasis: 'OLD_NIGHTS' }, { dateBasis: 'POSTING_DATE' }]) {
    const html = render({ ...summary, ...patch }); assert.match(html, /数据口径不一致/); assert.doesNotMatch(html, /¥10/)
  }
})
test('day month and custom queries omit stale fields from other selection modes', () => {
  const draft = { type: 'DAY', period: '2026-09-19', from: 'old', to: 'old' }
  assert.equal(query(draft), 'periodType=DAY&period=2026-09-19')
  assert.equal(query({ ...draft, type: 'MONTH', period: '' }), 'periodType=MONTH')
  assert.equal(query({ ...draft, type: 'CUSTOM', from: '2026-09-01', to: '2026-09-19' }), 'periodType=CUSTOM&from=2026-09-01&to=2026-09-19')
})
