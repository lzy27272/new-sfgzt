import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, forms, logic
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;[forms, logic] = await Promise.all([vite.ssrLoadModule('/src/features/storeResources/RoomNightCostPanel.tsx'), vite.ssrLoadModule('/src/features/storeResources/roomNightCosts.ts')])
})
after(async () => { await vite?.close() })
const metric = { kind: 'CONSUMABLE', amount: '100.0000', entryCount: 10, zeroAmountCount: 0, legacyCount: 0, state: 'READY', reason: '按已入账成本计算', perRoomUse: '0.5000' }
const source = { state: 'READY', reason: 'PMS全部用房数量、分项及口径已核对', roomUses: '200', coveredDays: 31, expectedDays: 31, missingDates: [], mode: 'PERIOD_SUMMARY', definitionLabel: '全部实际使用间次；含钟点房；含免费房；含自用房', collectedAt: '2026-09-01T08:00:00+08:00', revision: 'a'.repeat(64), breakdown: { paidOvernightRoomUses: '160', complimentaryRoomUses: '20', houseUseRoomUses: '10', paidHourlyRoomUses: '10' } }
const window = { from: '2026-08-01', to: '2026-08-31', costMonthClosed: true, pendingUsageCount: 0, shiftedEntryCount: 0, source, costs: [metric] }
const change = { state: 'READY', percent: '66.67', reason: '正数为成本上升' }
const analysis = { periodType: 'MONTH', roomUseBasis: 'ALL_ROOM_USES_V1', selection: '2026-08', currentMonth: '2026-09', latestClosedDay: '2026-09-17', generatedAt: '2026-09-18T10:00:00+08:00', businessTimezone: 'Asia/Shanghai', costTimezone: 'Asia/Shanghai',
  current: window, previous: { ...window, from: '2026-07-01', to: '2026-07-31' }, yearAgo: { ...window, from: '2025-08-01', to: '2025-08-31' }, comparisons: [{ kind: 'CONSUMABLE', previous: change, yearAgo: { ...change, percent: '-50.00' } }], notices: ['只读分析，不写入原月结或正式KPI。'] }
const render = (name, props) => renderToStaticMarkup(React.createElement(forms[name], props))

