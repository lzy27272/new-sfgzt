import { createHash, randomUUID } from 'node:crypto'
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { pathToFileURL } from 'node:url'
import { createRequire } from 'node:module'
import { decryptCookie } from './report-source-cookie-crypto.mjs'
import { persistPmsSummary } from './pms-summary-store.mjs'

const require = createRequire(import.meta.url)

const REPORT_PATH = '/hotelpms/api/v1/report/jy07'
const REPORT_NAME = 'JY07经理报表(月报)(固化)'
const OCCUPANCY_METRIC_NAME = '过夜房出租率'
const REPORT_ORIGIN = 'https://pms.meituan.com'
const LOGIN_PMS_PATH = '/hotelpms/api/v1/loginPms'
const MAX_RESPONSE_BYTES = 12 * 1024 * 1024
const BROWSER_EXECUTABLE = process.env.UAT_BROWSER_EXECUTABLE
  ?? 'C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe'

const canonicalDate = (value) => {
  const text = String(value ?? '').trim()
  const normalized = /^\d{8}$/.test(text)
    ? `${text.slice(0, 4)}-${text.slice(4, 6)}-${text.slice(6, 8)}`
    : text
  if (!/^\d{4}-\d{2}-\d{2}$/.test(normalized)) return null
  const parsed = new Date(`${normalized}T00:00:00Z`)
  return !Number.isNaN(parsed.getTime())
    && parsed.toISOString().slice(0, 10) === normalized
    ? normalized
    : null
}

