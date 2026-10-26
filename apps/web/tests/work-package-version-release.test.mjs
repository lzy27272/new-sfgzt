import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { before, after, test } from 'node:test'
import { createServer } from 'vite'

let vite, release
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  release = await vite.ssrLoadModule('/src/features/workPackages/versionRelease.ts')
})
after(async () => { await vite?.close() })

test('V88 publication uses an explicitly chosen future tenant midnight, not browser time or now', () => {
  const now = new Date('2026-10-26T09:00:00Z')
  assert.equal(release.versionPublicationStart('2026-10-27', 'Asia/Shanghai', true, now), '2026-10-26T16:00:00.000Z')
  assert.equal(release.versionPublicationStart('2026-10-27', 'America/New_York', true, now), '2026-10-27T04:00:00.000Z')
  assert.throws(() => release.versionPublicationStart('2026-10-26', 'Asia/Shanghai', true, now), /未来本地日期/)
  assert.throws(() => release.versionPublicationStart('2026-10-25', 'Asia/Shanghai', true, now), /未来本地日期/)
  assert.throws(() => release.versionPublicationStart('', 'Asia/Shanghai', true, now))
  assert.throws(() => release.versionPublicationStart('2026-02-30', 'Asia/Shanghai', true, now))
  assert.throws(() => release.versionPublicationStart('2026-10-27', '', true, now))
})

test('explicit allocation dates cannot precede the selected version or reverse the interval', () => {
  const effective = '2026-10-26T16:00:00Z'
  assert.equal(release.allocationDateIssue('2026-10-27', '', effective, 'Asia/Shanghai'), undefined)
  assert.match(release.allocationDateIssue('2026-10-26', '', effective, 'Asia/Shanghai'), /不能提前/)
  assert.match(release.allocationDateIssue('', '', effective, 'Asia/Shanghai'), /请选择/)
  assert.match(release.allocationDateIssue('2026-10-27', '2026-10-26', effective, 'Asia/Shanghai'), /不能早于/)
  assert.match(release.allocationDateIssue('2026-10-27', '', '', 'Asia/Shanghai'), /无法确认/)
  assert.match(release.allocationDateIssue('2026-10-27', '', effective, 'invalid'), /无法确认/)
})

test('overlap checks preserve prior allocation and consider assignment, target and inclusive dates', () => {
  const rows = [
    { id: 'active', assignmentId: 'worker', targetOrgUnitId: 'hotel', status: 'ACTIVE', validFrom: '2026-10-01', validTo: '2026-10-26' },
    { id: 'forever', assignmentId: 'worker', targetOrgUnitId: 'other', status: 'ACTIVE', validFrom: '2026-10-01' },
    { id: 'inactive', assignmentId: 'worker', targetOrgUnitId: 'hotel', status: 'INACTIVE', validFrom: '2026-01-01' },
  ]
  const original = structuredClone(rows)
  assert.deepEqual(release.conflictingAllocations(rows, 'worker', 'hotel', '2026-10-26').map(row => row.id), ['active'])
  assert.deepEqual(release.conflictingAllocations(rows, 'worker', 'hotel', '2026-10-27'), [])
  assert.deepEqual(release.conflictingAllocations(rows, 'worker', 'other', '2027-01-01').map(row => row.id), ['forever'])
  assert.deepEqual(release.conflictingAllocations(rows, 'someone', 'hotel', '2026-10-26'), [])
  assert.deepEqual(rows, original)
})

test('work package operations consistently use the selected version and independent configuration', () => {
  const source = readFileSync(new URL('../src/ConfigurationPages.tsx', import.meta.url), 'utf8')
  const section = source.slice(source.indexOf('export function WorkPackageCenter'))
  assert.match(section, /选择具体工作包版本/)
  assert.match(section, /versions\).find\(version => text\(version, 'id'\) === selectedVersionId\)/)
  assert.match(section, /\/versions\/\$\{versionId\}`/)
  assert.match(section, /const configuration = versionConfiguration \?\? \{\}/)
  assert.doesNotMatch(section, /detail.latestVersion|source.latestVersion|const latest = versions\[0\]/)
  assert.match(section, /refreshDetail\(packageId, selectedVersionId\)/)
})

test('allocation saves chosen dates, refreshes conflicts and never forces today generation or replacement', () => {
  const source = readFileSync(new URL('../src/ConfigurationPages.tsx', import.meta.url), 'utf8')
  const section = source.slice(source.indexOf('export function WorkPackageCenter'))
  assert.match(section, /validFrom: allocation.validFrom, validTo: allocation.validTo \|\| null/)
  assert.match(section, /currentAllocations = mapAllocations\(await apiRequest/)
  assert.match(section, /conflictingAllocations\(currentAllocations/)
  assert.doesNotMatch(section, /\/work-expectations\/actions\/generate/)
  assert.doesNotMatch(section, /effectiveFrom: new Date\(\).toISOString\(\)/)
  assert.match(section, /不代表今日已经生成/)
  assert.match(section, /canAllocate && !detail && <WorkDutyPeriodEditor/)
})
