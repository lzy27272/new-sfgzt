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

test('the shared footer renders one accessible official link and skips unconfigured local environments', () => {
  const html = renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber: '黔ICP备2026014300号-1' }))
  assert.match(html, /<footer class="site-icp-footer" aria-label="网站备案信息">/)
  assert.match(html, /href="https:\/\/beian\.miit\.gov\.cn\/" target="_blank" rel="noreferrer"/)
  assert.match(html, />黔ICP备2026014300号-1<\/a>/)
  assert.equal((html.match(/<a /g) ?? []).length, 1)
  for (const recordNumber of ['', '   ']) {
    assert.equal(renderToStaticMarkup(React.createElement(IcpFooter, { recordNumber })), '')
  }
})

test('login, session recovery and signed-in screens place the footer outside cards and the sidebar', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
  assert.equal((app.match(/<IcpFooter recordNumber=\{icpRecordNumber\} \/>/g) ?? []).length, 3)
  assert.equal((app.match(/<\/section>\s*<\/div>\s*<IcpFooter recordNumber=\{icpRecordNumber\} \/>\s*<\/main>/g) ?? []).length, 2)
  assert.match(app, /<\/main>\s*<IcpFooter recordNumber=\{icpRecordNumber\} \/>\s*<\/div>/)
  assert.doesNotMatch(app, /login-auth-icp|mobile-icp-footer|<a className="icp-record"/)
  assert.equal((app.match(/icpRecordNumber \? ' has-icp-footer' : ''/g) ?? []).length, 3)
})

test('the footer is full-width, readable and reserves space for content and mobile navigation', () => {
  const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
  const footer = styles.match(/\.site-icp-footer \{([^}]+)\}/)?.[1]
  assert.match(footer, /justify-content: center/)
  assert.match(footer, /position: fixed/)
  assert.match(footer, /right: 0; bottom: 0; left: 0/)
  assert.match(styles, /\.icp-record \{[^}]*color: #334f64; font-size: 15px/)
  assert.match(styles, /\.has-icp-footer \{ --site-footer-space: calc\(48px \+ env\(safe-area-inset-bottom,0px\)\)/)
  assert.match(styles, /\.shell \{[^}]*padding-bottom: var\(--site-footer-space,0px\)/)
  assert.match(styles, /\.has-icp-footer \.sidebar \{ bottom: var\(--site-footer-space\)/)
  assert.match(styles, /\.has-icp-footer \.mobile-bottom-nav \{[^}]*bottom: var\(--site-footer-space\)/)
  assert.match(styles, /--page-bottom-reserve: calc\(72px \+ var\(--site-footer-space\)\)/)
  assert.match(styles, /\.has-icp-footer \.mc-save-bar \{ bottom: var\(--page-bottom-reserve\)/)
  const procurementStyles = readFileSync(new URL('../src/features/procurement/procurement.module.css', import.meta.url), 'utf8')
  assert.match(procurementStyles, /bottom:calc\(8px \+ var\(--page-bottom-reserve,0px\)\)/)
  assert.match(styles, /\.icp-record:focus-visible \{ outline: 2px/)
})
