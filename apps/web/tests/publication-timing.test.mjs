import test from 'node:test'
import assert from 'node:assert/strict'
import { publicationStart } from '../src/features/workPackages/publicationTiming.ts'
test('publication dates use tenant midnight rather than browser UTC date', () => {
  assert.equal(publicationStart('2026-10-27', 'Asia/Shanghai'), '2026-10-26T16:00:00.000Z')
  assert.equal(publicationStart('2026-07-01', 'America/New_York'), '2026-07-01T04:00:00.000Z')
  assert.equal(publicationStart('2026-12-01', 'America/New_York'), '2026-12-01T05:00:00.000Z')
})
test('invalid calendar date and missing timezone cannot publish', () => {
  for (const date of ['2026-02-30', '', 'today']) assert.throws(() => publicationStart(date, 'Asia/Shanghai'))
  assert.throws(() => publicationStart('2026-10-27', ''))
})