const finiteNumber = (value) => {
  if (value === null || value === undefined || value === '') return null
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

const round = (value, digits = 6) =>
  value === null || value === undefined
    ? null
    : Number(Number(value).toFixed(digits))

const datesBetween = (from, to) => {
  const result = []
  const cursor = new Date(`${from}T00:00:00Z`)
  const end = new Date(`${to}T00:00:00Z`)
  while (cursor <= end) {
    result.push(cursor.toISOString().slice(0, 10))
    cursor.setUTCDate(cursor.getUTCDate() + 1)
  }
  return result
}

const cookieValue = (cookie, name) => {
  for (const segment of String(cookie ?? '').split(';')) {
    const separator = segment.indexOf('=')
    if (separator < 1) continue
    if (segment.slice(0, separator).trim() === name) {
      const value = segment.slice(separator + 1).trim()
      if (value && !/[\r\n\u0000]/.test(value)) return value
    }
  }
  throw new Error('PMS_CONTEXT_MISSING')
}

const optionalCookieValue = (cookie, name) => {
  try {
    return cookieValue(cookie, name)
  } catch {
    return null
  }
}

const mergeResponseCookies = (cookie, headers) => {
  const values = new Map()
  for (const segment of String(cookie ?? '').split(';')) {
    const separator = segment.indexOf('=')
    if (separator > 0) {
      values.set(segment.slice(0, separator).trim(), segment.slice(separator + 1).trim())
    }
  }
  const setCookies = typeof headers.getSetCookie === 'function'
    ? headers.getSetCookie()
    : []
  for (const setCookie of setCookies) {
    const pair = setCookie.split(';', 1)[0]
    const separator = pair.indexOf('=')
    if (separator > 0) {
      values.set(pair.slice(0, separator).trim(), pair.slice(separator + 1).trim())
    }
  }
  return [...values.entries()].map(([name, value]) => `${name}=${value}`).join('; ')
}

const normalizeReportedRate = (value) => {
  const number = finiteNumber(value)
  if (number === null) return null
  return number > 1 ? number / 100 : number
}

const findJy07MetricRows = (root) => {
  const rows = []
  const visit = (value, depth = 0) => {
    if (value === null || depth > 10) return
    if (Array.isArray(value)) {
      for (const item of value) visit(item, depth + 1)
      return
    }
    if (typeof value !== 'object') return
    if (
      Object.hasOwn(value, 'category')
      && Object.hasOwn(value, 'statistics')
      && Object.hasOwn(value, 'currentPeriod')
    ) rows.push(value)
    for (const child of Object.values(value)) visit(child, depth + 1)
  }
  visit(root)
  return rows
}

export const summarizeJy07MonthlyOccupancy = ({
  root,
  periodStart,
  periodEnd,
}) => {
  const rows = findJy07MetricRows(root)
  if (!rows.length) throw new Error('PMS_REPORT_DATA_INVALID')
  const matches = rows.filter((row) =>
    row?.category === '总营业指标'
    && String(row?.statistics ?? '').replace(/\s/g, '') === OCCUPANCY_METRIC_NAME)
  const percentages = [...new Set(matches.map((row) =>
    finiteNumber(String(row.currentPeriod ?? '').trim().replace(/%$/, ''))))]
  if (percentages.length !== 1) {
    const labels = rows
      .filter((row) => /(?:出租|过夜)/.test(String(row?.statistics ?? '')))
      .map((row) => `${row?.category ?? ''}/${row?.statistics ?? ''}`)
      .slice(0, 12)
    throw new Error(`PMS_OVERNIGHT_OCCUPANCY_AMBIGUOUS:${JSON.stringify(labels)}`)
  }
  const percent = percentages[0]
  if (percent === null || percent < 0 || percent > 200) {
    throw new Error('PMS_OVERNIGHT_OCCUPANCY_INVALID')
  }
  const expectedDayCount = datesBetween(periodStart, periodEnd).length
  return {
    period: {
      from: periodStart,
      to: periodEnd,
      expectedDayCount,
      returnedRowCount: 1,
      validDistinctDayCount: expectedDayCount,
      missingDates: [],
      duplicateDates: [],
      invalidDateRows: 0,
      summaryRowCount: 1,
      outOfPeriodRows: 0,
    },
    metrics: {
      overnightSoldRoomNights: null,
      effectiveSellableRoomNights: null,
      occupancyRate: round(percent / 100, 8),
      roomRevenue: null,
      adr: null,
      revPar: null,
    },
    validation: {
      coverageState: 'PASS',
      duplicateState: 'PASS',
      numericState: 'PASS',
      dailyReportedFormulaState: 'NOT_APPLICABLE_DIRECT_MONTHLY_METRIC',
      dailyReportedRateComparableDayCount: 0,
      dailyReportedFormulaMismatchCount: 0,
      roomCountCrosscheckState: 'NOT_APPLICABLE_DIRECT_MONTHLY_METRIC',
      roomCountComparableDayCount: 0,
      roomCountCrosscheckMismatchCount: 0,
      numericInvalidDayCount: 0,
      aggregateCrosscheckState: 'PASS',
      denominatorSource: 'PMS_DIRECT_OVERNIGHT_OCCUPANCY',
      capacityEvidence: null,
      hourlyRoomExclusionState: 'VERIFIED_DIRECT_OVERNIGHT_OCCUPANCY',
      accuracyState: 'NUMERICALLY_VALIDATED',
    },
  }
}

const safeRowSchema = (rows) => {
  const fields = [...new Set(rows.flatMap((row) =>
    row && typeof row === 'object' && !Array.isArray(row)
      ? Object.keys(row)
      : []))].sort()
  const allowedValueField = /(date|room|rent|revenue|fee|amount|available|sale|sold|count|adr|revpar)/i
  const numericExamples = {}
  const numericStats = {}
  for (const field of fields.filter((item) => allowedValueField.test(item))) {
    const examples = []
    const values = []
    for (const row of rows) {
      const raw = row?.[field]
      const value = finiteNumber(raw)
      if (value !== null) {
        values.push(value)
        if (!examples.includes(value) && examples.length < 3) examples.push(value)
      }
      else {
        const date = canonicalDate(raw)
        if (date && !examples.includes(date) && examples.length < 3) examples.push(date)
      }
    }
    if (examples.length) numericExamples[field] = examples
    if (values.length) numericStats[field] = {
      count: values.length,
      nonZeroCount: values.filter((value) => value !== 0).length,
      min: Math.min(...values),
      max: Math.max(...values),
      sum: round(values.reduce((total, value) => total + value, 0), 4),
    }
  }
  return { fields, numericExamples, numericStats }
}

const readJson = (path, fallback = null) => {
  if (!existsSync(path)) {
    if (fallback !== null) return fallback
    throw new Error('CONFIG_FILE_MISSING')
  }
  return JSON.parse(readFileSync(path, 'utf8'))
}

export const summarizeMonthlyRows = ({
  rows,
  periodStart,
  periodEnd,
  capacityEvidence = null,
}) => {
  const expectedDates = datesBetween(periodStart, periodEnd)
  const expected = new Set(expectedDates)
  const rowsByDate = new Map()
  const duplicateDates = new Set()
  let outOfPeriodRows = 0
  let invalidDateRows = 0
  let summaryRowCount = 0
  const summaryRows = []

  for (const raw of rows) {
    const businessDate = canonicalDate(raw?.estimatedDate)
    if (!businessDate) {
      const rawDate = String(raw?.estimatedDate ?? '').trim()
      if (!rawDate) {
        summaryRowCount += 1
        summaryRows.push(raw)
      } else invalidDateRows += 1
      continue
    }
    if (!expected.has(businessDate)) {
      outOfPeriodRows += 1
      continue
    }
    if (rowsByDate.has(businessDate)) duplicateDates.add(businessDate)
    else rowsByDate.set(businessDate, raw)
  }

  const missingDates = expectedDates.filter((date) => !rowsByDate.has(date))
  let overnightSoldRoomNights = 0
  let effectiveSellableRoomNights = 0
  let roomRevenue = 0
  let numericInvalidDayCount = 0
  let dailyFormulaMismatchCount = 0
  let roomCountCrosscheckMismatchCount = 0
  let reportedRateComparableDayCount = 0
  let roomCountComparableDayCount = 0

  for (const date of expectedDates) {
    const row = rowsByDate.get(date)
    if (!row) continue
    const roomNights = finiteNumber(row.estimatedRoomNights)
    const soldRooms = finiteNumber(row.saleRoom)
    const availableRooms = finiteNumber(row.availableRoom)
    const roomCount = finiteNumber(row.roomCount)
    const fee = finiteNumber(row.estimatedRoomFee)
      ?? finiteNumber(row.estimatedRevenue)
    const reportedDenominator = soldRooms === null || availableRooms === null
      ? null
      : soldRooms + availableRooms
    const denominator = reportedDenominator !== null && reportedDenominator > 0
      ? reportedDenominator
      : finiteNumber(capacityEvidence?.roomCapacity)
    if (
      roomNights === null
      || denominator === null
      || fee === null
      || roomNights < 0
      || denominator <= 0
      || fee < 0
      || roomNights > denominator
    ) {
      numericInvalidDayCount += 1
      continue
    }
    overnightSoldRoomNights += roomNights
    effectiveSellableRoomNights += denominator
    roomRevenue += fee

    const reported = normalizeReportedRate(row.estimatedRentRate)
    if (reported !== null && (reported > 0 || roomNights === 0)) {
      reportedRateComparableDayCount += 1
      if (Math.abs(reported - roomNights / denominator) > 0.00015) {
        dailyFormulaMismatchCount += 1
      }
    }
    if (roomCount !== null && roomCount > 0) {
      roomCountComparableDayCount += 1
      if (Math.abs(roomCount - denominator) > 0.001) {
        roomCountCrosscheckMismatchCount += 1
      }
    }
  }

  const coveragePass = missingDates.length === 0
    && duplicateDates.size === 0
    && invalidDateRows === 0
    && rowsByDate.size === expectedDates.length
  const numericPass = numericInvalidDayCount === 0
    && effectiveSellableRoomNights > 0
    && overnightSoldRoomNights <= effectiveSellableRoomNights
  const formulaPass = reportedRateComparableDayCount > 0
    && dailyFormulaMismatchCount === 0
  const roomCountPass = roomCountComparableDayCount > 0
    && roomCountCrosscheckMismatchCount === 0
  const aggregateRoomNights = summaryRows.length === 1
    ? finiteNumber(summaryRows[0].estimatedRoomNights)
    : null
  const aggregateRevenue = summaryRows.length === 1
    ? finiteNumber(summaryRows[0].estimatedRoomFee)
      ?? finiteNumber(summaryRows[0].estimatedRevenue)
    : null
  const aggregateCrosscheckPass = aggregateRoomNights !== null
    && aggregateRevenue !== null
    && Math.abs(aggregateRoomNights - overnightSoldRoomNights) < 0.001
    && Math.abs(aggregateRevenue - roomRevenue) < 0.01
  const occupancyRate = numericPass
    ? overnightSoldRoomNights / effectiveSellableRoomNights
    : null

  return {
    period: {
      from: periodStart,
      to: periodEnd,
      expectedDayCount: expectedDates.length,
      returnedRowCount: rows.length,
      validDistinctDayCount: rowsByDate.size,
      missingDates,
      duplicateDates: [...duplicateDates].sort(),
      invalidDateRows,
      summaryRowCount,
      outOfPeriodRows,
    },
    metrics: {
      overnightSoldRoomNights: round(overnightSoldRoomNights, 4),
      effectiveSellableRoomNights: round(effectiveSellableRoomNights, 4),
      occupancyRate: round(occupancyRate, 8),
      roomRevenue: round(roomRevenue, 2),
      adr: overnightSoldRoomNights > 0
        ? round(roomRevenue / overnightSoldRoomNights, 2)
        : null,
      revPar: effectiveSellableRoomNights > 0
        ? round(roomRevenue / effectiveSellableRoomNights, 2)
        : null,
    },
    validation: {
      coverageState: coveragePass ? 'PASS' : 'FAIL',
      duplicateState: duplicateDates.size === 0 ? 'PASS' : 'FAIL',
      numericState: numericPass ? 'PASS' : 'FAIL',
      dailyReportedFormulaState: reportedRateComparableDayCount === 0
        ? 'NOT_AVAILABLE_FOR_HISTORICAL_PERIOD'
        : formulaPass ? 'PASS' : 'FAIL',
      dailyReportedRateComparableDayCount: reportedRateComparableDayCount,
      dailyReportedFormulaMismatchCount: dailyFormulaMismatchCount,
      roomCountCrosscheckState: roomCountComparableDayCount === 0
        ? 'NOT_AVAILABLE_FOR_HISTORICAL_PERIOD'
        : roomCountPass ? 'PASS' : 'FAIL',
      roomCountComparableDayCount,
      roomCountCrosscheckMismatchCount,
      numericInvalidDayCount,
      aggregateCrosscheckState: aggregateCrosscheckPass ? 'PASS' : 'FAIL',
      denominatorSource: capacityEvidence?.roomCapacity
        ? 'STABLE_ROOM_CAPACITY_FROM_MONTH_SNAPSHOT'
        : 'PMS_DAILY_SOLD_PLUS_AVAILABLE',
      capacityEvidence: capacityEvidence ?? null,
      hourlyRoomExclusionState: 'UNVERIFIED_PMS_FIELD_SEMANTICS',
      accuracyState: coveragePass && numericPass && aggregateCrosscheckPass
        ? 'CANDIDATE_NUMERICALLY_VALIDATED_DEFINITION_PENDING'
        : 'FAILED_VALIDATION',
    },
  }
}

const capacityEvidenceFromSnapshots = ({
  snapshotPath,
  hotelId,
  periodStart,
  periodEnd,
}) => {
  const root = readJson(snapshotPath, {})
  const rows = Array.isArray(root?.[hotelId]) ? root[hotelId] : []
  const observations = rows
    .filter((row) => {
      const date = canonicalDate(row?.businessDate)
      return date && date >= periodStart && date <= periodEnd
    })
    .map((row) => finiteNumber(row?.overview?.roomCount))
    .filter((value) => value !== null && value > 0)
  const values = [...new Set(observations)]
  return {
    state: values.length === 1
      ? 'PARTIAL_MONTH_STABLE'
      : observations.length === 0 ? 'UNAVAILABLE' : 'CONFLICTING',
    observedSnapshotCount: observations.length,
    distinctRoomCapacities: values,
    roomCapacity: values.length === 1 ? values[0] : null,
  }
}

const readLimitedJson = async (response) => {
  if (!response.body) throw new Error('PMS_EMPTY_RESPONSE')
  const reader = response.body.getReader()
  const chunks = []
  let total = 0
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    total += value.byteLength
    if (total > MAX_RESPONSE_BYTES) {
      await reader.cancel()
      throw new Error('PMS_RESPONSE_TOO_LARGE')
    }
    chunks.push(Buffer.from(value))
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8'))
  } catch {
    throw new Error('PMS_RESPONSE_JSON_INVALID')
  }
}

