import assert from 'node:assert/strict'
import { after, before, test } from 'node:test'
import { fileURLToPath } from 'node:url'
import React from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

let vite, daily, DailyUsageFields, HousekeepingReportForm
before(async () => {
  vite = await createServer({ configFile:'./vite.config.ts', root:fileURLToPath(new URL('..',import.meta.url)), mode:'pilot', logLevel:'silent', server:{middlewareMode:true} })
  daily = await vite.ssrLoadModule('/src/features/storeResources/dailyUsage.ts')
  ;({ DailyUsageFields } = await vite.ssrLoadModule('/src/features/storeResources/DailyUsageFields.tsx'))
  ;({ HousekeepingReportForm } = await vite.ssrLoadModule('/src/features/dailyReports/HousekeepingReportForm.tsx'))
})
after(async () => { await vite?.close() })
const linen = { id:'linen', name:'床单', specification:'1.2米', category:'LINEN', unit:'条', issueUnit:'条', issueFactor:1, rowVersion:2 }
const brush = { ...linen, id:'brush', name:'牙刷', specification:'独立包装', category:'CONSUMABLE', unit:'支', issueUnit:'支' }
const soap = { ...brush, id:'soap', name:'清洁液', category:'CLEANING_SUPPLY', unit:'毫升', issueUnit:'毫升' }
const catalogue = { enabled:true, reviewerAvailable:true, hotelOrgUnitId:'hotel-a', items:[brush,linen,soap], quickList:{ configured:true, rowVersion:0, positionId:'position', itemIds:['linen','brush'] } }

test('common rows appear immediately in configured order and blanks are not zeros', () => {
  const input = daily.dailyUsageValue(undefined,catalogue)
  assert.deepEqual(input.lines.map(line => [line.itemId,line.quantity]),[['linen',''],['brush','']])
  assert.equal(input.remainingUnusedConfirmed,false)
  assert.match(daily.dailyUsageValidation(input,catalogue),/仍有未填/)
  assert.equal(daily.dailyUsageValidation({...input,remainingUnusedConfirmed:true},catalogue),undefined)
  const explicitZeros = {...input, lines:input.lines.map(line => ({...line,quantity:'0'}))}
  assert.equal(daily.dailyUsageValidation(explicitZeros,catalogue),undefined)
  assert.equal(daily.dailyUsagePayload(explicitZeros,catalogue).lines[0].quantity,'0')
})

test('mixed use preserves all raw rows and requires confirmation only for blanks', () => {
  const input = daily.dailyUsageValue(undefined,catalogue)
  input.lines[0].quantity='7'
  assert.match(daily.dailyUsageValidation(input,catalogue),/仍有未填/)
  input.remainingUnusedConfirmed=true
  assert.equal(daily.dailyUsageValidation(input,catalogue),undefined)
  assert.deepEqual(daily.dailyUsagePayload(input,catalogue),input)
  for(const quantity of ['-1','1e99','1.00001','NaN','0.00001']) {
    assert.ok(daily.dailyUsageValidation({...input,lines:[{...input.lines[0],quantity},input.lines[1]]},catalogue))
  }
})

test('legacy values, invalid drafts and existing usage references cannot be silently erased', () => {
  const legacy={schemaVersion:1,noUsage:false,lines:[{itemId:'brush',quantity:'3',unit:'支',expectedItemVersion:1}]}
  const input=daily.dailyUsageValue(legacy,catalogue)
  assert.equal(input.lines[0].quantity,'3'); assert.equal(input.lines[0].expectedItemVersion,1)
  assert.match(daily.dailyUsageValidation(input,catalogue),/配置变化/)
  const unreadable={牙刷:'3'}
  assert.deepEqual(daily.dailyUsagePayload(unreadable,catalogue),unreadable)
  assert.match(daily.dailyUsageValidation(unreadable,catalogue),/旧草稿/)
  const referenced={...catalogue, referenceUsage:{workRecordId:'original'}}
  assert.deepEqual(daily.dailyUsagePayload(input,referenced),{schemaVersion:1,referenceRecordId:'original'})
  assert.equal(daily.dailyUsageValidation(input,referenced),undefined)
})

test('catalogue changes retain quantities, reset blanket confirmation, and keep removed draft rows', () => {
  const input=daily.dailyUsageValue(undefined,catalogue)
  input.lines[0].quantity='7'; input.remainingUnusedConfirmed=true
  const next={...catalogue, items:[{...linen,rowVersion:3},soap], quickList:{...catalogue.quickList,itemIds:['linen','soap']}}
  const restored=daily.dailyUsageValue(input,next)
  assert.equal(restored.lines.find(line=>line.itemId==='linen').quantity,'7')
  assert.equal(restored.lines.find(line=>line.itemId==='linen').expectedItemVersion,2)
  assert.ok(restored.lines.some(line=>line.itemId==='brush'))
  assert.equal(restored.remainingUnusedConfirmed,false)
  assert.match(daily.dailyUsageValidation(restored,next),/配置变化/)
})

test('renders all common rows without picker, labelled numeric inputs and explicit unused confirmation', () => {
  const html=renderToStaticMarkup(React.createElement(DailyUsageFields,{catalogue,value:null,onChange(){},onRefresh(){}}))
  assert.match(html,/床单 1.2米今日用量/); assert.match(html,/牙刷 独立包装今日用量/)
  assert.match(html,/inputMode="decimal"/); assert.match(html,/placeholder="未填"/)
  assert.match(html,/添加其他物品/); assert.match(html,/空白不视为零/)
  assert.doesNotMatch(html,/checked=""|搜索、选择物品/)
})

test('one housekeeping form merges manual text, retains hygiene, history and a single submit action', () => {
  const report={ status:'DRAFT',plannedHygieneSupported:true,allowedActions:['EDIT','SUBMIT'], blockedActionReasons:{},
    roomCounts:{}, currentRevision:{sections:[{id:'s',name:'客房服务员岗位日报 V2',applicable:true,items:[{id:'manual',templateItemId:'template',code:'manualOtherWork',label:'其他工作与手工汇报',dataSource:'EMPLOYEE',required:false,evidence:[]}]}]},revisions:[],aiRecommendations:[] }
  const html=renderToStaticMarkup(React.createElement(HousekeepingReportForm,{identity:{tenantId:'tenant',actorId:'actor'},activities:{schemaVersion:1,serviceCases:[],lostProperties:[],lostPropertyOccurred:null},onActivitiesChange(){},onPhotosPending(){},photosPending:false,report,values:{manual:'交接事项'},editable:true,busy:false,catalogue,inventoryValue:null,missingRequiredCount:0,conflict:false,setValue(){},onRefreshCatalogue(){},onLegacyDraft(){},onSave(){},onSubmit(){}}))
  assert.doesNotMatch(html,/客房服务员岗位日报 V2|补充填报内容/)
  assert.match(html,/其他工作与补充说明/); assert.match(html,/交接事项/); assert.match(html,/计划卫生/)
  assert.equal((html.match(/>提交日报</g)||[]).length,1)
  assert.doesNotMatch(html,/草稿已暂存/)
  assert.match(html,/服务案例/);assert.match(html,/遗留物品/);assert.match(html,/与日报同时提醒、同时填报/)
})
