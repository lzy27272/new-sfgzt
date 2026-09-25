import assert from 'node:assert/strict'
import { test } from 'node:test'
import { issueRanges, matchesIssueRange, issueDepartmentLabel } from '../src/features/storeResources/issueSelection.ts'
import { inferUsageScope } from '../src/features/storeResources/usageScope.ts'
import { appendUsageItems, filterUsageItems } from '../src/features/storeResources/itemSelection.ts'

const own = { id:'mine', name:'牙刷', sku:'KF-S001', specification:'30克', issueUnit:'支', rowVersion:0, applicableToAssignment:true, departmentIds:['hk'] }
const cross = { id:'tape', name:'胶带', sku:'WX-S010', specification:'透明', issueUnit:'卷', rowVersion:3, applicableToAssignment:false, departmentIds:['repair'] }
test('cross-department label is exact; all-store is not the shared-only filter', () => {
  assert.equal(issueRanges.DEPARTMENT, '跨部门领用')
  assert.equal(matchesIssueRange(own, 'MINE', ''), true)
  assert.equal(matchesIssueRange(cross, 'MINE', ''), false)
  assert.equal(matchesIssueRange(cross, 'DEPARTMENT', ''), false)
  assert.equal(matchesIssueRange(cross, 'DEPARTMENT', 'repair'), true)
  assert.equal(matchesIssueRange(own, 'DEPARTMENT', 'repair'), false)
  assert.equal(matchesIssueRange(cross, 'HOTEL', ''), true)
  assert.equal(issueDepartmentLabel(cross, [{id:'repair',name:'维修部'}]), '维修部')
})
test('filtering and searching preserve previously entered quantities across ranges', () => {
  const lines = [{ itemId:'mine', quantity:'2', unit:'支', expectedItemVersion:0 }]
  const next = appendUsageItems(lines, [cross])
  next[1].quantity = '1'
  assert.deepEqual(filterUsageItems([own,cross].filter(item => matchesIssueRange(item,'DEPARTMENT','repair')), 'WX-S010', 'ALL'), [cross])
  assert.equal(filterUsageItems([own,cross], '透明 胶带', 'ALL')[0].id, 'tape')
  assert.equal(next[0].quantity, '2'); assert.equal(next[1].quantity, '1')
  assert.deepEqual(lines, [{itemId:'mine',quantity:'2',unit:'支',expectedItemVersion:0}])
})
test('prefix inference uses current store mappings only and leaves unknown codes unconfigured', () => {
  const options = {departments:[{id:'hk',code:'HK',name:'客房部'}],positions:[],prefixConfig:{rowVersion:0,rules:[{prefix:'KF',defaultName:'客房部',departmentId:'hk'},{prefix:'QT',defaultName:'前厅部',departmentId:'foreign'}]}}
  assert.deepEqual(inferUsageScope(' kf-S001 ',options), {mode:'DEPARTMENTS',departmentIds:['hk'],positionIds:[]})
  assert.equal(inferUsageScope('QT-S001',options),undefined)
  assert.equal(inferUsageScope('UNKNOWN',options),undefined)
  assert.equal(inferUsageScope('KFA-S001',options),undefined)
})
