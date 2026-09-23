import type { NavigationParams, RouteParams, ViewId } from '../domain'
import { permissions } from './permissions'

export type DailyFeatureRouteId =
  | 'daily-reports-my'
  | 'daily-reports-team'
  | 'daily-report-detail'
  | 'daily-report-correction'
  | 'daily-report-templates'
  | 'daily-report-template-detail'
  | 'daily-report-template-version'
  | 'daily-operations'
  | 'daily-operation-action-items'
  | 'daily-operation-issues'
  | 'daily-operation-issue-detail'
  | 'daily-operation-snapshots'
  | 'daily-operation-snapshot-detail'
  | 'daily-operation-exports'
  | 'kpi-center'
  | 'kpi-scorecards'
  | 'kpi-scorecard-detail'
  | 'kpi-templates'
  | 'kpi-template-detail'
  | 'kpi-relations'
  | 'kpi-settlements'
  | 'kpi-inspections'

export type InvestmentFeatureRouteId =
  | 'investments'
  | 'investment-project'
  | 'investment-parameters'
  | 'investment-professional'

export type AppRouteId = ViewId | DailyFeatureRouteId | InvestmentFeatureRouteId
export type AppSectionId = ViewId | 'daily-reports' | 'daily-report-templates' | 'daily-operations' | 'kpi' | 'investments'
export type AppNavigate = (view: AppRouteId, params?: NavigationParams) => void

export type AppRoute = {
  view: AppRouteId
  params: RouteParams
  sectionId: AppSectionId
  explicit: boolean
}

type RouteDefinition = {
  id: DailyFeatureRouteId | InvestmentFeatureRouteId
  pattern: string
  sectionId: Extract<AppSectionId, 'daily-reports' | 'daily-report-templates' | 'daily-operations' | 'kpi' | 'investments'>
  requiredAny: readonly string[]
  requiredAll?: readonly string[]
}

const legacyViews: readonly ViewId[] = [
  'workbench',
  'all-functions',
  'account-self-service',
  'hotel-dashboard',
  'operations-dashboard',
  'work-packages',
  'my-work',
  'team-work',
  'rules',
  'tasks',
  'evaluations',
  'notifications',
  'templates',
  'organization',
  'store-resources',
  'finance',
  'procurement',
  'data-retention',
  'ai-data',
  'wecom-webhooks',
  'wecom-bindings',
  'wecom-onboarding',
]

const legacyViewSet = new Set<string>(legacyViews)

