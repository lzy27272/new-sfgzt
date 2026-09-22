import assert from 'node:assert/strict'
import { test } from 'node:test'
import { mapWorkPackageItem, serializeWorkPackageItems, validateWorkPackageItems } from '../src/features/workPackages/configuration.ts'

test('calendar attribution, relative reminders and protected metadata survive configuration roundtrip', () => {
  const draft=mapWorkPackageItem({ id:'id',item_code:'GM_M_WATER',name:'空气能净水设备检查',form_version_id:'form',period_type:'MONTH',
    execution_policy:{enabled:true,managerCycle:true,cycleSchedule:{dueMonthOffset:0,dueDay:28}},
    reminder_policy:{moments:[{code:'BEFORE_3D',kind:'REMINDER',dueOffsetMinutes:-4320,recipient:'EXECUTORS'}]},
    submission_policy:{maxAttachments:200,maxFileSizeBytes:20971520},report_policy:{cycleReport:true} },0)
  assert.deepEqual(validateWorkPackageItems([draft]),[])
  const row=serializeWorkPackageItems([draft])[0]
  assert.deepEqual(row.executionPolicy.cycleSchedule,{dueMonthOffset:0,dueDay:28})
  assert.equal(row.executionPolicy.managerCycle,true)
  assert.equal(row.reminderPolicy.moments[0].dueOffsetMinutes,-4320)
  assert.equal(row.reminderPolicy.moments[0].localTime,undefined)
  assert.equal(row.reportPolicy.cycleReport,true)
  assert.equal(row.submissionPolicy.completionStatementRequired,false)
  draft.cycleDueMonthOffset='2'
  assert.match(validateWorkPackageItems([draft]).join(),/截止日期无效/)
})
test('legacy weekly entries are not silently converted to new calendars', () => {
  const draft=mapWorkPackageItem({id:'id',item_code:'WEEKLY-OLD',name:'旧周工作',form_version_id:'form',period_type:'WEEK',execution_policy:{enabled:true}},0)
  assert.equal(draft.cycleEnabled,false)
  assert.equal(serializeWorkPackageItems([draft])[0].executionPolicy.cycleSchedule,undefined)
})