test('only confirmed values render rates, retaining exact four-decimal money', () => {
  assert.equal(logic.unitCostText(metric), '¥0.5000 / 间次')
  assert.equal(logic.unitCostText({ ...metric, perRoomUse: '9007199254740993.0001' }), '¥9,007,199,254,740,993.0001 / 间次')
  assert.equal(logic.unitCostText({ ...metric, state: 'UNVERIFIED' }), '暂不计算')
  assert.equal(logic.unitCostText({ ...metric, perRoomUse: undefined }), '暂不计算')
})
test('missing comparison is never zero growth and no floating point conversion occurs', () => {
  assert.equal(logic.costChangeText(change), '+66.67%')
  assert.equal(logic.costChangeText({ ...change, percent: '-50.00' }), '-50.00%')
  assert.equal(logic.costChangeText({ ...change, percent: '0.00' }), '0.00%')
  for (const value of [undefined, { ...change, state: 'DEFINITION_CHANGED' }, { ...change, percent: 'NaN' }, { ...change, percent: null }]) assert.equal(logic.costChangeText(value), '不可比')
})
test('period identity excludes stale responses from old dates or switched period types', () => {
  assert.equal(logic.analysisMatches(analysis, 'MONTH', '2026-08'), true)
  assert.equal(logic.analysisMatches(analysis, 'MONTH', '2026-07'), false)
  assert.equal(logic.analysisMatches(analysis, 'DAY', ''), false)
  assert.equal(logic.analysisMatches(undefined, 'MONTH', ''), false)
  assert.equal(logic.windowLabel({ from: '2026-09-01', to: '2026-08-31' }), '本期暂无已日结日期')
})
test('renders source definition, exact costs, periods, revision and management-only boundary', () => {
  const html = render('RoomNightCostView', { analysis })
  for (const text of ['耗品使用', '¥0.5000 / 间次', '¥100.0000', '200', '较上月同期', '+66.67%', '-50.00%', '含钟点房', '含免费房', '来源指纹', '成本月份已关账', '只读分析，不写入原月结']) assert.ok(html.includes(text), text)
})
test('unverified candidates cannot appear as active room-use denominators', () => {
  const html = render('RoomNightEvidenceView', { window: { ...window, source: { ...source, state: 'UNVERIFIED', roomUses: undefined, candidateRoomUses: '200', revision: undefined, reason: '免费房口径待核验' } } })
  assert.match(html, /待核验数量：200/)
  assert.match(html, /尚未作为计算分母/)
  assert.doesNotMatch(html, /PMS全部用房|UNVERIFIED|来源指纹|收费钟点房/)
})
test('missing-day and zero-use explanations are visible instead of empty result cards', () => {
  assert.match(render('RoomNightEvidenceView', { window: { ...window, source: { ...source, state: 'INCOMPLETE', roomUses: undefined, missingDates: ['2026-08-17'], reason: 'PMS缺少营业日' } } }), /缺少日期：2026-08-17/)
  const html = render('RoomNightCostView', { analysis: { ...analysis, current: { ...window, source: { ...source, state: 'ZERO_USES', roomUses: '0', reason: 'PMS确认全部实际使用间次为0，不进行除法', breakdown: { paidOvernightRoomUses: '0', complimentaryRoomUses: '0', houseUseRoomUses: '0', paidHourlyRoomUses: '0' } }, costs: [{ ...metric, state: 'ZERO_USES', perRoomUse: undefined }] } } })
  assert.match(html, /不进行除法/)
  assert.match(html, /暂不计算/)
})
test('absence of cost records is explained and pending corrections are not hidden', () => {
  const html = render('RoomNightCostView', { analysis: { ...analysis, current: { ...window, pendingUsageCount: 2, shiftedEntryCount: 3, costs: [{ ...metric, state: 'NO_COST_RECORDS', entryCount: 0, amount: '0.0000', perRoomUse: undefined }] } } })
  for (const text of ['暂无本类成本记录', '不代表实际成本为0', '待审核耗用 2 条', '冲销 3 条', '不直接据此评价员工']) assert.ok(html.includes(text), text)
})
test('daily comparison label and query controls distinguish dates from months', () => {
  assert.match(render('RoomNightCostView', { analysis: { ...analysis, periodType: 'DAY' } }), /较前一日/)
  const html = render('RoomNightCostPanel', { identity: { key: 'admin' }, hotelId: 'hotel-a', month: '2026-08' })
  for (const text of ['PMS用房间次与成本', '每间次成本统计方式', '按营业日', '成本比较期间', 'type="month"', '刷新用房与成本']) assert.ok(html.includes(text), text)
})
test('legacy responses cannot silently present overnight-only numbers as all-use costs', () => {
  for (const basis of [undefined, 'OVERNIGHT_SOLD', 'ALL_USED_ROOM_NIGHTS_V1']) {
    const legacy = { ...analysis, roomUseBasis: basis }
    assert.equal(logic.analysisMatches(legacy, 'MONTH', '2026-08'), false)
    const html = render('RoomNightCostView', { analysis: legacy })
    assert.match(html, /role="alert"/)
    assert.match(html, /不是“用房间次”口径/)
    assert.doesNotMatch(html, /¥0.5000|PMS全部用房/)
  }
})
test('all four categories are visible with integer uses and no duplicate addition', () => {
  const html = render('RoomNightEvidenceView', { window: { ...window, source: { ...source, roomUses: '220', breakdown: { paidOvernightRoomUses: '200', complimentaryRoomUses: '10', houseUseRoomUses: '5', paidHourlyRoomUses: '5' } } } })
  for (const text of ['PMS全部用房', '220', '收费过夜房', '免费房', '自用房', '收费钟点房', '5', '不重复', '不是各类型独立成本']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /PMS过夜间次：|undefined|NaN/)
})
test('unverified responses never render retained totals or breakdowns as confirmed evidence', () => {
  const html = render('RoomNightEvidenceView', { window: { ...window, source: { ...source, state: 'UNVERIFIED', candidateRoomUses: '200' } } })
  assert.match(html, /待核验数量：200/)
  assert.doesNotMatch(html, /PMS全部用房：|全部用房分类明细|收费钟点房/)
})
test('verified total-only evidence renders costs without inventing four zero components', () => {
  const html = render('RoomNightCostView', { analysis: { ...analysis, current: { ...window, source: { ...source, breakdown: undefined } }, previous: { ...window, source: { ...source, breakdown: undefined } }, yearAgo: { ...window, source: { ...source, breakdown: undefined } } } })
  for (const text of ['¥0.5000 / 间次', '已核对全部用房总间次', '分项不按0补齐', '不影响总成本分摊']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /全部用房分类明细|收费钟点房|undefined|NaN/)
})
test('counting guidance clearly includes repeat uses with no hourly conversion', () => {
  const html = render('RoomNightCostPanel', { identity: { key: 'admin' }, hotelId: 'hotel-a', month: '2026-08' })
  for (const text of ['同房当天使用两次计2间次', '不按房号去重', '钟点不折算', '无需强制提供四类明细']) assert.ok(html.includes(text), text)
  assert.doesNotMatch(html, /房晚|折合|间夜/)
})
