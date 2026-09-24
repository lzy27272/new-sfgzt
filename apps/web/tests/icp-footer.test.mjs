import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { loadEnv } from 'vite'
import configure from '../vite.config.ts'

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

test('login, session recovery and desktop/mobile signed-in shells retain the official filing links', () => {
  const app = readFileSync(new URL('../src/App.tsx', import.meta.url), 'utf8')
  const links = app.match(/<a className="icp-record(?: login-auth-icp)?"[^>]+>\{icpRecordNumber\}<\/a>/g)
  assert.equal(links?.length, 4)
  for (const link of links) {
    assert.match(link, /href="https:\/\/beian\.miit\.gov\.cn\/"/)
    assert.match(link, /target="_blank"/)
    assert.match(link, /rel="noreferrer"/)
  }
  assert.match(app, /<footer className="mobile-icp-footer">/)
  const styles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
  assert.match(styles, /\.mobile-icp-footer \{ display: none;/)
  assert.match(styles.slice(styles.indexOf('@media (max-width: 820px)')), /\.mobile-icp-footer \{ display: block;/)
})
