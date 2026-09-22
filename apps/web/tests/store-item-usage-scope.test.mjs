import assert from 'node:assert/strict'
import test from 'node:test'
import { parseItemUsageScope, parseUsageScopeOptions, parseStoreResourceItem, parseStoreResourceImportJob } from '../src/features/storeResources/types.ts'
import { usageScopeSummary, usageScopeValidation } from '../src/features/storeResources/usageScope.ts'

const options = { departments: [{ id: 'd1', code: 'FRONT', name: '前厅部' }, { id: 'd2', code: 'HK', name: '客房部' }], positions: [{ id: 'p1', code: 'HKA', name: '客房服务员' }] }
const specific = { mode: 'DEPARTMENTS', departmentIds: ['d2'], positionIds: [], departments: [options.departments[1]] }

test('usage scope distinguishes old unconfigured items from explicitly all-store items', () => {
  assert.deepEqual(usageScopeSummary(), { departments: '使用范围待配置', positions: '保留原有领用及常用品行为' })
  assert.equal(usageScopeSummary({ mode: 'LEGACY', departmentIds: [], positionIds: [] }).departments, '使用范围待配置')
  assert.equal(usageScopeSummary({ mode: 'ALL_DEPARTMENTS', departmentIds: [], positionIds: [] }).departments, '全店通用')
  assert.equal(usageScopeSummary(specific).departments, '客房部')
  assert.equal(usageScopeSummary(specific).positions, '所选部门内全部岗位')
})

test('validation rejects empty explicit selection or stale cross-store references', () => {
  assert.match(usageScopeValidation({ ...specific, departmentIds: [] }, options), /至少选择/)
  assert.match(usageScopeValidation({ ...specific, departmentIds: ['outside'] }, options), /已不可用/)
  assert.match(usageScopeValidation({ ...specific, positionIds: ['inactive'] }, options), /已不可用/)
  assert.equal(usageScopeValidation(specific, options), undefined)
  assert.equal(usageScopeValidation({ ...specific, mode: 'LEGACY', departmentIds: [] }, options), undefined)
})

test('scope response contract accepts selected IDs and snapshots and rejects malformed data', () => {
  assert.deepEqual(parseUsageScopeOptions(options), options)
  const parsed = parseItemUsageScope({ ...specific, positions: [] })
  assert.equal(usageScopeSummary(parsed).departments, '客房部')
  assert.throws(() => parseItemUsageScope({ ...specific, mode: 'ALL' }), /模式异常/)
  assert.throws(() => parseItemUsageScope({ ...specific, departmentIds: ['d2', 'd2'] }), /标识异常/)
  assert.throws(() => parseUsageScopeOptions({ positions: [], departments: [{}] }), /格式异常/)
  assert.throws(() => parseUsageScopeOptions({ positions: [] }), /格式异常/)
  const item = parseStoreResourceItem({ id: 'i', sku: 'KQS', name: '矿泉水', unit: '瓶', usageScope: specific })
  assert.equal(item.usageScope.departmentIds[0], 'd2')
})

test('current labels take precedence while removed references retain visible saved labels', () => {
  assert.equal(usageScopeSummary(specific, { ...options, departments: [{ ...options.departments[1], name: '客房中心' }] }).departments, '客房中心')
  assert.equal(usageScopeSummary(specific, { departments: [], positions: [] }).departments, '客房部')
  assert.equal(usageScopeSummary({ ...specific, positionIds: ['p1'] }, options).positions, '客房服务员')
})

test('import previews preserve new scopes and explicit legacy-warning counts', () => {
  const job = parseStoreResourceImportJob({ id: 'job', fileName: 'V3.xlsx', status: 'READY', canApply: true,
    summary: { unconfiguredUsageCount: 1 }, preview: { items: [
      { rowNumber: 2, sku: 'W', name: '水', unit: '瓶', usageScope: specific, usageDepartmentNames: '客房部', usagePositionNames: '' },
      { rowNumber: 3, sku: 'OLD', name: '旧物品', unit: '支' },
    ] } })
  assert.equal(job.summary.unconfiguredUsageCount, 1)
  assert.equal(job.preview.items[0].usageScope.mode, 'DEPARTMENTS')
  assert.equal(job.preview.items[0].usageDepartmentNames, '客房部')
  assert.equal(job.preview.items[1].usageScope, undefined)
})

test('invalid preview keeps row errors visible even when other required cells are missing', () => {
  const response = { id: 'job', fileName: 'V3.xlsx', status: 'INVALID', canApply: false, errorCount: 2,
    preview: { items: [{ rowNumber: 2, sku: '', name: '', unit: '', usageDepartmentNames: '不存在部门' }],
      assets: [{ rowNumber: 2, assetCode: '', name: '', category: '', unit: '' }] },
    errors: [{ sheetName: '物品与期初库存', rowNumber: 2, field: '适用部门', message: '未找到有效部门' },
      { sheetName: '物品与期初库存', rowNumber: 2, field: '物品名称', message: '必填内容不能为空' }] }
  const job = parseStoreResourceImportJob(response)
  assert.equal(job.canApply, false)
  assert.equal(job.preview.items[0].name, '未填写')
  assert.equal(job.preview.assets[0].unit, '未填写')
  assert.equal(job.errors.length, 2)
  assert.throws(() => parseStoreResourceImportJob({ ...response, status: 'READY' }), /STORE_RESOURCE_RESPONSE_INVALID/)
})
