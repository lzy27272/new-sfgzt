// Local component acceptance fixture, never imported by the production entry point.
import React, { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { WorkRecordDialog } from '../src/App'
import { TaskCreateDialog } from '../src/Pilot6Pages'
import '../src/styles.css'

const identity = { key: 'qa', tenantId: 'test-tenant', actorId: 'test-account',
  roleCode: 'HOUSEKEEPING_ATTENDANT', orgScopes: ['test-hotel'], businessActorAssignmentId: 'test-assignment',
  employeeId: 'test-employee', assignmentOrgUnitId: 'test-hotel', label: '客房服务员', userName: '验收员工', orgName: '验证门店' }
const titles = { HKA_COMPLAINT_EVENT: '客诉报备', HKA_GUEST_SERVICE_EVENT: '对客服务记录', HKA_CART_PREPARATION: '布草车与工作车整理', HKA_LOST_PROPERTY: '遗留物品移交' }
const common = { issueFound: { type: 'boolean', title: '是否有异常' }, workSummary: { type: 'string', title: '工作结果' },
  checkoutRooms: { type: 'integer', title: '退房打扫数' }, stayoverRooms: { type: 'integer', title: '续住房打扫数' } }
function Fixture() {
  const [kind, setKind] = useState('')
  const [key, setKey] = useState(0)
  const [saved, setSaved] = useState(0)
  const occurrence = kind !== 'HKA_CART_PREPARATION'
  const properties = kind === 'HKA_CART_PREPARATION' ? { ...common, notes: { type: 'string', title: '补充说明（选填）' } }
    : kind === 'HKA_LOST_PROPERTY' ? { ...common, roomNumber: { type: 'string', title: '房号' }, itemName: { type: 'string', title: '遗留物品名称' },
      quantity: { type: 'integer', minimum: 1, title: '数量' }, foundAt: { type: 'string', title: '发现时间' },
      foundLocation: { type: 'string', title: '发现位置' }, handoverRecipientAssignmentId: { type: 'string', title: '遗留物接收人' } }
    : { roomNumber: { type: 'string', title: '房号' }, [kind === 'HKA_COMPLAINT_EVENT' ? 'complaintContent' : 'serviceContent']:
      { type: 'string', title: kind === 'HKA_COMPLAINT_EVENT' ? '客诉内容' : '服务内容描述' } }
  const item = { id: 'fixture-'+key, itemCode: kind, itemName: titles[kind], name: titles[kind], title: titles[kind],
    orgUnitId: 'test-hotel', targetOrgName: '验证门店', assigneeName: '验收员工', businessDate: '2026-09-21',
    assignmentId: 'test-assignment', formVersionId: 'test-form', workPackageVersionId: 'test-version', workPackageItemId: 'test-item',
    status: 'AVAILABLE', formName: titles[kind], formSchema: { properties, required: occurrence ? Object.keys(properties).filter(k=>!Object.keys(common).includes(k)) : [] },
    executionPolicy: { repeatableDailyRecords: occurrence, registrationMode: occurrence ? 'WHEN_OCCURRED' : undefined },
    submissionPolicy: { completionStatementRequired: false, exceptionStatementRequired: false, nextActionRequired: false,
      attachmentRequired: false, maxAttachments: 200, maxFileSizeBytes: 20971520, allowedExtensions: ['jpg','jpeg','png','pdf'], evidenceRequirements: [] } }
  return <main style={{ padding: 24 }}><h1>工作登记交互验证</h1><p>已提交 {saved} 条</p>
    {Object.entries(titles).map(([code,title])=><button className="secondary" key={code} onClick={()=>{ setKey(k=>k+1); setKind(code) }}>{title}</button>)}
    <button className="primary" onClick={()=>setKind('task')}>工作下达</button>
    {kind === 'task' ? <TaskCreateDialog identity={identity} permissions={[]} initialHotelId="test-hotel" onClose={()=>setKind('')} onCreated={async()=>setSaved(n=>n+1)} />
      : kind && <WorkRecordDialog key={key} item={item} identity={identity} onClose={()=>setKind('')} onSaved={async()=>setSaved(n=>n+1)} onDelegated={()=>{}} />}
  </main>
}
createRoot(document.getElementById('root')!).render(<Fixture />)