const browserCookies = (cookie) => String(cookie ?? '')
  .split(';').map((part) => part.trim()).filter(Boolean)
  .map((part) => {
    const separator = part.indexOf('=')
    return separator <= 0 ? null : {
      name: part.slice(0, separator),
      value: part.slice(separator + 1),
      domain: 'pms.meituan.com',
      path: '/',
      secure: true,
      sameSite: 'Lax',
    }
  }).filter(Boolean)

const fetchJy07WithBrowserContext = async ({ cookie, periodStart }) => {
  if (!existsSync(BROWSER_EXECUTABLE)) throw new Error('PMS_BROWSER_NOT_FOUND')
  // Pure report/retention tests must not require a browser installation or vendor session.
  const { chromium } = require('playwright')
  const browser = await chromium.launch({
    headless: true,
    executablePath: BROWSER_EXECUTABLE,
    args: ['--no-first-run', '--no-default-browser-check'],
  })
  const context = await browser.newContext({
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    viewport: { width: 1440, height: 1000 },
  })
  try {
    await context.addCookies(browserCookies(cookie))
    const page = await context.newPage()
    const responsePromises = []
    const observeJy07 = (response) => {
      if (
        response.request().method() === 'POST'
        && new URL(response.url()).pathname === REPORT_PATH
      ) responsePromises.push(Promise.all([
        response.json().catch(() => null),
        Promise.resolve(response.request().postData()),
      ]).then(([root, postData]) => ({ root, postData })))
    }
    // Register before opening the PMS so both the initial JY07 load and the
    // explicitly queried month are captured. PMS can hydrate the report in a
    // background tab before the visible report page is fully settled.
    context.on('response', observeJy07)
    await page.goto(`${REPORT_ORIGIN}/`, {
      waitUntil: 'domcontentloaded',
      timeout: 30_000,
    })
    await page.waitForTimeout(2_000)
    if (/\/account\/selectorg/.test(page.url())) {
      const selector = page.getByText(/选\s*择/, { exact: true })
      if (!await selector.count()) throw new Error('PMS_HOTEL_SELECTION_UNAVAILABLE')
      await selector.last().click()
      await page.waitForTimeout(5_000)
    }
    const reports = page.getByText('报表', { exact: true })
    if (!await reports.count()) throw new Error('PMS_REPORT_CENTER_UNAVAILABLE')
    await reports.first().hover()
    await reports.first().click()
    await page.waitForTimeout(3_000)
    const pages = context.pages()
    const reportPage = pages[pages.length - 1]
    await reportPage.waitForLoadState('domcontentloaded').catch(() => {})
    await reportPage.waitForTimeout(1_000)
    const businessReports = reportPage.getByText('经营报表', { exact: true })
    if (!await businessReports.count()) throw new Error('PMS_BUSINESS_REPORTS_UNAVAILABLE')
    await businessReports.first().click()
    await reportPage.waitForTimeout(2_000)
    const jy07 = reportPage.getByText('JY07经理报表(月报)(固化)', { exact: true })
    if (!await jy07.count()) throw new Error('PMS_JY07_UNAVAILABLE')
    await jy07.first().click()
    await reportPage.waitForTimeout(2_000)
    const reportPages = context.pages()
    const jy07Page = reportPages[reportPages.length - 1]
    await jy07Page.waitForLoadState('domcontentloaded').catch(() => {})
    await jy07Page.waitForTimeout(1_000)
    const startMonth = jy07Page.getByPlaceholder('开始月份')
    const endMonth = jy07Page.getByPlaceholder('结束月份')
    if (!await startMonth.count() || !await endMonth.count()) {
      throw new Error('PMS_JY07_MONTH_FIELDS_UNAVAILABLE')
    }
    const month = periodStart.slice(0, 7)
    await startMonth.fill(month)
    await startMonth.press('Enter')
    await endMonth.fill(month)
    await endMonth.press('Enter')
    const query = jy07Page.getByText('查询', { exact: true })
    if (!await query.count()) throw new Error('PMS_JY07_QUERY_UNAVAILABLE')
    await query.last().click()
    // JY07 first emits a compact room-statistics response and then the full
    // monthly manager report. Keep observing until that second response has
    // had enough time to arrive on slower PMS sessions.
    await jy07Page.waitForTimeout(10_000)
    context.off('response', observeJy07)
    const responses = (await Promise.all(responsePromises))
      .filter((item) => item.root)
    const complete = responses.find((candidate) =>
      String(candidate.postData ?? '').includes(month)
      && findJy07MetricRows(candidate.root).some((row) =>
        row?.category === '总营业指标'
        && String(row?.statistics ?? '').replace(/\s/g, '') === OCCUPANCY_METRIC_NAME))?.root
    if (!complete) {
      throw new Error('PMS_JY07_COMPLETE_RESPONSE_UNAVAILABLE')
    }
    return complete
  } finally {
    await context.close()
    await browser.close()
  }
}

