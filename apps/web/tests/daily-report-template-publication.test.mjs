import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { publicationStart } from '../src/features/workPackages/publicationTiming.ts'

async function apiHarness(fixture) {
  const source = await readFile(new URL('../src/features/dailyReportTemplates/api.ts', import.meta.url), 'utf8')
  const code = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const requests = [], module = { exports: {} }
  runInNewContext(code, {
    module, exports: module.exports,
    require(path) {
      if (path === '../shared/featureApi') return {
        featureApiRequest: async () => fixture,
        featureApiMutation: async (url, identity, options) => {
          requests.push({ url, identity, options })
          return { ...fixture.versions[0], configuration: options.body, rowVersion: 1 }
        },
      }
      if (path === '../shared/apiEnvelope') return { queryString: () => '', requireItems: value => value }
      throw new Error(`Unexpected import ${path}`)
    },
  })
  return { api: module.exports, requests }
}

const fixture = {
  id: '123e4567-e89b-42d3-a456-426614174000', name: '夜班日报', templateOrigin: 'HQ', publicationTimeZone: 'America/New_York',
  versions: [{ id: '123e4567-e89b-42d3-a456-426614174001', versionNo: 2, lifecycleStatus: 'DRAFT', rowVersion: 0,
    configuration: { title: '夜班日报', deliveryTiming: { mode: 'SHIFT_END', openOffsetMinutes: -30, dueOffsetMinutes: 15 },
      sections: [{ id: '123e4567-e89b-f2d3-2456-426614174002', sectionCode: 'night', title: '工作汇总', sectionOrigin: 'HQ',
        items: [
          { id: '123e4567-e89b-42d3-a456-426614174003', itemCode: 'facts', label: '来源工作', valueType: 'WORK_RECORD_REFERENCE', dataSourceType: 'WORK_RECORD', dataSourceConfig: { mode: 'AUTO_APPROVED' } },
          { id: '123e4567-e89b-42d3-a456-426614174004', itemCode: 'analysis', label: 'AI分析', valueType: 'LONG_TEXT', dataSourceType: 'AI_SUMMARY', dataSourceConfig: { section: 'ANALYSIS' } },
          { id: '123e4567-e89b-42d3-a456-426614174005', itemCode: 'metric', label: '指标', valueType: 'METRIC_REFERENCE', dataSourceType: 'SYSTEM' },
        ],
      }],
    },
  }],
}
const identity = { tenantId: 'tenant', actorId: 'reviewer', roleCode: 'PLATFORM_ADMIN', orgScopes: [] }

test('delivery-time edits preserve exact source and value types through normalize/save', async () => {
  const { api, requests } = await apiHarness(fixture)
  const detail = await api.loadDailyReportTemplate(identity, fixture.id)
  const version = detail.versions[0]
  assert.equal(version.publicationTimeZone, 'America/New_York')
  await api.saveDailyReportTemplateVersion(identity, fixture.id, version, {
    title: version.title, sections: version.sections,
    deliveryTiming: { mode: 'SHIFT_END', openOffsetMinutes: -45, dueOffsetMinutes: 15 },
  }, 'save-key')
  const body = requests[0].options.body
  assert.equal(body.sections[0].id, fixture.versions[0].configuration.sections[0].id)
  assert.equal(body.deliveryTiming.openOffsetMinutes, -45)
  assert.equal(body.sections[0].items[0].dataSourceType, 'WORK_RECORD')
  assert.equal(body.sections[0].items[0].dataSourceConfig.mode, 'AUTO_APPROVED')
  assert.equal(body.sections[0].items[1].dataSourceType, 'AI_SUMMARY')
  assert.equal(body.sections[0].items[1].dataSourceConfig.section, 'ANALYSIS')
  assert.equal(body.sections[0].items[2].valueType, 'METRIC_REFERENCE')
  assert.equal(body.sections[0].items[2].dataSourceType, 'SYSTEM')
  assert.equal(body.effectiveFrom, undefined)
})

test('publish explicitly carries the chosen tenant-midnight instant instead of stale version dates', async () => {
  const { api, requests } = await apiHarness(fixture)
  const { versions: [version] } = await api.loadDailyReportTemplate(identity, fixture.id)
  const chosen = publicationStart('2030-06-01', version.publicationTimeZone)
  await api.transitionDailyReportTemplateVersion(identity, fixture.id, version, 'publish', 'publish-key', chosen)
  assert.equal(requests[0].options.body.effectiveFrom, '2030-06-01T04:00:00.000Z')
  assert.equal(requests[0].options.expectedVersion, 0)
  const editor = await readFile(new URL('../src/features/dailyReportTemplates/DailyReportTemplateRoutes.tsx', import.meta.url), 'utf8')
  assert.match(editor, /publicationStart\(draft\.effectiveFrom\?\.slice\(0, 10\)/)
  assert.match(editor, /disabled=\{!canEdit && !canPublish\}/)
})
