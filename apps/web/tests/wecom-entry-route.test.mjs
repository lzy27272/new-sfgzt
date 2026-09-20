import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import {
  buildAppHashLocation,
  consumeWecomSessionBootstrap,
  consumeWecomTaskEntry,
  safeTaskDeepLink,
} from '../src/features/wecom/entryRoute.ts'
import { buildWeComSilentAuthUrl, isWeComBrowser } from '../src/features/wecom/silentAuth.ts'

const taskEntrySource = await readFile(
  new URL('../src/features/wecom/WecomTaskEntry.tsx', import.meta.url),
  'utf8',
)
const appSource = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8')

test('企微回调只读取 exchange_code 并立即清除地址栏凭证', () => {
  let replaced = ''
  globalThis.window = {
    location: { origin: 'https://www.sfgzt.cn' },
    history: { replaceState: (_state, _title, url) => { replaced = url } },
  }
  const entry = consumeWecomTaskEntry({
    href: 'https://www.sfgzt.cn/wecom-auth?exchange_code=Abcd_efghijklmnopqrstuvwxyz0123456789-AB',
  })
  assert.equal(entry?.code, 'Abcd_efghijklmnopqrstuvwxyz0123456789-AB')
  assert.equal(replaced, '/wecom-auth')
})

test('企微深链允许工作台、岗位事项、站内任务及日报详情，并要求安全目标', () => {
  globalThis.window = { location: { origin: 'https://www.sfgzt.cn' } }
  assert.equal(
    safeTaskDeepLink('#/tasks?view=mine&taskId=123e4567-e89b-42d3-a456-426614174000'),
    '#/tasks?view=mine&taskId=123e4567-e89b-42d3-a456-426614174000',
  )
  assert.equal(
    safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000'),
    '#/daily-reports/123e4567-e89b-42d3-a456-426614174000',
  )
  assert.equal(safeTaskDeepLink('#/workbench'), '#/workbench')
  assert.equal(
    safeTaskDeepLink('#/my-work?expectationId=123e4567-e89b-42d3-a456-426614174000'),
    '#/my-work?expectationId=123e4567-e89b-42d3-a456-426614174000',
  )
  assert.throws(() => safeTaskDeepLink('https://evil.example/tasks?taskId=123e4567-e89b-42d3-a456-426614174000'))
  assert.throws(() => safeTaskDeepLink('#/notifications?taskId=123e4567-e89b-42d3-a456-426614174000'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/not-a-uuid'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000?next=https://evil.example'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000/extra'))
  assert.throws(() => safeTaskDeepLink('#/daily-reports/123e4567-e89b-42d3-a456-426614174000#extra'))
  assert.throws(() => safeTaskDeepLink('#/workbench?next=/admin'))
  assert.throws(() => safeTaskDeepLink('#/my-work'))
  assert.throws(() => safeTaskDeepLink('#/my-work?expectationId=not-a-uuid'))
  assert.throws(() => safeTaskDeepLink('#/my-work?expectationId=123e4567-e89b-42d3-a456-426614174000&next=/admin'))
})

test('完成和取消都回到应用根路径，不保留 wecom-auth pathname', () => {
  assert.equal(buildAppHashLocation('#/tasks?taskId=1', '/'), '/#/tasks?taskId=1')
  assert.equal(buildAppHashLocation('#/', '/console/'), '/console/#/')
  assert.equal(buildAppHashLocation('#/tasks', '//evil.example/'), '/#/tasks')
})

test('顶层换票跳转建立非敏感会话标记并立即清理地址栏', () => {
  let replaced = ''
  let established = 0
  globalThis.window = {
    location: { origin: 'https://www.sfgzt.cn' },
    history: { replaceState: (_state, _title, url) => { replaced = url } },
  }

  const consumed = consumeWecomSessionBootstrap(
    () => { established += 1 },
    { href: 'https://www.sfgzt.cn/?wecom_session=1#/my-work?expectationId=123e4567-e89b-42d3-a456-426614174000' },
  )

  assert.equal(consumed, true)
  assert.equal(established, 1)
  assert.equal(replaced, '/#/my-work?expectationId=123e4567-e89b-42d3-a456-426614174000')
})

test('企微会话由当前页面直接认领，不依赖 WebView 存储是否可用', () => {
  assert.match(appSource, /const initialWecomSessionBootstrap = consumeWecomSessionBootstrap/)
  assert.match(appSource, /initialWecomSessionBootstrap \|\| hasAccessToken\(\)/)
})

test('企微正常入口支持静默登录，同时为手动登录选择保留回退入口', () => {
  assert.equal(isWeComBrowser('Mozilla/5.0 wxwork/4.1.30 MicroMessenger'), true)
  assert.equal(isWeComBrowser('Mozilla/5.0 Chrome/128.0'), false)
  assert.equal(
    buildWeComSilentAuthUrl(),
    '/api/v1/integrations/wecom/oauth/start?returnTo=%23%2Fworkbench',
  )
  assert.match(appSource, /isWeComBrowser\(\)/)
  assert.match(appSource, /!authenticated && !manualLogin && authMode === 'bearer' && isWeComBrowser\(\)/)
  assert.match(appSource, /<WeComSilentLoginRedirect onReturnToLogin=/)
})

test('伪造或重复会话标记会被清理但不会建立登录状态', () => {
  let established = 0
  globalThis.window = {
    location: { origin: 'https://www.sfgzt.cn' },
    history: { replaceState: () => undefined },
  }

  assert.equal(consumeWecomSessionBootstrap(
    () => { established += 1 },
    { href: 'https://www.sfgzt.cn/?wecom_session=1&wecom_session=1#/workbench' },
  ), false)
  assert.equal(established, 0)
})

test('企微任务入口以一次性码建立内存会话并保留 Cookie 辅助通道', () => {
  assert.match(taskEntrySource, /exchangeWecomCode\(entry\.code\)/)
  assert.match(taskEntrySource, /safeTaskDeepLink\(session\.returnTo\)/)
  assert.match(taskEntrySource, /establishFederatedSession\(session\.accessToken, session\.expiresAt\)/)
  assert.match(taskEntrySource, /onAuthenticated\(\)/)
  assert.doesNotMatch(taskEntrySource, /browser-exchange/)
  assert.doesNotMatch(taskEntrySource, /localStorage/)
  assert.match(appSource, /const completeWecomTaskEntry = useCallback/)
  assert.match(appSource, /onAuthenticated=\{completeWecomTaskEntry\}/)
})
