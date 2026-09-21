import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  buildDataRetentionUpdateRequest,
  dataRetentionDraftFrom,
  formatRetentionBytes,
  hasDataRetentionAdministrationRole,
  parseDataRetentionPolicy,
  parseDataRetentionPreview,
  validateDataRetentionDraft,
} from '../src/features/dataRetention/policy.ts'

const appSource = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
const routeSource = readFileSync(new URL('../src/app/routeConfig.ts', import.meta.url), 'utf8')
const domainSource = readFileSync(new URL('../src/domain.ts', import.meta.url), 'utf8')
const apiSource = readFileSync(new URL('../src/features/dataRetention/api.ts', import.meta.url), 'utf8')
const pageSource = readFileSync(new URL('../src/features/dataRetention/DataRetentionAdministration.tsx', import.meta.url), 'utf8')

const policyPayload = {
  imageRetentionDays: 5,
  documentRetentionDays: 30,
  employeeMetricRetentionMonths: 36,
  pmsRetentionMonths: 36,
  otaRetentionMonths: 36,
  kpiRetentionMonths: 36,
  rawPayloadRetentionDays: 90,
  imageCleanupEnabled: false,
  version: 3,
  updatedAt: '2026-09-17T08:30:00Z',
  updatedBy: 'admin-1',
}

test('data retention route is registered with the legacy hash router', () => {
  assert.match(domainSource, /\| 'data-retention'/)
  assert.match(routeSource, /const legacyViews[\s\S]*'data-retention'/)
})

test('only exact platform and group administrator roles can see retention administration', () => {
  assert.equal(hasDataRetentionAdministrationRole(['PLATFORM_ADMIN']), true)
  assert.equal(hasDataRetentionAdministrationRole(['GROUP_ADMIN']), true)
  assert.equal(hasDataRetentionAdministrationRole(['CEO', 'SYSTEM_ADMIN']), false)
  assert.equal(hasDataRetentionAdministrationRole(['platform-admin', '平台管理员']), false)
})

test('policy parser accepts the frozen backend contract and creates editable values', () => {
  const policy = parseDataRetentionPolicy(policyPayload)
  assert.deepEqual(policy, policyPayload)
  assert.deepEqual(dataRetentionDraftFrom(policy), {
    imageRetentionDays: '5',
    documentRetentionDays: '30',
    employeeMetricRetentionMonths: '36',
    pmsRetentionMonths: '36',
    otaRetentionMonths: '36',
    kpiRetentionMonths: '36',
    rawPayloadRetentionDays: '90',
    imageCleanupEnabled: false,
  })
  assert.throws(() => parseDataRetentionPolicy({ ...policyPayload, imageCleanupEnabled: 'false' }), /INVALID/)
  assert.throws(() => parseDataRetentionPolicy({ ...policyPayload, imageRetentionDays: 366 }), /INVALID/)
})

test('policy updates enforce every server boundary and carry optimistic version', () => {
  const draft = dataRetentionDraftFrom(parseDataRetentionPolicy(policyPayload))
  assert.deepEqual(validateDataRetentionDraft(draft), {})
  assert.deepEqual(buildDataRetentionUpdateRequest(draft, 3), {
    imageRetentionDays: 5,
    documentRetentionDays: 30,
    employeeMetricRetentionMonths: 36,
    pmsRetentionMonths: 36,
    otaRetentionMonths: 36,
    kpiRetentionMonths: 36,
    rawPayloadRetentionDays: 90,
    imageCleanupEnabled: false,
    expectedVersion: 3,
  })
  assert.match(validateDataRetentionDraft({ ...draft, imageRetentionDays: '0' }).imageRetentionDays, /1 至 365/)
  assert.match(validateDataRetentionDraft({ ...draft, documentRetentionDays: '3651' }).documentRetentionDays, /1 至 3650/)
  assert.match(validateDataRetentionDraft({ ...draft, kpiRetentionMonths: '12.5' }).kpiRetentionMonths, /整数/)
  assert.throws(() => buildDataRetentionUpdateRequest(draft, -1), /VERSION_INVALID/)
})

test('preview parser accepts omitted empty timestamps and formats capacity', () => {
  assert.deepEqual(parseDataRetentionPreview({
    candidateCount: 0,
    totalSizeBytes: 0,
    generatedAt: '2026-09-17T09:00:00Z',
    cleanupEnabled: false,
  }), {
    candidateCount: 0,
    totalSizeBytes: 0,
    oldestRetentionUntil: null,
    newestRetentionUntil: null,
    generatedAt: '2026-09-17T09:00:00Z',
    cleanupEnabled: false,
  })
  assert.equal(formatRetentionBytes(0), '0 B')
  assert.equal(formatRetentionBytes(1536), '1.50 KB')
  assert.equal(formatRetentionBytes(15 * 1024 * 1024), '15.0 MB')
})

test('frontend uses the agreed read/update endpoints without a destructive request', () => {
  assert.match(apiSource, /'\/admin\/data-retention-policy'/)
  assert.match(apiSource, /'\/admin\/data-retention-preview'/)
  assert.match(apiSource, /method: 'PUT'/)
  assert.doesNotMatch(apiSource, /method:\s*['"]DELETE['"]/)
})

test('administrator navigation and direct-link boundary are explicit', () => {
  const navigationSource = readFileSync(new URL('../src/app/navigationAccess.ts', import.meta.url), 'utf8')
  assert.match(navigationSource, /id: 'data-retention'.*administratorOnly: true/)
  assert.match(appSource, /hasDataRetentionAdministrationRole\(me\.data\.roleCodes\)/)
  assert.match(appSource, /view !== 'data-retention' \|\| isDataRetentionAdministrator/)
  assert.match(appSource, /<DataRetentionAdministration identity=\{activeIdentity\}/)
})

test('page states both execution gates and exposes no immediate delete action', () => {
  assert.match(pageSource, /双重开关控制，仅清理到期普通图片/)
  assert.match(pageSource, /预览不会删除文件/)
  assert.match(pageSource, /从上传成功起计算；重要证据标记后暂停清理/)
  assert.doesNotMatch(pageSource, /<button[^>]*>[^<]*(?:立即删除|执行删除|开始清理)[^<]*<\/button>/)
})