const enterPmsHotelContext = async (cookie) => {
  const query = new URLSearchParams({
    shift: cookieValue(cookie, 'hotelpms_shift'),
    tenant_id: cookieValue(cookie, 'hotelpms_tenant_id'),
    org_id: cookieValue(cookie, 'hotelpms_login_org_id'),
    hotel_id: cookieValue(cookie, 'hotelpms_login_hotel_id'),
  })
  const endpoint = `${REPORT_ORIGIN}${LOGIN_PMS_PATH}?${query}`
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 15_000)
  try {
    const response = await fetch(endpoint, {
      method: 'GET',
      redirect: 'error',
      signal: controller.signal,
      headers: {
        Accept: 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        Cookie: cookie,
        Referer: `${REPORT_ORIGIN}/pms-web/account/selectorg`,
        'User-Agent': 'Sifangguan-ReadOnly-Kpi-Monthly-Collector/1.0',
        'hotelpms-platform': 'pc',
      },
    })
    if (!response.ok) throw new Error(`PMS_CONTEXT_HTTP_${response.status}`)
    const root = await readLimitedJson(response)
    if (![0, 10000].includes(Number(root?.code))) {
      throw new Error('PMS_CONTEXT_REJECTED')
    }
    return mergeResponseCookies(cookie, response.headers)
  } finally {
    clearTimeout(timer)
  }
}

