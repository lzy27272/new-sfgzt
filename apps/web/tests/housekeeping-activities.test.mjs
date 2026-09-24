import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { emptyActivities,readActivities,validateActivities } from '../src/features/dailyReports/housekeepingActivities.ts'
import { safeTaskDeepLink } from '../src/features/wecom/entryRoute.ts'

const entry={id:'entry',itemName:'充电器',quantity:2,roomNumber:'1208',foundLocation:'床头柜',foundAt:'2026-09-24T03:00:00Z',recipientAssignmentId:'recipient',recipientLabel:'前台 · 张女士',notes:'黑色',photoIds:['photo']}
const activities={schemaVersion:1,lostPropertyOccurred:true,lostProperties:[entry],serviceCases:[]}
test('new reports explicitly confirm lost-property status and preserve partial drafts',()=>{
  assert.match(validateActivities(emptyActivities()),/是否发现/)
  assert.equal(validateActivities({...emptyActivities(),lostPropertyOccurred:false}),undefined)
  const draft={...activities,lostProperties:[{...entry,itemName:'',quantity:null}]}
  assert.deepEqual(readActivities(JSON.stringify(draft)),draft)
  assert.throws(()=>readActivities('{bad'),SyntaxError)
  assert.throws(()=>readActivities('{"schemaVersion":2}'),/草稿格式异常/)
  assert.throws(()=>readActivities(JSON.stringify({...draft,lostProperties:[null]})),/草稿内容损坏/)
})
test('service cases require room or region, content and uploaded photos',()=>{
  assert.match(validateActivities({...activities,serviceCases:[{id:'a',roomNumber:'前台',serviceContent:'搬运行李',photoIds:[]}]}),/服务案例/)
  assert.equal(validateActivities({...activities,serviceCases:[{id:'a',roomNumber:'前台',serviceContent:'搬运行李',photoIds:['photo']}]}),undefined)
})
test('lost-property quantities and recipient are checked without silently discarding any entries',()=>{
  for(const quantity of [0,-1,1.5,10001,null])assert.match(validateActivities({...activities,lostProperties:[{...entry,quantity}]}),/补全/)
  assert.match(validateActivities({...activities,lostProperties:[{...entry,recipientAssignmentId:''}]}),/接收人/)
  assert.match(validateActivities({...activities,lostPropertyOccurred:false}),/选择“有”/)
  assert.match(validateActivities({...activities,lostProperties:[]}),/至少填写/)
  assert.equal(validateActivities(activities),undefined)
  assert.equal(readActivities(JSON.stringify(activities)).lostProperties[0].recipientLabel,entry.recipientLabel)
})
test('review links allow exactly one case id, never external destinations or added queries',()=>{
  globalThis.window={location:{origin:'https://www.sfgzt.cn'}}
  const id='123e4567-e89b-42d3-a456-426614174000',path='#/lost-properties?id='+id
  assert.equal(safeTaskDeepLink(path),path)
  for(const unsafe of [path+'&id='+id,path+'&redirect=https://evil.test','#/lost-properties?id=bad','https://evil.test/'+path,'#/lost-properties'])assert.throws(()=>safeTaskDeepLink(unsafe))
})
test('album uploads retain a durable receipt before the parent form references a photo',async()=>{
  const source=await readFile(new URL('../src/features/dailyReports/ActivityPhotos.tsx',import.meta.url),'utf8')
  assert.doesNotMatch(source,/\bcapture=/)
  assert.match(source,/type="file" accept="image\/jpeg,image\/png" multiple/)
  const receipt=source.indexOf('await writeResumableDraft(key, { pending: queue.current.slice(1), completed: completed.current })')
  assert.ok(receipt>=0&&receipt<source.indexOf('latest.current.onAdd?.(next.id)'))
  assert.match(source,/inFlight.current\?\.abort\(\)/)
  assert.match(source,/identity.tenantId, identity.actorId, identity.businessActorAssignmentId, base, entryId/)
  assert.match(source,/已暂停新增和提交，避免覆盖原草稿/)
  assert.match(source,/重新恢复照片草稿/)
})

test('mobile review puts the selected case first and isolates editor drafts by active identity',async()=>{
  const [source,css]=await Promise.all([
    readFile(new URL('../src/features/lostProperties/LostPropertyRoutes.tsx',import.meta.url),'utf8'),
    readFile(new URL('../src/features/dailyReports/activities.module.css',import.meta.url),'utf8'),
  ])
  assert.match(css,/@media\(max-width:760px\).*\.caseDetail\{order:-1\}/s)
  assert.match(source,/key=\{identity.key \+ ':' \+ detail.data.id \+ ':' \+ detail.data.rowVersion\}/)
})

test('acknowledged draft save feedback survives editor reload and stays scoped to the report',async()=>{
  const source=await readFile(new URL('../src/features/dailyReports/DailyReportRoutes.tsx',import.meta.url),'utf8')
  const page=source.slice(source.indexOf('function ReportDetailPage('),source.indexOf('type DailyReportLocalDraft'))
  assert.match(page,/const noticeScope = `\$\{identity.key\}:\$\{reportId\}`/)
  assert.match(page,/savedNotice\?\.scope === noticeScope/)
  assert.match(page,/role="status">\{savedNotice.text\}/)
  assert.ok(page.indexOf('setSavedNotice(notice ?')<page.indexOf('await resource.reload()'))
  assert.match(source,/await onChanged\(action === 'save' \? '草稿已保存到服务器' : undefined\)/)
  assert.doesNotMatch(source,/setDraftNotice\('草稿已保存到服务器'\)/)
})
