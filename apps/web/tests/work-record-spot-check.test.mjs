import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { before, after, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'
import { spotCheckPreviewParams, spotCheckImpactFromBoard } from '../src/features/workbench/spotCheckPreview.ts'
import { boardQuery } from '../src/features/workbench/workBoardModel.ts'

let vite, WorkRecordReviewActions
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;({ WorkRecordReviewActions } = await vite.ssrLoadModule('/src/P0Pages.tsx'))
})
after(async () => { await vite?.close() })
const props = (overrides = {}) => ({ status: 'APPROVED', eligibility: { canReview: true, spotCheck: true, reason: '' }, reason: '现场不符合检查要求', isDemo: false, onReason() {}, onReview() {}, ...overrides })
const render = overrides => renderToStaticMarkup(React.createElement(WorkRecordReviewActions, props(overrides)))

test('approved exempt mandatory work exposes audited spot-check actions rather than a second approval', () => {
  const html = render()
  assert.match(html, /免审工作抽查/)
  assert.match(html, />抽查不合格<\/button>/)
  assert.doesNotMatch(html, />复核通过<\/button>/)
  assert.match(html, />抽查合格<\/button>/)
  assert.match(html, /按原工作日期撤销一次有效完成/)
  assert.match(html, /人工复验通过才恢复/)
  assert.match(html, /按需事件不扣必做完成率/)
})

test('a submitted exempt record can also be failed by spot check with server permission', () => {
  assert.match(render({ status: 'SUBMITTED' }), />抽查不合格<\/button>/)
})

test('spot-check failure requires a nonblank reason and cannot mutate in demo or while busy', () => {
  for (const overrides of [{ reason: '' }, { reason: '  \n ' }, { isDemo: true }, { busy: 'review-REJECTED' }]) {
    const html = render(overrides)
    assert.match(html, /class="danger-button" disabled=""/)
  }
  assert.doesNotMatch(render(), /class="danger-button" disabled=""/)
})

test('server canReview false or absent always disables a visible review action', () => {
  const denied = render({ eligibility: { canReview: false, spotCheck: true, reason: '不能审核本人工作' } })
  assert.match(denied, /不能审核本人工作/)
  assert.match(denied, /class="danger-button" disabled=""/)
  assert.match(render({ status: 'SUBMITTED', eligibility: undefined }), /class="primary" disabled=""/)
})

test('ordinary manual work remains reviewable only while submitted', () => {
  const eligibility = { canReview: true, spotCheck: false, reason: '' }
  const submitted = render({ status: 'SUBMITTED', eligibility })
  assert.match(submitted, />复核通过<\/button>/)
  assert.match(submitted, />退回补充<\/button>/)
  assert.doesNotMatch(submitted, />抽查不合格<\/button>/)
  for (const status of ['APPROVED', 'REJECTED', 'DRAFT']) {
    const html = render({ status, eligibility })
    assert.doesNotMatch(html, /<button/)
    assert.match(html, /当前记录状态不可复核/)
  }
})

test('legacy eligibility responses without spotCheck retain normal manual review', () => {
  const html = render({ status: 'SUBMITTED', eligibility: { canReview: true, reason: '' } })
  assert.match(html, /工作记录复核/)
  assert.match(html, />复核通过<\/button>/)
})

const descendants = element => !element || typeof element !== 'object' ? [] : [element, ...React.Children.toArray(element.props?.children).flatMap(descendants)]

test('first failure click prepares a preview and cannot submit the rejection directly', () => {
  const calls = []
  const element = WorkRecordReviewActions(props({ onPrepareSpotCheck: () => calls.push('preview'), onReview: value => calls.push(value) }))
  descendants(element).find(node => node.type === 'button' && node.props.children === '抽查不合格').props.onClick()
  assert.deepEqual(calls, ['preview'])
})

