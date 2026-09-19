import assert from 'node:assert/strict'
import test from 'node:test'
import { withRequestDeadline } from '../src/api/requestDeadline.ts'
import { resolveNotificationNavigation } from '../src/app/notificationNavigation.ts'

let instance = 0
async function consume(query) {
  const route = await import(`../src/features/wecom/entryRoute.ts?errors=${++instance}`)
  let replaced
  globalThis.window = {
    location: { origin: 'https://www.sfgzt.cn' },
    history: { replaceState: (_state, _title, url) => { replaced = url } },
  }
  const result = route.consumeWecomTaskEntry({ href: `https://www.sfgzt.cn/wecom-auth?${query}` })
  assert.equal(replaced, '/wecom-auth')
  return { result, route }
}

test('未绑定与失效任职提示真实处理方法，不再一律提示凭证过期', async () => {
  const { result, route } = await consume('error_code=MEMBER_UNBOUND')
  assert.match(result.securityError, /尚未绑定/)
  assert.match(result.securityError, /无需重复注册/)
  assert.equal(result.code, undefined)
  assert.equal(route.wecomLoginRestartPath(), '/api/v1/integrations/wecom/oauth/start?returnTo=%23%2Fworkbench')
  assert.match((await consume('error_code=ASSIGNMENT_INACTIVE')).result.securityError, /默认任职已失效/)
  assert.match((await consume('error_code=ACCOUNT_UNAVAILABLE')).result.securityError, /账号或任职当前不可用/)
  assert.match((await consume('error_code=SESSION_INVALID')).result.securityError, /重新通过企业微信验证/)
})

test('错误入口只接受固定错误码，拒绝混合凭证、重复参数及外部跳转', async () => {
  for (const query of [
    'exchange_code=Abcd_efghijklmnopqrstuvwxyz&error_code=MEMBER_UNBOUND',
    'error_code=MEMBER_UNBOUND&error_code=SESSION_INVALID',
    'error_code=MEMBER_UNBOUND&returnTo=https://evil.example',
    'exchange_code=Abcd_efghijklmnopqrstuvwxyz&exchange_code=Abcd_efghijklmnopqrstuvwxyz',
  ]) {
    const { result } = await consume(query)
    assert.equal(result.code, undefined)
    assert.match(result.securityError, /重复或非预期参数/)
  }
  for (const query of ['error_code=private-secret', 'error_code=__proto__']) {
    const { result } = await consume(query)
    assert.match(result.securityError, /暂未完成/)
    assert.doesNotMatch(result.securityError, /private-secret|__proto__/)
  }
})

test('身份读取正常完成，失败原样返回，不额外重试一次性换票', async () => {
  let calls = 0
  assert.equal(await withRequestDeadline(async () => { calls++; return 'identity' }, 'timeout', 100), 'identity')
  assert.equal(calls, 1)
  const original = new Error('unbound')
  await assert.rejects(withRequestDeadline(async () => { throw original }, 'timeout', 100), (error) => error === original)
})

test('WebView 请求无响应或忽略取消，也会退出无限加载并给出可重试提示', async () => {
  let signal
  await assert.rejects(withRequestDeadline((input) => {
    signal = input
    return new Promise(() => {})
  }, '身份读取超时，请重试', 10), /身份读取超时，请重试/)
  assert.equal(signal.aborted, true)
})

test('管理督办通知进入工作台，员工自己的任务仍进入填报页面', () => {
  assert.deepEqual(resolveNotificationNavigation({ type: 'WORK_EXPECTATION_SUPERVISION', sourceType: 'WORK_EXPECTATION', sourceId: 'work-1' }),
    { view: 'workbench', params: { scope: 'all' }, actionLabel: '查看督办' })
  assert.deepEqual(resolveNotificationNavigation({ type: 'WORK_EXPECTATION_REMINDER', sourceType: 'WORK_EXPECTATION', sourceId: 'work-1' }),
    { view: 'my-work', params: { expectationId: 'work-1' }, actionLabel: '去完成' })
})