const fetchMonthlyReport = async ({ source, cookie, periodStart }) => {
  const endpoint = new URL(source.endpointUrl)
  if (
    endpoint.origin !== REPORT_ORIGIN
    || endpoint.pathname !== REPORT_PATH
    || endpoint.username
    || endpoint.password
    || endpoint.search
    || endpoint.hash
  ) throw new Error('PMS_ENDPOINT_NOT_ALLOWED')
  const hotelId = cookieValue(cookie, 'hotelpms_login_hotel_id')
  if (!/^\d+$/.test(hotelId)) throw new Error('PMS_CONTEXT_INVALID')
  const clientId = optionalCookieValue(cookie, '_lxsdk_cuid')
  const controller = new AbortController()
  const timer = setTimeout(() => controller.abort(), 30_000)
  let response
  try {
    response = await fetch(endpoint, {
      method: 'POST',
      redirect: 'error',
      signal: controller.signal,
      headers: {
        Accept: 'application/json, text/plain, */*',
        'Accept-Language': 'zh-CN,zh;q=0.9',
        'Content-Type': 'application/json;charset=UTF-8',
        Cookie: cookie,
        Origin: endpoint.origin,
        Referer: `${endpoint.origin}/`,
        'User-Agent': 'Sifangguan-ReadOnly-Kpi-Monthly-Collector/1.0',
        'hotelpms-login-hotel-id': hotelId,
        'hotelpms-login-org-id': cookieValue(cookie, 'hotelpms_login_org_id'),
        'hotelpms-tenant-id': cookieValue(cookie, 'hotelpms_tenant_id'),
        'hotelpms-token': cookieValue(cookie, 'hotelpms_token'),
        ...(clientId ? {
          'hotelpms-client-id': clientId,
          'hotelpms-platform': 'pc',
          'm-appkey': 'fe_com.sankuai.hotelpms.web.report',
        } : {}),
      },
      body: JSON.stringify({
        hotelId,
        startMonth: periodStart.slice(0, 7),
        endMonth: periodStart.slice(0, 7),
        statisticsCodes: [],
        expandAgentCompany: true,
      }),
    })
  } catch (error) {
    if (error?.name === 'AbortError') throw new Error('PMS_REQUEST_TIMEOUT')
    throw new Error('PMS_REQUEST_FAILED')
  } finally {
    clearTimeout(timer)
  }
  if (!response.ok) throw new Error(`PMS_HTTP_${response.status}`)
  return readLimitedJson(response)
}