test('confirmation displays actual original-day rates and cancel never submits a review', () => {
  const calls = []
  const preview = { impact: { businessDate: '2026-10-20', assignmentId: 'original-worker', expected: 4, completed: 3, afterCompleted: 2, beforeRate: 75, afterRate: 50 } }
  const html = render({ preview })
  assert.match(html, /2026-10-20/)
  assert.match(html, /75% → 50%/)
  assert.match(html, /原执行任职、原工作日期统计预估/)
  const element = WorkRecordReviewActions(props({ preview, onCancelSpotCheck: () => calls.push('cancel'), onReview: value => calls.push(value) }))
  const buttons = descendants(element).filter(node => node.type === 'button')
  buttons.find(node => node.props.children === '取消，不提交').props.onClick()
  assert.deepEqual(calls, ['cancel'])
  buttons.find(node => node.props.children === '确认不合格并通知整改').props.onClick()
  assert.deepEqual(calls, ['cancel', 'REJECTED'])
})

test('loading or unavailable impact never enables a confirm mutation and never inserts demo rates', () => {
  for (const preview of [{ loading: true }, { error: '无法预估，请重试。' }]) {
    const html = render({ preview })
    assert.match(html, /class="danger-button" disabled=""[^>]*>确认不合格并通知整改/)
    assert.doesNotMatch(html, /80%|70%/)
  }
})

test('impact query uses the record original day, actual execution assignment and existing hotel scope', () => {
  const record = { businessDate: '2026-10-20', positionAssignmentId: 'original-worker', targetOrgUnitId: 'record-department' }
  const expectation = { businessDate: '2026-10-20', hotelOrgUnitId: 'original-hotel' }
  const query = spotCheckPreviewParams(record, expectation)
  assert.deepEqual(query, { dateMode: 'day', from: '2026-10-20', to: '2026-10-20', assignmentId: 'original-worker', orgUnitId: 'original-hotel', queue: 'PERIOD', status: 'ALL' })
  assert.equal(boardQuery(query).assignmentId, 'original-worker')
  for (const patch of [{ businessDate: undefined }, { businessDate: '2026-02-30' }, { positionAssignmentId: undefined }, { targetOrgUnitId: undefined }]) {
    assert.throws(() => spotCheckPreviewParams({ ...record, ...patch }, expectation), /无法预估/)
  }
  assert.throws(() => spotCheckPreviewParams(record, { businessDate: '2026-10-21' }), /日期不一致/)
})

test('impact is one completed item with the original denominator, never a fixed percentage deduction', () => {
  const query = { from: '2026-10-20', to: '2026-10-20', assignmentId: 'original-worker' }
  const board = { fromDate: query.from, toDate: query.to, period: { expected: 7, completed: 5 } }
  assert.deepEqual(spotCheckImpactFromBoard(board, query), { businessDate: query.from, assignmentId: query.assignmentId, expected: 7, completed: 5, afterCompleted: 4, beforeRate: 71.4, afterRate: 57.1 })
  for (const period of [{ expected: 0, completed: 0 }, { expected: 7, completed: 0 }, { expected: 7, completed: 8 }, { expected: 7, completed: Number.NaN }, { expected: 1.5, completed: 1 }]) {
    assert.throws(() => spotCheckImpactFromBoard({ ...board, period }, query), /无法预估/)
  }
  assert.throws(() => spotCheckImpactFromBoard({ ...board, toDate: '2026-10-21' }, query), /无法预估/)
})

test('audit queues are sent to the server with scope and pagination, not filtered from a local first page', () => {
  for (const queue of ['SPOT_CHECK', 'REPAIR']) {
    const query = boardQuery({ queue, hotelId: 'hotel', departmentId: 'dept', assignmentId: 'worker', page: '2' }, '2026-10-26')
    assert.equal(query.queue, queue)
    assert.equal(query.page, 2)
    assert.equal(query.orgUnitId, 'hotel')
    assert.equal(query.assignmentId, 'worker')
  }
})