export const dailyFeatureRoutes: readonly (RouteDefinition & { id: DailyFeatureRouteId })[] = [
  {
    id: 'kpi-scorecard-detail',
    pattern: '/kpi/scorecards/:scorecardId',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.scorecardReadOwn, permissions.kpi.scorecardReadTeam, permissions.kpi.scorecardReadAll],
  },
  {
    id: 'kpi-scorecards',
    pattern: '/kpi/scorecards',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.scorecardReadOwn, permissions.kpi.scorecardReadTeam, permissions.kpi.scorecardReadAll],
  },
  {
    id: 'kpi-template-detail',
    pattern: '/kpi/templates/:templateId',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.templateRead, permissions.kpi.templateManage],
  },
  {
    id: 'kpi-templates',
    pattern: '/kpi/templates',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.templateRead, permissions.kpi.templateManage],
  },
  {
    id: 'kpi-relations',
    pattern: '/kpi/relations',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.relationManage, permissions.kpi.scorecardReadTeam, permissions.kpi.scorecardReadAll],
  },
  {
    id: 'kpi-settlements',
    pattern: '/kpi/settlements',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.settlementRead, permissions.kpi.settlementManage],
  },
  {
    id: 'kpi-inspections',
    pattern: '/kpi/inspections',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.inspectionSubmit, permissions.kpi.inspectionReadTeam, permissions.kpi.inspectionVerify],
  },
  {
    id: 'kpi-center',
    pattern: '/kpi',
    sectionId: 'kpi',
    requiredAny: [permissions.kpi.scorecardReadOwn, permissions.kpi.scorecardReadTeam, permissions.kpi.scorecardReadAll, permissions.kpi.templateRead],
  },
  {
    id: 'daily-report-correction',
    pattern: '/daily-reports/:reportId/corrections/:revisionId',
    sectionId: 'daily-reports',
    requiredAny: [permissions.dailyReport.readOwn, permissions.dailyReport.readTeam, permissions.dailyReport.reviewCorrection],
  },
  {
    id: 'daily-reports-my',
    pattern: '/daily-reports/my',
    sectionId: 'daily-reports',
    requiredAny: [permissions.dailyReport.readOwn, permissions.dailyReport.editOwn, permissions.dailyReport.submit],
  },
  {
    id: 'daily-reports-team',
    pattern: '/daily-reports/team',
    sectionId: 'daily-reports',
    requiredAny: [permissions.dailyReport.readTeam, permissions.dailyReport.reviewException, permissions.dailyReport.reviewCorrection],
  },
  {
    id: 'daily-report-detail',
    pattern: '/daily-reports/:reportId',
    sectionId: 'daily-reports',
    requiredAny: [permissions.dailyReport.readOwn, permissions.dailyReport.readTeam],
  },
  {
    id: 'daily-report-template-version',
    pattern: '/daily-report-templates/:templateId/versions/:versionId',
    sectionId: 'daily-report-templates',
    requiredAny: [permissions.dailyReportTemplate.read, permissions.dailyReportTemplate.edit, permissions.dailyReportTemplate.review, permissions.dailyReportTemplate.publish],
  },
  {
    id: 'daily-report-template-detail',
    pattern: '/daily-report-templates/:templateId',
    sectionId: 'daily-report-templates',
    requiredAny: [permissions.dailyReportTemplate.read, permissions.dailyReportTemplate.edit],
  },
  {
    id: 'daily-report-templates',
    pattern: '/daily-report-templates',
    sectionId: 'daily-report-templates',
    requiredAny: [permissions.dailyReportTemplate.read, permissions.dailyReportTemplate.create, permissions.dailyReportTemplate.edit],
  },
  {
    id: 'daily-operation-issue-detail',
    pattern: '/daily-operations/issues/:issueId',
    sectionId: 'daily-operations',
    requiredAny: [permissions.dailyOperations.readHotel, permissions.dailyOperations.readCrossHotel],
  },
  {
    id: 'daily-operation-issues',
    pattern: '/daily-operations/issues',
    sectionId: 'daily-operations',
    requiredAny: [permissions.dailyOperations.readHotel, permissions.dailyOperations.readCrossHotel],
  },
  {
    id: 'daily-operation-action-items',
    pattern: '/daily-operations/action-items',
    sectionId: 'daily-operations',
    requiredAny: [permissions.dailyOperations.readHotel, permissions.dailyOperations.readCrossHotel],
  },
  {
    id: 'daily-operation-snapshot-detail',
    pattern: '/daily-operations/snapshots/:snapshotId',
    sectionId: 'daily-operations',
    requiredAny: [permissions.snapshot.read],
  },
  {
    id: 'daily-operation-snapshots',
    pattern: '/daily-operations/snapshots',
    sectionId: 'daily-operations',
    requiredAny: [permissions.snapshot.read],
  },
  {
    id: 'daily-operation-exports',
    pattern: '/daily-operations/exports',
    sectionId: 'daily-operations',
    requiredAny: [permissions.export.create, permissions.export.download],
    requiredAll: [permissions.dailyOperations.readHotel],
  },
  {
    id: 'daily-operations',
    pattern: '/daily-operations',
    sectionId: 'daily-operations',
    requiredAny: [permissions.dailyOperations.readHotel, permissions.dailyOperations.readCrossHotel],
  },
] as const

const featureById = new Map<DailyFeatureRouteId, RouteDefinition>(dailyFeatureRoutes.map((route) => [route.id, route]))

export const investmentFeatureRoutes: readonly (RouteDefinition & { id: InvestmentFeatureRouteId })[] = [
  {
    id: 'investment-professional',
    pattern: '/investments/professional',
    sectionId: 'investments',
    requiredAny: [permissions.investment.read],
  },
  {
    id: 'investment-project',
    pattern: '/investments/projects/:projectId',
    sectionId: 'investments',
    requiredAny: [permissions.investment.read],
  },
  {
    id: 'investment-parameters',
    pattern: '/investments/cost-parameters',
    sectionId: 'investments',
    requiredAny: [permissions.investment.read],
  },
  {
    id: 'investments',
    pattern: '/investments',
    sectionId: 'investments',
    requiredAny: [permissions.investment.read],
  },
] as const