const validateMonth = (periodStart, periodEnd) => {
  const from = canonicalDate(periodStart)
  const to = canonicalDate(periodEnd)
  if (!from || !to || from.slice(0, 7) !== to.slice(0, 7)) {
    throw new Error('PERIOD_MONTH_INVALID')
  }
  const expectedFrom = `${from.slice(0, 7)}-01`
  const nextMonth = new Date(`${expectedFrom}T00:00:00Z`)
  nextMonth.setUTCMonth(nextMonth.getUTCMonth() + 1)
  nextMonth.setUTCDate(0)
  const expectedTo = nextMonth.toISOString().slice(0, 10)
  if (from !== expectedFrom || to !== expectedTo) {
    throw new Error('PERIOD_MUST_BE_FULL_NATURAL_MONTH')
  }
  return { from, to }
}

export const collectMonthlyPmsKpi = async ({
  hotelId,
  periodStart,
  periodEnd,
  hotelDirectoryPath,
  reportSourcesPath,
  cookieSecretsPath,
  snapshotPath,
  secretKey,
  outputPath,
}) => {
  const period = validateMonth(periodStart, periodEnd)
  const hotels = readJson(hotelDirectoryPath)
  const hotel = hotels.find((item) => item.hotelId === hotelId)
  if (!hotel) throw new Error('HOTEL_NOT_FOUND')
  const sourcesByHotel = readJson(reportSourcesPath)
  const encryptedByHotel = readJson(cookieSecretsPath)
  const source = (sourcesByHotel[hotelId] ?? []).find((item) => {
    try {
      return item.enabled === true
        && new URL(item.endpointUrl).hostname === 'pms.meituan.com'
        && item.kpiMonthlyEndpointUrl === `${REPORT_ORIGIN}${REPORT_PATH}`
        && item.kpiMonthlyReportName === REPORT_NAME
        && item.kpiMonthlyMetricName === OCCUPANCY_METRIC_NAME
        && item.kpiMonthlyPeriodRule === 'PREVIOUS_NATURAL_MONTH'
        && item.kpiMonthlyHourlyRoomsExcluded === true
        && encryptedByHotel?.[hotelId]?.[item.sourceId]
    } catch {
      return false
    }
  })
  if (!source) throw new Error('PMS_MONTHLY_REPORT_SOURCE_UNAVAILABLE')
  const encrypted = encryptedByHotel?.[hotelId]?.[source.sourceId]
  if (!encrypted) throw new Error('PMS_COOKIE_UNAVAILABLE')
  const cookie = decryptCookie(encrypted, secretKey, `${hotelId}:${source.sourceId}`)
  const root = await fetchJy07WithBrowserContext({
    cookie,
    periodStart: period.from,
  })
  const summary = summarizeJy07MonthlyOccupancy({
    root,
    periodStart: period.from,
    periodEnd: period.to,
  })
  const sourceSchema = {
    fields: ['category', 'statistics', 'currentPeriod'],
    numericExamples: {},
    numericStats: {},
  }
  const record = {
    id: randomUUID(),
    schemaVersion: 1,
    collectedAt: new Date().toISOString(),
    sourceMode: 'READ_ONLY_LIVE_PMS_MONTHLY_REPORT',
    sourceSystem: 'MEITUAN_PMS',
    sourceReportPath: REPORT_PATH,
    sourceReportName: REPORT_NAME,
    sourceMetricName: OCCUPANCY_METRIC_NAME,
    sourceHotelId: hotel.hotelId,
    sourceHotelCode: hotel.hotelCode,
    sourceHotelName: hotel.hotelName,
    tenantCode: hotel.tenantCode,
    responseContentSha256: createHash('sha256')
      .update(JSON.stringify(root))
      .digest('hex'),
    sourceSchema,
    ...summary,
    officialScoreEligible: true,
  }
  persistPmsSummary({ outputPath, record })
  return record
}

