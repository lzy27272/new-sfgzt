import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyConfig, normalizeConfig, ruleStatus, withSavedRule, validateConfig, latestChecks, statusText, failureText } from '../src/features/aiEvidence/model.ts'

test('AI configuration requires explicit scope and objective standards', () => {
  const config = emptyConfig()
  assert.equal(validateConfig(config, ''), '请选择适用岗位')
  assert.equal(validateConfig(config, 'position'), '请选择至少一家门店')
  config.hotelIds = ['hotel']
  assert.equal(validateConfig(config, 'position'), '请选择要检查的原工作项')
  config.itemCodes = ['GROOMING']
  assert.equal(validateConfig(config, 'position'), '请填写启用项目的明确规范')
  config.criteria = [{ code: 'BADGE', enabled: true, standard: '佩戴门店规定工牌' }]
  assert.equal(validateConfig(config, 'position'), undefined)
})
test('configuration defaults are independent and makeup requires explicit opt-in', () => {
  const a = emptyConfig(), b = emptyConfig()
  a.criteria[0].standard = 'modified'
  assert.equal(b.criteria[0].standard, '')
  assert.deepEqual(b.criteria.map(item => item.code), ['UNIFORM', 'BADGE', 'EQUIPMENT', 'MAKEUP'])
  assert.equal(b.criteria[3].enabled, false)
  assert.equal(b.criteria[3].standard, '')
})
test('legacy rules load all editable criteria without silently enabling new checks', () => {
  const legacy = { hotelIds: ['h'], itemCodes: ['w'], criteria: [{ code: 'BADGE', enabled: true, standard: '佩戴工牌' }] }
  const result = normalizeConfig(legacy)
  assert.deepEqual(result.criteria.filter(c => c.enabled).map(c => c.code), ['BADGE'])
  assert.equal(result.criteria.find(c => c.code === 'MAKEUP').standard, '')
  result.criteria[1].standard = 'changed'
  assert.equal(legacy.criteria[0].standard, '佩戴工牌')
  assert.deepEqual(result.sampleIds, [])
})
test('enabling makeup requires an explicit standard and preserves configured exceptions', () => {
  const config = normalizeConfig({ hotelIds: ['h'], itemCodes: ['w'], criteria: [{ code: 'MAKEUP', enabled: true, standard: '' }], exceptions: '已批准合理例外' })
  assert.equal(validateConfig(config, 'position'), '请填写启用项目的明确规范')
  config.criteria.find(c => c.code === 'MAKEUP').standard = '按确认的妆面整洁规范'
  assert.equal(validateConfig(config, 'position'), undefined)
  assert.equal(config.exceptions, '已批准合理例外')
})
test('draft and enabled versions are distinct; saving a draft preserves the active rule', () => {
  const active = { id: 'r1', version_no: 1, state: 'SHADOW' }
  const draft = { id: 'r2', version_no: 2, state: 'DRAFT' }
  const rows = withSavedRule([active], draft)
  assert.equal(rows[1].state, 'SHADOW')
  assert.match(ruleStatus(rows, false).title, /草稿 V2.*尚未启用/)
  assert.match(ruleStatus(rows, false).detail, /仍使用 V1/)
  assert.match(ruleStatus(rows, true).title, /未保存/)
  const enabled = { id: 'r3', version_no: 3, state: 'SHADOW' }
  const saved = withSavedRule(rows, enabled)
  assert.equal(saved.find(r => r.id === 'r1').state, 'RETIRED')
  assert.equal(active.state, 'SHADOW')
  assert.match(ruleStatus(saved, false).title, /已启用 V3/)
  assert.match(ruleStatus(saved, false).detail, /不追溯扫描/)
  assert.match(ruleStatus([draft], false).title, /尚未启用/)
  assert.match(ruleStatus([], false).title, /尚未启用/)
})
test('retakes only replace their linked evidence check in the current list', () => {
  const rows = [{ id: '3', supersedes_id: '2' }, { id: '2', supersedes_id: '1' }, { id: 'other' }, { id: '1' }]
  assert.deepEqual(latestChecks(rows).map(row => row.id), ['3', 'other'])
  assert.equal(rows.length, 4)
})
test('failure and uncertain states do not misrepresent employees as non-compliant', () => {
  assert.equal(statusText.UNKNOWN, '无法确认')
  assert.equal(statusText.CONCERN, '疑似不符合')
  assert.match(failureText.MODEL_NOT_CONFIGURED, /人工复核/)
  assert.match(failureText.WORKER_INTERRUPTED, /不影响原工作提交时间/)
  assert.match(failureText.DAILY_LIMIT, /限额/)
})