const investmentById = new Map<InvestmentFeatureRouteId, RouteDefinition>(
  investmentFeatureRoutes.map((route) => [route.id, route]),
)

function safeDecode(value: string): string {
  try {
    return decodeURIComponent(value)
  } catch {
    return value
  }
}

function splitPath(path: string): string[] {
  return path.replace(/^\/+|\/+$/g, '').split('/').filter(Boolean)
}

function matchPattern(pattern: string, path: string): RouteParams | undefined {
  const expected = splitPath(pattern)
  const actual = splitPath(path)
  if (expected.length !== actual.length) return undefined
  const params: RouteParams = {}
  for (let index = 0; index < expected.length; index += 1) {
    const segment = expected[index]
    if (segment.startsWith(':')) params[segment.slice(1)] = safeDecode(actual[index])
    else if (segment !== actual[index]) return undefined
  }
  return params
}

export function parseHashRoute(hash: string): AppRoute {
  const explicit = Boolean(hash && hash !== '#' && hash !== '#/')
  const raw = hash.replace(/^#\/?/, '')
  const queryIndex = raw.indexOf('?')
  const rawPath = queryIndex >= 0 ? raw.slice(0, queryIndex) : raw
  const path = `/${rawPath.replace(/^\/+/, '')}`
  const query = Object.fromEntries(new URLSearchParams(queryIndex >= 0 ? raw.slice(queryIndex + 1) : '').entries()) as RouteParams

  for (const route of dailyFeatureRoutes) {
    const pathParams = matchPattern(route.pattern, path)
    if (pathParams) return { view: route.id, params: { ...query, ...pathParams }, sectionId: route.sectionId, explicit }
  }
  for (const route of investmentFeatureRoutes) {
    const pathParams = matchPattern(route.pattern, path)
    if (pathParams) return { view: route.id, params: { ...query, ...pathParams }, sectionId: route.sectionId, explicit }
  }

  const legacy = splitPath(path)[0]
  const view = legacyViewSet.has(legacy) ? legacy as ViewId : 'workbench'
  return { view, params: query, sectionId: view, explicit }
}

export function buildHashRoute(view: AppRouteId, params: NavigationParams = {}): string {
  const definition = featureById.get(view as DailyFeatureRouteId) ?? investmentById.get(view as InvestmentFeatureRouteId)
  let path = definition?.pattern ?? `/${view}`
  const query = new URLSearchParams()
  const consumed = new Set<string>()

  path = path.replace(/:([A-Za-z0-9_]+)/g, (_, key: string) => {
    const value = params[key]
    consumed.add(key)
    return encodeURIComponent(value ?? '')
  })
  Object.entries(params).forEach(([key, value]) => {
    if (!consumed.has(key) && value !== undefined && value !== '') query.set(key, value)
  })
  return `${path}${query.size ? `?${query.toString()}` : ''}`
}

export function isDailyFeatureRoute(view: AppRouteId): view is DailyFeatureRouteId {
  return featureById.has(view as DailyFeatureRouteId)
}

export function isInvestmentFeatureRoute(view: AppRouteId): view is InvestmentFeatureRouteId {
  return investmentById.has(view as InvestmentFeatureRouteId)
}

export function requiredPermissionsForRoute(view: AppRouteId): readonly string[] {
  return featureById.get(view as DailyFeatureRouteId)?.requiredAny
    ?? investmentById.get(view as InvestmentFeatureRouteId)?.requiredAny
    ?? []
}

export function requiredAllPermissionsForRoute(view: AppRouteId, params: RouteParams = {}): readonly string[] {
  const configured = featureById.get(view as DailyFeatureRouteId)?.requiredAll
    ?? investmentById.get(view as InvestmentFeatureRouteId)?.requiredAll
    ?? []
  if (view === 'daily-operations' && params.mode === 'SNAPSHOT') {
    return [...configured, permissions.snapshot.read]
  }
  return configured
}
