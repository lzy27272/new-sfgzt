import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { after, before, test } from 'node:test'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer, loadEnv } from 'vite'
import configure from '../vite.config.ts'

let vite, IcpFooter
before(async () => {
  vite = await createServer({ appType: 'custom', configFile: './vite.config.ts', logLevel: 'silent', mode: 'pilot', root: fileURLToPath(new URL('..', import.meta.url)), server: { middlewareMode: true } })
  ;({ IcpFooter } = await vite.ssrLoadModule('/src/shared/IcpFooter.tsx'))
})
after(async () => { await vite?.close() })

function withRecordNumber(value, run) {
  const previous = process.env.VITE_ICP_RECORD_NUMBER
  try {
    if (value === undefined) delete process.env.VITE_ICP_RECORD_NUMBER
    else process.env.VITE_ICP_RECORD_NUMBER = value
    return run()
  } finally {
    if (previous === undefined) delete process.env.VITE_ICP_RECORD_NUMBER
    else process.env.VITE_ICP_RECORD_NUMBER = previous
  }
}

test('Pilot config supplies the existing public ICP filing number without local secrets', () => {
  withRecordNumber(undefined, () => {
    const config = configure({ command: 'build', mode: 'pilot' })
    assert.equal(loadEnv('pilot', config.envDir).VITE_ICP_RECORD_NUMBER, '黔ICP备2026014300号-1')
    const committedEnv = readFileSync(new URL('../.env.pilot', import.meta.url), 'utf8')
    assert.match(committedEnv, /^VITE_ICP_RECORD_NUMBER=黔ICP备2026014300号-1\r?$/m)
  })
})

test('Pilot builds reject empty or whitespace-only deployment overrides', () => {
  for (const value of ['', ' ', '\t\n']) {
    withRecordNumber(value, () => {
      assert.throws(() => configure({ command: 'build', mode: 'pilot' }), /requires a non-empty VITE_ICP_RECORD_NUMBER/)
    })
  }
})

test('an explicit filing number override is preserved', () => {
  withRecordNumber('备案号测试值', () => {
    const config = configure({ command: 'build', mode: 'pilot' })
    assert.equal(loadEnv('pilot', config.envDir).VITE_ICP_RECORD_NUMBER, '备案号测试值')
  })
})

test('local development does not require production filing information', () => {
  withRecordNumber('', () => {
    assert.doesNotThrow(() => configure({ command: 'serve', mode: 'development' }))
    assert.doesNotThrow(() => configure({ command: 'build', mode: 'development' }))
  })
})

test('the shared footer renders filing information as non-interactive text and skips unconfigured local environments', () => {
  const html = renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber: '黔ICP备2026014300号-1' }))
  assert.match(html, /<footer class="site-icp-footer" aria-label="网站备案信息">/)
  assert.match(html, /<span class="icp-record">黔ICP备2026014300号-1<\/span>/)
  assert.doesNotMatch(html, /<a\b|href=|target=|role="link"|tabindex=/i)
  for (const recordNumber of ['', '   ']) {
    assert.equal(renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber })), '')
  }
})

test('sidebar and mobile variants also use plain text and skip empty configuration', () => {
  for (const placement of ['sidebar', 'mobile']) {
    const html = renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber: '黔ICP备2026014300号-1', placement }))
    assert.ok(html.includes(`class="site-icp-footer site-icp-footer--${placement}"`))
    assert.match(html, /aria-label="网站备案信息"/)
    assert.match(html, /<span class="icp-record">黔ICP备2026014300号-1<\/span>/)
    assert.doesNotMatch(html, /<a\b|href=|target=|role="link"|tabindex=/i)
    assert.equal(renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber: ' ', placement })), '')
  }
})

test('signed-in desktop places filing text inside the sidebar version box while login and mobile retain page footers', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
  assert.equal((app.match(/<IcpFooter recordNumber=\{icpRecordNumber\} \/>/g) ?? []).length, 2)
  assert.equal((app.match(/<\/section>\s*<\/div>\s*<IcpFooter recordNumber=\{icpRecordNumber\} \/>\s*<\/main>/g) ?? []).length, 2)
  assert.match(app, /<div className="sidebar-footer">\s*<span>\{product.version\}<\/span>\s*<small>标准 → 工作 → 任务 → 执行 → 验收<\/small>\s*<IcpFooter recordNumber=\{icpRecordNumber\} placement="sidebar" \/>\s*<\/div>/)
  assert.match(app, /<\/main>\s*<IcpFooter recordNumber=\{icpRecordNumber\} placement="mobile" \/>\s*<\/div>/)
  assert.doesNotMatch(app, /login-auth-icp|mobile-icp-footer|<a className="icp-record"/)
  assert.equal((app.match(/icpRecordNumber \? ' has-icp-footer' : ''/g) ?? []).length, 3)
})

test('login and mobile footers remain full-width, readable and reserve space for content and mobile navigation', () => {
  const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
  const footer = styles.match(/\.site-icp-footer \{([^}]+)\}/)?.[1]
  assert.match(footer, /justify-content: center/)
  assert.match(footer, /position: fixed/)
  assert.match(footer, /right: 0; bottom: 0; left: 0/)
  assert.match(styles, /\.icp-record \{[^}]*color: #334f64; font-size: 12px; font-weight: 400/)
  assert.match(styles, /\.sidebar-footer > span \{ font-size: 11px;/)
  assert.match(styles, /\.has-icp-footer \{ --site-footer-space: calc\(48px \+ env\(safe-area-inset-bottom,0px\)\)/)
  assert.match(styles, /\.shell \{[^}]*padding-bottom: var\(--site-footer-space,0px\)/)
  assert.match(styles, /\.has-icp-footer \.mobile-bottom-nav \{[^}]*bottom: var\(--site-footer-space\)/)
  assert.match(styles, /--page-bottom-reserve: calc\(72px \+ var\(--site-footer-space\)\)/)
  assert.match(styles, /\.has-icp-footer \.mc-save-bar \{ bottom: var\(--page-bottom-reserve\)/)
  const procurementStyles = readFileSync(new URL('../src/features/procurement/procurement.module.css', import.meta.url), 'utf8')
  assert.match(procurementStyles, /bottom:calc\(8px \+ var\(--page-bottom-reserve,0px\)\)/)
  assert.match(procurementStyles, /@media\(max-width:760px\)[^@]*bottom:calc\(8px \+ var\(--page-bottom-reserve,calc\(72px \+ env\(safe-area-inset-bottom\)\)\)\)/)
  assert.doesNotMatch(styles, /\.icp-record:(?:hover|focus-visible)/)
})

test('desktop footer stays in the version box without a page-bottom strip; mobile switches to its fixed footer', () => {
  const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
  assert.match(styles, /\.shell\.has-icp-footer \{ --site-footer-space: 0px;/)
  assert.match(styles, /\.sidebar-footer \{[^}]*flex-shrink: 0/)
  assert.match(styles, /\.site-icp-footer--sidebar \{[^}]*position: static;[^}]*background: transparent;/)
  assert.match(styles, /\.site-icp-footer--sidebar \.icp-record \{[^}]*color: #bed0dd;/)
  assert.match(styles, /\.site-icp-footer--mobile \{ display: none;/)
  assert.match(styles, /@media \(max-width: 820px\) \{\s*\.shell \{ display: block; \}\s*\.shell\.has-icp-footer \{ --site-footer-space: calc\(48px \+ env\(safe-area-inset-bottom,0px\)\); \}\s*\.site-icp-footer--mobile \{ display: flex;/)
})
