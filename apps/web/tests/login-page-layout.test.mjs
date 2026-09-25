import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const appSource = await readFile(new URL('../src/App.tsx', import.meta.url), 'utf8')
const styleSource = await readFile(new URL('../src/styles.css', import.meta.url), 'utf8')
const loginSource = appSource.slice(
  appSource.indexOf('function LoginPage'),
  appSource.indexOf('function ChangePasswordDialog'),
)

test('account login uses the simplified dedicated surface without changing auth behavior', () => {
  assert.match(loginSource, /className=\{`login-auth-screen\$\{/)
  assert.match(loginSource, /className="login-auth-layout"/)
  assert.match(loginSource, /aria-label="中台账号登录"/u)
  assert.match(loginSource, /autoComplete="username"/)
  assert.match(loginSource, /autoComplete="current-password"/)
  assert.match(loginSource, /role="alert" aria-live="polite"/)
  assert.match(loginSource, /aria-busy=\{busy\}/)
  assert.match(loginSource, /disabled=\{busy\}/)
  assert.match(loginSource, /aria-invalid=\{Boolean\(error\)\}/)
  assert.match(loginSource, /await login\(loginName, password\)/)
  assert.doesNotMatch(loginSource, /panel-kicker/)
})

test('login layout has explicit desktop and mobile contracts', () => {
  assert.match(styleSource, /\.login-auth-layout\s*\{[^}]*grid-template-columns:/s)
  assert.match(styleSource, /@media \(max-width: 820px\)[\s\S]*?\.login-auth-layout\s*\{[^}]*flex-direction: column;/)
  assert.match(styleSource, /\.login-auth-card input\s*\{[^}]*font-size: 14px;/s)
  assert.match(styleSource, /\.login-auth-screen\s*\{[^}]*background: #f4f7f9;/s)
  assert.match(styleSource, /\.login-auth-screen\s*\{[^}]*min-height: 100vh;[^}]*min-height: 100dvh;/s)
  assert.match(styleSource, /\.icp-record\s*\{[^}]*color: #334f64;[^}]*font-size: 12px;[^}]*font-weight: 400;/s)
  assert.match(loginSource, /<\/div>\s*<IcpFooter recordNumber=\{icpRecordNumber\} \/>\s*<\/main>/)
})

test('login and session validation share the requested workbench title without pilot copy', () => {
  assert.match(appSource, /const loginWorkbenchTitle = '贵州四方馆酒店管理有限公司工作台'/u)
  assert.equal((loginSource.match(/<h1>\{loginWorkbenchTitle\}<\/h1>/g) ?? []).length, 2)
  assert.equal((loginSource.match(/aria-label=\{loginWorkbenchTitle\}/g) ?? []).length, 2)
  assert.doesNotMatch(loginSource, /product\.(?:editionLabel|version|name)|login-auth-version|以真实账号进入门店管理闭环|关键操作均记录|内部测试系统/u)
  assert.match(loginSource, /<strong>\{product.shortName\}<\/strong>/)
  assert.match(appSource, /<div className="sidebar-footer">\s*<span>\{product.version\}<\/span>/)
})

test('WeCom enrollment surfaces remain on their existing login-screen contract', () => {
  assert.match(appSource, /className=\{`login-auth-screen\$\{/)
  assert.doesNotMatch(loginSource, /className="login-screen"/)
  assert.match(styleSource, /\.login-screen\s*\{/)
})
