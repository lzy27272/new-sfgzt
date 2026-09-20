import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const pageSource = readFileSync(new URL('../src/P0Pages.tsx', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../src/api/resources.ts', import.meta.url), 'utf8')
const serviceSource = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkPackageService.java', import.meta.url), 'utf8')
const evaluationSource = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/evaluations/EvaluationService.java', import.meta.url), 'utf8')

const boardService = readFileSync(new URL('../../core-api/src/main/java/cn/sifangguan/hotelaios/workpackage/WorkBoardService.java', import.meta.url), 'utf8')
const workbench = readFileSync(new URL('../src/features/workbench/RoleWorkbench.tsx', import.meta.url), 'utf8')

test('historical daily misses do not inflate the current exception metric', () => {
assert.match(boardService, /business_date between :fromDate and :toDate/)
  assert.match(boardService, /period_type <> 'DAY' and not optional_record/)
  assert.match(pageSource, /WorkDateToolbar/)
  assert.match(pageSource, /WorkQueueAlerts/)
})

test('multi-hotel managers receive hotel-level exception drill-down', () => {
assert.match(workbench, /负责门店/)
  assert.match(workbench, /hotelId: hotel.id, status: 'OVERDUE'/)
  assert.match(serviceSource, /hotel_context.id as hotel_org_unit_id, hotel_context.name as hotel_name/)
  assert.match(serviceSource, /requested_scope.ancestor_id = :targetOrgUnitId/)
})

test('new submissions are surfaced before older expectations', () => {
assert.match(boardService, /case board_status when 'REVIEW' then 0/)
  assert.match(boardService, /latest_submitted_at desc nulls last/)
  assert.match(pageSource, /WorkBoardList/)
})

test('record payload is unwrapped and rendered with Chinese business labels', () => {
  assert.match(apiSource, /jsonColumn\(value\(item, 'payload'\)\)/)
  assert.match(pageSource, /const payloadLabels:/)
  assert.match(pageSource, /properties\[key\]\?\.title \|\| payloadLabels\[key\]/)
  assert.doesNotMatch(pageSource, /Object\.entries\(record\.payload\)/)
  assert.doesNotMatch(pageSource, /standard\.standardCode} · \{standard\.title/)
  assert.match(pageSource, /\{standard\.title}（第\{standard\.versionNo}版）/)
})

test('attachments and management actions keep feedback in the visible drawer', () => {
  assert.match(pageSource, /attachment-lightbox-\$\{lightboxOrientation\}/)
  assert.match(pageSource, /className="evidence-gallery"/)
  assert.match(pageSource, /const context = evidenceContext\(item, expectation\)/)
  assert.match(pageSource, /loadingId=\{busy\?\.startsWith\('preview-'\)/)
  assert.match(pageSource, /className=\{`team-action-feedback/)
  assert.match(pageSource, /继续创建标准评价/)
  assert.match(pageSource, /进入评价中心/)
  assert.match(pageSource, /进入任务中心/)
})

test('evaluation checks the record target organization used by the frontend', () => {
  assert.match(evaluationSource, /w\.target_org_unit_id = :orgUnitId/)
  assert.match(evaluationSource, /s\.usage_type in \('ACCEPTANCE', 'EXECUTION'\)/)
  assert.doesNotMatch(evaluationSource, /w\.org_unit_id = :orgUnitId/)
})
