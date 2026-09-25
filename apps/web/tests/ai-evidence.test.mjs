import test from 'node:test'
import assert from 'node:assert/strict'
import { emptyConfig, validateConfig, latestChecks, statusText, failureText } from '../src/features/aiEvidence/model.ts'

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
test('configuration defaults are independent and exclude makeup and identity recognition', () => {
  const a = emptyConfig(), b = emptyConfig()
  a.criteria[0].standard = 'modified'
  assert.equal(b.criteria[0].standard, '')
  assert.deepEqual(b.criteria.map(item => item.code), ['UNIFORM', 'BADGE', 'EQUIPMENT'])
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