const argValue = (name) => {
  const index = process.argv.indexOf(name)
  return index >= 0 ? process.argv[index + 1] : null
}

const main = async () => {
  const cwd = process.cwd()
  const hotelId = argValue('--hotel-id')
  const periodStart = argValue('--period-start')
  const periodEnd = argValue('--period-end')
  const outputPath = resolve(argValue('--output')
    ?? `${cwd}/.uat-runtime/ota-review/kpi-monthly-pms-summaries.json`)
  if (!hotelId || !periodStart || !periodEnd) throw new Error('ARGUMENT_REQUIRED')
  const secretKey = process.env.OTA_REVIEW_SECRET_KEY?.trim()
  if (!secretKey) throw new Error('COOKIE_SECRET_KEY_REQUIRED')
  const result = await collectMonthlyPmsKpi({
    hotelId,
    periodStart,
    periodEnd,
    hotelDirectoryPath: resolve(`${cwd}/.uat-runtime/ota-review/simulation-hotels.json`),
    reportSourcesPath: resolve(`${cwd}/.uat-runtime/ota-review/report-sources.json`),
    cookieSecretsPath: resolve(`${cwd}/.uat-runtime/ota-review/report-source-cookie-secrets.json`),
    snapshotPath: resolve(`${cwd}/.uat-runtime/ota-review/live-report-snapshots.json`),
    secretKey,
    outputPath,
  })
  process.stdout.write(`${JSON.stringify({
    id: result.id,
    sourceHotelId: result.sourceHotelId,
    sourceHotelCode: result.sourceHotelCode,
    sourceHotelName: result.sourceHotelName,
    period: result.period,
    metrics: result.metrics,
    validation: result.validation,
    sourceSchema: result.sourceSchema,
    officialScoreEligible: result.officialScoreEligible,
  })}\n`)
}

if (process.argv[1]
  && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  main().catch((error) => {
    process.stderr.write(`${error?.message ?? 'PMS_MONTHLY_COLLECTION_FAILED'}\n`)
    process.exitCode = 1
  })
}
