import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const clientSource = await readFile(
  new URL('../src/api/client.ts', import.meta.url),
  'utf8',
)
const pilotEnvironment = await readFile(
  new URL('../.env.pilot', import.meta.url),
  'utf8',
)
const releaseScript = await readFile(
  new URL('../../../tools/release/New-ReleaseArtifacts.ps1', import.meta.url),
  'utf8',
)
const continuousIntegration = await readFile(
  new URL('../../../.github/workflows/ci.yml', import.meta.url),
  'utf8',
)
const productionInstaller = await readFile(
  new URL('../../../infra/production/bootstrap/60-install-release.sh', import.meta.url),
  'utf8',
)

test('production authenticated requests default to the Caddy token header', () => {
  assert.match(
    clientSource,
    /const BEARER_HEADER = import\.meta\.env\.VITE_BEARER_HEADER \?\? 'X-Hotel-AI-Authorization'/,
  )
})

test('request assignment header uses the canonical business actor field', () => {
  assert.match(clientSource, /businessActorAssignmentId\?: string/)
  assert.match(clientSource, /identity\.businessActorAssignmentId/)
  assert.doesNotMatch(clientSource, /identity\.assignmentId/)
})

test('unauthorized real sessions clear both client state and the HttpOnly cookie', () => {
  assert.equal(
    clientSource.match(/shouldExpireAuthenticatedSession\(AUTH_MODE, response\.status\)/g)?.length,
    2,
  )
  assert.equal(clientSource.match(/expireAuthenticatedSession\(requestSessionGeneration\)/g)?.length, 2)
  assert.match(clientSource, /fetch\(`\$\{API_BASE\}\/auth\/logout`/)
  assert.match(clientSource, /window\.dispatchEvent\(new Event\('hotel-ai-os:auth-expired'\)\)/)
})

test('server-cookie mode can recover through local login without stale 401 logout races', () => {
  assert.match(clientSource, /Authorization: `Bearer \$\{ephemeralAccessToken\}`/)
  assert.equal(clientSource.match(/const requestSessionGeneration = sessionGeneration/g)?.length, 2)
  assert.match(clientSource, /if \(requestSessionGeneration !== sessionGeneration\) return/)
  assert.match(clientSource, /sessionGeneration \+= 1/)
})

test('Pilot builds fail closed to token authentication without demo fallback', () => {
  assert.match(pilotEnvironment, /^VITE_AUTH_MODE=bearer$/m)
  assert.match(pilotEnvironment, /^VITE_BEARER_HEADER=X-Hotel-AI-Authorization$/m)
  assert.match(pilotEnvironment, /^VITE_ENABLE_DEMO_FALLBACK=false$/m)
  assert.match(pilotEnvironment, /^VITE_ALLOW_DEMO_QUERY=false$/m)
  assert.match(pilotEnvironment, /^VITE_DEMO_ONLY=false$/m)
  assert.match(pilotEnvironment, /^VITE_PRODUCT_VERSION=TECH-V0\.2-PILOT\.8$/m)
  assert.doesNotMatch(pilotEnvironment, /(^|=)(dev-header|server|true)$/m)
})

test('release artifacts are built in Pilot mode and reject an unauthenticated bundle', () => {
  assert.match(releaseScript, /& \$pnpm 'build:pilot'/)
  assert.match(releaseScript, /X-Hotel-AI-Authorization/)
  assert.match(releaseScript, /does not enforce the Pilot bearer-login contract/)
  assert.match(continuousIntegration, /run: pnpm build:pilot/)
  assert.match(continuousIntegration, /grep -R --include='\*\.js' -Fq 'X-Hotel-AI-Authorization' dist\/assets/)
  assert.match(productionInstaller, /grep -R --include='\*\.js' -Fq 'X-Hotel-AI-Authorization'/)
  assert.match(productionInstaller, /Refusing to install a Web bundle without the Pilot bearer-login contract/)
})
