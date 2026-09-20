import { useEffect, useMemo, useState } from 'react'
import { apiRequest, asList } from './api/client'
import type { RoleContext, RouteParams } from './domain'
import { PositionAdministration } from './features/organization/PositionAdministration'
import { PersonnelDirectory, initialPersonnelDirectory } from './features/organization/PersonnelDirectory'
import { isEmployeePermanentDeleteConfirmation } from './features/organization/employeeDeleteConfirmation'
import { WorkPackageItemEditor } from './features/workPackages/WorkPackageItemEditor'
import { mapWorkPackageItem, serializeWorkPackageItems, validateWorkPackageItems, type WorkPackageItemDraft } from './features/workPackages/configuration'

type Row = Record<string, unknown>
type OrgUnit = { id: string; parentId?: string; code: string; name: string; unitType: string; status: string; sortOrder: number; propertyCode?: string; city?: string; roomCount?: number; openingDate?: string; breakfastServiceEnabled?: boolean; guestRoomFloorCount?: number; assistantWorkTemplateMode?: string; kitchenEnabled?: boolean; afternoonTeaEnabled?: boolean; barEnabled?: boolean; projectionEnabled?: boolean; gymEnabled?: boolean; terraceEnabled?: boolean; trendyPlayAreaEnabled?: boolean; facilityEvidenceInstances: Record<string, string[]>; assetEvidenceInstances: Record<string, string[]> }
type Position = { id: string; name: string; status: string; authorizationScopeType?: string; appliesToAllHotels?: boolean; applicableHotelIds: string[] }
type Employee = { id: string; accountId?: string; loginName?: string; employeeNo: string; name: string; mobile?: string; hiredOn?: string; employmentStatus: string; accountStatus?: string; assignmentId?: string; orgUnitId?: string; orgUnitName?: string; positionId?: string; positionName?: string; managerAssignmentId?: string; primary?: boolean; assignmentType?: string; assignmentStatus?: string; validFrom?: string; validTo?: string; authorizationScopeType?: string; responsibleHotelIds: string[]; responsibleHotelNames?: string; deletedAt?: string; deletedByName?: string }
type PackageRow = { id: string; code: string; name: string; positionName: string; lifecycleStatus: string; versionNo: number; latestVersionId?: string; ownerOrgName?: string }
type SelectRow = { id: string; code: string; name: string; status?: string; versionId?: string; positionId?: string }
type AllocationRow = { id: string; assignmentId: string; targetOrgName: string; assigneeName: string; status: string; validFrom: string }

const field = (row: Row, ...keys: string[]) => keys.map((key) => row[key]).find((value) => value !== undefined && value !== null)
const text = (row: Row, ...keys: string[]) => String(field(row, ...keys) ?? '')
const bool = (row: Row, ...keys: string[]) => Boolean(field(row, ...keys))
const jsonObject = (raw: unknown): Row => {
  if (typeof raw === 'string') {
    try { return JSON.parse(raw) as Row } catch { return {} }
  }
  const wrapped = raw && typeof raw === 'object' ? raw as Row : {}
  if ((wrapped.type === 'json' || wrapped.type === 'jsonb') && typeof wrapped.value === 'string') {
    try { return JSON.parse(wrapped.value) as Row } catch { return {} }
  }
  return wrapped
}
const stringArrayMap = (raw: unknown): Record<string, string[]> => Object.fromEntries(
  Object.entries(jsonObject(raw)).map(([key, value]) => [key, asList<unknown>(value)
    .filter((entry): entry is string => typeof entry === 'string')]),
)
const parseEvidenceList = (value: string) => [...new Set(value.split(/[\n,，]/)
  .map((entry) => entry.trim()).filter(Boolean))]
const formatEvidenceList = (values?: string[]) => (values ?? []).join('\n')
const dateTimeFormatter = new Intl.DateTimeFormat('zh-CN', { dateStyle: 'medium', timeStyle: 'short' })
const formatDateTime = (value?: string) => value ? dateTimeFormatter.format(new Date(value)) : '—'
const mapEmployee = (row: Row): Employee => ({
  id: text(row, 'id'), accountId: text(row, 'account_id', 'accountId') || undefined,
  loginName: text(row, 'login_name', 'loginName') || undefined, employeeNo: text(row, 'employee_no', 'employeeNo'),
  name: text(row, 'name'), mobile: text(row, 'mobile') || undefined,
  hiredOn: text(row, 'hired_on', 'hiredOn') || undefined,
  employmentStatus: text(row, 'employment_status', 'employmentStatus') || 'ACTIVE',
  accountStatus: text(row, 'account_status', 'accountStatus') || undefined,
  assignmentId: text(row, 'assignment_id', 'assignmentId') || undefined,
  orgUnitId: text(row, 'org_unit_id', 'orgUnitId') || undefined, orgUnitName: text(row, 'org_unit_name', 'orgUnitName') || undefined,
  positionId: text(row, 'position_id', 'positionId') || undefined, positionName: text(row, 'position_name', 'positionName') || undefined,
  managerAssignmentId: text(row, 'manager_assignment_id', 'managerAssignmentId') || undefined,
  primary: bool(row, 'is_primary', 'primary'),
  assignmentType: text(row, 'assignment_type', 'assignmentType') || undefined,
  assignmentStatus: text(row, 'assignment_status', 'assignmentStatus') || undefined,
  validFrom: text(row, 'valid_from', 'validFrom') || undefined,
  validTo: text(row, 'valid_to', 'validTo') || undefined,
  authorizationScopeType: text(row, 'authorization_scope_type', 'authorizationScopeType') || undefined,
  responsibleHotelIds: text(row, 'responsible_hotel_ids', 'responsibleHotelIds').split(',').filter(Boolean),
  responsibleHotelNames: text(row, 'responsible_hotel_names', 'responsibleHotelNames') || undefined,
  deletedAt: text(row, 'deleted_at', 'deletedAt') || undefined,
  deletedByName: text(row, 'deleted_by_name', 'deletedByName') || undefined,
})
async function loadSetup(identity: RoleContext, includeDeleted = false, includeMaintenance = true, includeOrgUnits = true) {
  const [orgRaw, positionRaw, employeeRaw, deletedEmployeeRaw] = await Promise.all([
    includeOrgUnits ? apiRequest<unknown>('/org/units', identity) : Promise.resolve([]),
    includeMaintenance ? apiRequest<unknown>('/org/position-options', identity) : Promise.resolve([]),
    includeMaintenance ? apiRequest<unknown>('/org/employees?includeHistory=true', identity) : Promise.resolve([]),
    includeDeleted ? apiRequest<unknown>('/org/employees/deleted', identity) : Promise.resolve([]),
  ])
  const orgUnits = asList<Row>(orgRaw).map((row): OrgUnit => {
    const evidenceConfiguration = jsonObject(field(
      row, 'evidence_instance_configuration', 'evidenceInstanceConfiguration'))
    return {
    id: text(row, 'id'), parentId: text(row, 'parent_id', 'parentId') || undefined,
    code: text(row, 'code'), name: text(row, 'name'), unitType: text(row, 'unit_type', 'unitType'),
    status: text(row, 'status') || 'ACTIVE', sortOrder: Number(field(row, 'sort_order', 'sortOrder') ?? 0),
    propertyCode: text(row, 'property_code', 'propertyCode') || undefined,
    city: text(row, 'city') || undefined, roomCount: field(row, 'room_count', 'roomCount') === undefined ? undefined : Number(field(row, 'room_count', 'roomCount')),
    openingDate: text(row, 'opening_date', 'openingDate') || undefined,
    breakfastServiceEnabled: bool(row, 'breakfast_service_enabled', 'breakfastServiceEnabled'),
    guestRoomFloorCount: field(row, 'guest_room_floor_count', 'guestRoomFloorCount') === undefined ? undefined : Number(field(row, 'guest_room_floor_count', 'guestRoomFloorCount')),
    assistantWorkTemplateMode: String(field(row, 'assistant_work_template_mode', 'assistantWorkTemplateMode') ?? 'WITH_FRONT_OFFICE_SUPERVISOR'),
    kitchenEnabled: bool(row, 'kitchen_enabled', 'kitchenEnabled'),
    afternoonTeaEnabled: bool(row, 'afternoon_tea_enabled', 'afternoonTeaEnabled'),
    barEnabled: bool(row, 'bar_enabled', 'barEnabled'),
    projectionEnabled: bool(row, 'projection_enabled', 'projectionEnabled'),
    gymEnabled: bool(row, 'gym_enabled', 'gymEnabled'),
    terraceEnabled: bool(row, 'terrace_enabled', 'terraceEnabled'),
    trendyPlayAreaEnabled: bool(row, 'trendy_play_area_enabled', 'trendyPlayAreaEnabled'),
    facilityEvidenceInstances: stringArrayMap(evidenceConfiguration.facilityInstances),
    assetEvidenceInstances: stringArrayMap(evidenceConfiguration.assetInstances),
  }})
  const positions = asList<Row>(positionRaw).map((row): Position => ({
    id: text(row, 'id'), name: text(row, 'name'), status: text(row, 'status') || 'ACTIVE',
    authorizationScopeType: text(row, 'authorization_scope_type', 'authorizationScopeType') || undefined,
    appliesToAllHotels: bool(row, 'applies_to_all_hotels', 'appliesToAllHotels'),
    applicableHotelIds: text(row, 'applicable_hotel_ids', 'applicableHotelIds').split(',').filter(Boolean),
  }))
  const employeeHistory = asList<Row>(employeeRaw).map(mapEmployee)
  const employees = employeeHistory.filter(item => !item.assignmentId || !item.assignmentStatus || item.assignmentStatus === 'ACTIVE')
  const deletedEmployees = asList<Row>(deletedEmployeeRaw).map(mapEmployee)
  return { orgUnits, positions, employees, employeeHistory, deletedEmployees }
}

function Modal({ title, children, onClose, onSave, saving, saveLabel = '保存', saveDisabled = false, wide = false, className = '' }: { title: string; children: React.ReactNode; onClose: () => void; onSave: () => void; saving: boolean; saveLabel?: string; saveDisabled?: boolean; wide?: boolean; className?: string }) {
  return <div className="modal-backdrop"><section className={`modal configuration-modal ${wide ? 'work-package-editor-modal' : ''} ${className}`.trim()} role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">CONFIGURATION</span><h2>{title}</h2></div><button className="close" onClick={onClose}>×</button></header>
    <div className="form-body configuration-form">{children}</div>
    <footer><button className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={saving || saveDisabled} onClick={onSave}>{saving ? '保存中…' : saveLabel}</button></footer>
  </section></div>
}

export function OrganizationCenter({ identity, permissions, routeParams = {} }: { identity: RoleContext; permissions: string[]; routeParams?: RouteParams }) {
  const canManage = permissions.includes('org.manage') || permissions.includes('*')
  const canReadOrg = permissions.includes('org.read') || permissions.includes('*')
  const canReadPosition = permissions.includes('position-profile.read') || permissions.includes('*')
  const canManagePosition = permissions.includes('position-profile.manage') || permissions.includes('*')
  const canPublishPosition = permissions.includes('position-profile.publish') || permissions.includes('*')
  const [data, setData] = useState<{ orgUnits: OrgUnit[]; positions: Position[]; employees: Employee[]; employeeHistory: Employee[]; deletedEmployees: Employee[] }>({ orgUnits: [], positions: [], employees: [], employeeHistory: [], deletedEmployees: [] })
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const [modal, setModal] = useState<'org' | 'employee' | 'assignment-scope'>()
  const [editingId, setEditingId] = useState<string>()
  const [editingAssignmentId, setEditingAssignmentId] = useState<string>()
  const [saving, setSaving] = useState(false)
  const [tab, setTab] = useState<'org' | 'position' | 'employee'>(canReadPosition && (!canManage || routeParams.tab === 'position') ? 'position' : routeParams.tab === 'employee' ? 'employee' : 'org')
  const [employeeView, setEmployeeView] = useState<'current' | 'deleted'>('current')
  const [personnelView, setPersonnelView] = useState(initialPersonnelDirectory)
  useEffect(() => setPersonnelView(initialPersonnelDirectory), [identity.key])
  const [orgForm, setOrgForm] = useState({ unitType: 'HOTEL', parentId: '', code: '', name: '', propertyCode: '', city: '', roomCount: '', openingDate: '', breakfastServiceEnabled: false, guestRoomFloorCount: '1', assistantWorkTemplateMode: 'WITH_FRONT_OFFICE_SUPERVISOR', kitchenEnabled: false, afternoonTeaEnabled: false, barEnabled: false, projectionEnabled: false, gymEnabled: false, terraceEnabled: false, trendyPlayAreaEnabled: false, serviceFacilityIds: '百宝箱\n水吧台\n行李房', publicAreaFacilityIds: '大堂\n百宝箱\n水吧台\n行李房\n洗衣房', eveningPublicAreaFacilityIds: '门头灯光\n前厅大堂\n洗衣房\n楼层过道及电梯间', warmingEquipmentIds: '保温设备', equipmentAssetIds: '空气能机组', sortOrder: '0', status: 'ACTIVE' })
  const [employeeForm, setEmployeeForm] = useState({ employeeNo: '', name: '', mobile: '', hiredOn: new Date().toISOString().slice(0, 10), loginName: '', temporaryPassword: '', employmentStatus: 'ACTIVE' })
  const [assignmentForm, setAssignmentForm] = useState({ assignmentId: '', employeeId: '', employeeName: '', orgUnitId: '', positionId: '', managerAssignmentId: '', primary: true, assignmentType: 'PERMANENT', validFrom: new Date().toISOString().slice(0, 10), validTo: '', responsibleHotelIds: [] as string[] })

  const reload = async () => {
    setLoading(true); setError(undefined)
    try { setData(await loadSetup(identity, canManage, canManage, canManage || (canManagePosition && canReadOrg))) } catch (reason) { setError(reason instanceof Error ? reason.message : '组织数据加载失败') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key, canManage, canManagePosition, canReadOrg])
  useEffect(() => {
    if (routeParams.tab === 'position' && canReadPosition) setTab('position')
    else if ((routeParams.tab === 'employee' || routeParams.tab === 'org') && canManage) setTab(routeParams.tab)
    else if (tab === 'position' && !canReadPosition) setTab('org')
  }, [routeParams.tab, canReadPosition, canManage, tab])
  const orgDepth = useMemo(() => {
    const byId = new Map(data.orgUnits.map((item) => [item.id, item]))
    const depth = (item: OrgUnit) => { let result = 0; let current = item; const seen = new Set<string>(); while (current.parentId && byId.has(current.parentId) && !seen.has(current.id)) { seen.add(current.id); result += 1; current = byId.get(current.parentId)! } return result }
    return new Map(data.orgUnits.map((item) => [item.id, depth(item)]))
  }, [data.orgUnits])
  const primaryEmployees = useMemo(() => {
    const grouped = new Map<string, Employee[]>()
    for (const employee of data.employeeHistory) grouped.set(employee.id, [...(grouped.get(employee.id) ?? []), employee])
    return [...grouped.values()].map((rows) => rows.find((row) => row.primary) ?? rows[0])
  }, [data.employeeHistory])
  const inactiveEmployeeCount = useMemo(() => primaryEmployees.filter((item) => item.employmentStatus === 'INACTIVE').length, [primaryEmployees])
  const employeeAssignments = editingId ? data.employees.filter((item) => item.id === editingId && item.assignmentId) : []
  const selectedAssignmentPosition = data.positions.find((item) => item.id === assignmentForm.positionId)
  const selectableAssignmentHotels = data.orgUnits.filter((item) => item.status === 'ACTIVE' && item.unitType === 'HOTEL'
    && (selectedAssignmentPosition?.appliesToAllHotels || selectedAssignmentPosition?.applicableHotelIds.includes(item.id)))
  const assignmentDraftStarted = Boolean(assignmentForm.orgUnitId || assignmentForm.positionId || assignmentForm.managerAssignmentId || assignmentForm.responsibleHotelIds.length)
  const assignmentDraftInvalid = assignmentDraftStarted && (!assignmentForm.orgUnitId || !assignmentForm.positionId || !assignmentForm.validFrom || employeeForm.employmentStatus !== 'ACTIVE'
    || Boolean(assignmentForm.validTo && assignmentForm.validTo < assignmentForm.validFrom)
    || (selectedAssignmentPosition?.authorizationScopeType === 'ASSIGNED_HOTELS' && assignmentForm.responsibleHotelIds.length === 0))
  const assignmentEditInvalid = Boolean(editingAssignmentId) && (!assignmentForm.orgUnitId || !assignmentForm.positionId || !assignmentForm.validFrom
    || Boolean(assignmentForm.validTo && assignmentForm.validTo < assignmentForm.validFrom)
    || (selectedAssignmentPosition?.authorizationScopeType === 'ASSIGNED_HOTELS' && assignmentForm.responsibleHotelIds.length === 0))

  const closeModal = () => { setModal(undefined); setEditingId(undefined); setEditingAssignmentId(undefined) }
  const openCreate = (kind: 'org' | 'employee') => {
    setEditingId(undefined); setError(undefined)
    if (kind === 'org') setOrgForm({ unitType: 'HOTEL', parentId: '', code: '', name: '', propertyCode: '', city: '', roomCount: '', openingDate: '', breakfastServiceEnabled: false, guestRoomFloorCount: '1', assistantWorkTemplateMode: 'WITH_FRONT_OFFICE_SUPERVISOR', kitchenEnabled: false, afternoonTeaEnabled: false, barEnabled: false, projectionEnabled: false, gymEnabled: false, terraceEnabled: false, trendyPlayAreaEnabled: false, serviceFacilityIds: '百宝箱\n水吧台\n行李房', publicAreaFacilityIds: '大堂\n百宝箱\n水吧台\n行李房\n洗衣房', eveningPublicAreaFacilityIds: '门头灯光\n前厅大堂\n洗衣房\n楼层过道及电梯间', warmingEquipmentIds: '保温设备', equipmentAssetIds: '空气能机组', sortOrder: '0', status: 'ACTIVE' })
    if (kind === 'employee') {
      setEmployeeForm({ employeeNo: '', name: '', mobile: '', hiredOn: new Date().toISOString().slice(0, 10), loginName: '', temporaryPassword: '', employmentStatus: 'ACTIVE' })
      setAssignmentForm({ assignmentId: '', employeeId: '', employeeName: '', orgUnitId: '', positionId: '', managerAssignmentId: '', primary: true, assignmentType: 'PERMANENT', validFrom: new Date().toISOString().slice(0, 10), validTo: '', responsibleHotelIds: [] })
    }
    setModal(kind)
  }
  const openOrgEdit = (item: OrgUnit) => {
    setEditingId(item.id); setError(undefined)
    setOrgForm({ unitType: item.unitType, parentId: item.parentId ?? '', code: item.code, name: item.name, propertyCode: item.propertyCode ?? '', city: item.city ?? '', roomCount: item.roomCount === undefined ? '' : String(item.roomCount), openingDate: item.openingDate ?? '', breakfastServiceEnabled: Boolean(item.breakfastServiceEnabled), guestRoomFloorCount: String(item.guestRoomFloorCount ?? 1), assistantWorkTemplateMode: item.assistantWorkTemplateMode ?? 'WITH_FRONT_OFFICE_SUPERVISOR', kitchenEnabled: Boolean(item.kitchenEnabled), afternoonTeaEnabled: Boolean(item.afternoonTeaEnabled), barEnabled: Boolean(item.barEnabled), projectionEnabled: Boolean(item.projectionEnabled), gymEnabled: Boolean(item.gymEnabled), terraceEnabled: Boolean(item.terraceEnabled), trendyPlayAreaEnabled: Boolean(item.trendyPlayAreaEnabled), serviceFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.serviceFacilityIds), publicAreaFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.publicAreaFacilityIds), eveningPublicAreaFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.eveningPublicAreaFacilityIds), warmingEquipmentIds: formatEvidenceList(item.assetEvidenceInstances.warmingEquipmentIds), equipmentAssetIds: formatEvidenceList(item.assetEvidenceInstances.equipmentAssetIds), sortOrder: String(item.sortOrder), status: item.status })
    setModal('org')
  }
  const openEmployeeEdit = (item: Employee) => {
    setEditingId(item.id); setError(undefined)
    setEmployeeForm({ employeeNo: item.employeeNo, name: item.name, mobile: item.mobile ?? '', hiredOn: item.hiredOn ?? '', loginName: item.loginName ?? '', temporaryPassword: '', employmentStatus: item.employmentStatus })
    setEditingAssignmentId(undefined)
    setAssignmentForm({ assignmentId: '', employeeId: item.id, employeeName: item.name, orgUnitId: '', positionId: '', managerAssignmentId: '', primary: !data.employees.some((row) => row.id === item.id && row.assignmentId), assignmentType: 'PERMANENT', validFrom: new Date().toISOString().slice(0, 10), validTo: '', responsibleHotelIds: [] })
    setModal('employee')
  }
  const orgBody = (form = orgForm) => ({ code: form.code, name: form.name, sortOrder: form.sortOrder ? Number(form.sortOrder) : 0, status: form.status, propertyCode: form.unitType === 'HOTEL' ? form.propertyCode : null, city: form.unitType === 'HOTEL' ? form.city || null : null, roomCount: form.unitType === 'HOTEL' && form.roomCount ? Number(form.roomCount) : null, openingDate: form.unitType === 'HOTEL' ? form.openingDate || null : null, breakfastServiceEnabled: form.unitType === 'HOTEL' && form.breakfastServiceEnabled, guestRoomFloorCount: form.unitType === 'HOTEL' ? Number(form.guestRoomFloorCount || 1) : null, assistantWorkTemplateMode: form.unitType === 'HOTEL' ? form.assistantWorkTemplateMode : null, kitchenEnabled: form.unitType === 'HOTEL' ? form.kitchenEnabled : null, afternoonTeaEnabled: form.unitType === 'HOTEL' ? form.afternoonTeaEnabled : null, barEnabled: form.unitType === 'HOTEL' ? form.barEnabled : null, projectionEnabled: form.unitType === 'HOTEL' ? form.projectionEnabled : null, gymEnabled: form.unitType === 'HOTEL' ? form.gymEnabled : null, terraceEnabled: form.unitType === 'HOTEL' ? form.terraceEnabled : null, trendyPlayAreaEnabled: form.unitType === 'HOTEL' ? form.trendyPlayAreaEnabled : null, facilityEvidenceInstances: form.unitType === 'HOTEL' ? { serviceFacilityIds: parseEvidenceList(form.serviceFacilityIds), publicAreaFacilityIds: parseEvidenceList(form.publicAreaFacilityIds), eveningPublicAreaFacilityIds: parseEvidenceList(form.eveningPublicAreaFacilityIds) } : null, assetEvidenceInstances: form.unitType === 'HOTEL' ? { warmingEquipmentIds: parseEvidenceList(form.warmingEquipmentIds), equipmentAssetIds: parseEvidenceList(form.equipmentAssetIds) } : null })
  const employeeBody = (form = employeeForm) => ({ employeeNo: form.employeeNo, name: form.name, mobile: form.mobile || null, hiredOn: form.hiredOn || null, employmentStatus: form.employmentStatus, loginName: form.loginName || null, temporaryPassword: form.temporaryPassword || null })
  const runMaintenance = async (request: () => Promise<unknown>, fallback: string, success?: string) => {
    setSaving(true); setError(undefined); setNotice(undefined)
    try { await request(); await reload(); if (success) setNotice(success); return true }
    catch (reason) { setError(reason instanceof Error ? reason.message : fallback); return false }
    finally { setSaving(false) }
  }
  const toggleOrg = (item: OrgUnit) => runMaintenance(() => apiRequest(`/org/units/${item.id}`, identity, { method: 'PUT', body: JSON.stringify(orgBody({ ...orgForm, unitType: item.unitType, code: item.code, name: item.name, sortOrder: String(item.sortOrder), status: item.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE', propertyCode: item.propertyCode ?? '', city: item.city ?? '', roomCount: item.roomCount === undefined ? '' : String(item.roomCount), openingDate: item.openingDate ?? '', breakfastServiceEnabled: Boolean(item.breakfastServiceEnabled), guestRoomFloorCount: String(item.guestRoomFloorCount ?? 1), assistantWorkTemplateMode: item.assistantWorkTemplateMode ?? 'WITH_FRONT_OFFICE_SUPERVISOR', kitchenEnabled: Boolean(item.kitchenEnabled), afternoonTeaEnabled: Boolean(item.afternoonTeaEnabled), barEnabled: Boolean(item.barEnabled), projectionEnabled: Boolean(item.projectionEnabled), gymEnabled: Boolean(item.gymEnabled), terraceEnabled: Boolean(item.terraceEnabled), trendyPlayAreaEnabled: Boolean(item.trendyPlayAreaEnabled), serviceFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.serviceFacilityIds), publicAreaFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.publicAreaFacilityIds), eveningPublicAreaFacilityIds: formatEvidenceList(item.facilityEvidenceInstances.eveningPublicAreaFacilityIds), warmingEquipmentIds: formatEvidenceList(item.assetEvidenceInstances.warmingEquipmentIds), equipmentAssetIds: formatEvidenceList(item.assetEvidenceInstances.equipmentAssetIds), parentId: item.parentId ?? '' })) }), '组织状态修改失败')
  const toggleEmployee = (item: Employee) => runMaintenance(() => apiRequest(`/org/employees/${item.id}`, identity, { method: 'PUT', body: JSON.stringify(employeeBody({ employeeNo: item.employeeNo, name: item.name, mobile: item.mobile ?? '', hiredOn: item.hiredOn ?? '', loginName: item.loginName ?? '', temporaryPassword: '', employmentStatus: item.employmentStatus === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })) }), '员工状态修改失败')
  const deleteMasterData = async (kind: 'units', id: string, name: string) => {
    if (!window.confirm(`确认永久删除“${name}”？\n\n仅未被业务数据引用且已停用的数据允许删除；有历史记录的数据会被系统拒绝。`)) return
    await runMaintenance(() => apiRequest(`/org/${kind}/${id}`, identity, { method: 'DELETE' }), '删除失败')
  }
  const deleteEmployee = async (item: Employee) => {
    if (!window.confirm(`确认将“${item.name}”移入“已删除”？\n\n账号、任职、角色和企业微信绑定将立即停用；业务与审计历史保留，可在“已删除”中恢复员工。`)) return
    await runMaintenance(() => apiRequest(`/org/employees/${item.id}`, identity, { method: 'DELETE' }), '删除员工失败', `“${item.name}”已移入“已删除”`)
  }
  const deleteAllInactiveEmployees = async () => {
    if (inactiveEmployeeCount === 0) return
    if (!window.confirm(`确认将当前范围内 ${inactiveEmployeeCount} 名已停用员工全部移入“已删除”？\n\n不会影响启用中的员工；账号、任职、角色和企业微信绑定保持停用，之后仍可逐人恢复。`)) return
    if (await runMaintenance(() => apiRequest('/org/employees/actions/delete-inactive', identity, { method: 'POST', body: '{}' }), '批量删除已停用员工失败', `已将 ${inactiveEmployeeCount} 名已停用员工移入“已删除”`)) setEmployeeView('deleted')
  }
  const restoreEmployee = async (item: Employee) => {
    if (!window.confirm(`确认恢复“${item.name}”？\n\n恢复后员工和账号仍保持停用，需要核对身份后再手动启用并重新分配任职。`)) return
    if (await runMaintenance(() => apiRequest(`/org/employees/${item.id}/restore`, identity, { method: 'POST', body: '{}' }), '恢复员工失败', `“${item.name}”已恢复并保持停用`)) setEmployeeView('current')
  }
  const permanentlyDeleteEmployee = async (item: Employee) => {
    const confirmation = window.prompt(`永久删除不可撤销。系统将清除“${item.name}”的账号身份与个人信息，只保留匿名业务和审计历史。\n\n请输入“确认”后继续：`)
    if (!isEmployeePermanentDeleteConfirmation(confirmation)) return
    await runMaintenance(() => apiRequest(`/org/employees/${item.id}/permanent`, identity, { method: 'DELETE' }), '永久删除员工失败', `“${item.name}”已永久删除，无法找回`)
  }
  const resetAssignmentDraft = () => {
    setEditingAssignmentId(undefined)
    setAssignmentForm({ assignmentId: '', employeeId: editingId ?? '', employeeName: employeeForm.name, orgUnitId: '', positionId: '', managerAssignmentId: '', primary: employeeAssignments.length === 0, assignmentType: 'PERMANENT', validFrom: new Date().toISOString().slice(0, 10), validTo: '', responsibleHotelIds: [] })
  }
  const openAssignmentEdit = (item: Employee) => {
    if (!item.assignmentId) return
    setEditingAssignmentId(item.assignmentId)
    setAssignmentForm({ assignmentId: item.assignmentId, employeeId: item.id, employeeName: item.name, orgUnitId: item.orgUnitId ?? '', positionId: item.positionId ?? '', managerAssignmentId: item.managerAssignmentId ?? '', primary: Boolean(item.primary), assignmentType: item.assignmentType ?? 'PERMANENT', validFrom: item.validFrom ?? new Date().toISOString().slice(0, 10), validTo: item.validTo ?? '', responsibleHotelIds: item.responsibleHotelIds })
  }
  const assignmentBody = () => ({ orgUnitId: assignmentForm.orgUnitId, positionId: assignmentForm.positionId, managerAssignmentId: assignmentForm.managerAssignmentId || null, primary: assignmentForm.primary, assignmentType: assignmentForm.assignmentType, validFrom: assignmentForm.validFrom, validTo: assignmentForm.validTo || null, responsibleHotelIds: assignmentForm.responsibleHotelIds })
  const saveCurrentAssignment = async () => {
    if (!editingAssignmentId || assignmentEditInvalid) return
    const current = employeeAssignments.find((item) => item.assignmentId === editingAssignmentId)
    const identityChanged = Boolean(current && (current.orgUnitId !== assignmentForm.orgUnitId || current.positionId !== assignmentForm.positionId))
    if (identityChanged && !window.confirm('本次修改包含组织或岗位变更。\n\n系统会结束原任职并新建任职，以保留历史任务、日报和证据链；原任职的直接下级将自动转接到新任职。确认继续？')) return
    const success = await runMaintenance(
      () => apiRequest(`/org/assignments/${editingAssignmentId}`, identity, { method: 'PUT', body: JSON.stringify(assignmentBody()) }),
      '任职修改失败', identityChanged ? '岗位变更已完成，原任职历史已保留' : '任职信息已更新',
    )
    if (success) resetAssignmentDraft()
  }
  const endCurrentAssignment = async (item: Employee) => {
    if (!item.assignmentId) return
    if (!window.confirm(`确认结束“${item.positionName || '未命名岗位'} · ${item.orgUnitName || '未设置组织'}”任职？\n\n岗位权限立即回收，历史任务、日报和证据链保留；如这是主岗，系统会自动选择另一项有效任职为主岗。`)) return
    const success = await runMaintenance(
      () => apiRequest(`/org/assignments/${item.assignmentId}`, identity, { method: 'DELETE' }),
      '结束任职失败', `已结束“${item.positionName || '未命名岗位'}”任职`,
    )
    if (success && editingAssignmentId === item.assignmentId) resetAssignmentDraft()
  }
  const submit = async () => {
    setSaving(true); setError(undefined)
    try {
      if (modal === 'org') {
        if (editingId) await apiRequest(`/org/units/${editingId}`, identity, { method: 'PUT', body: JSON.stringify(orgBody()) })
        else await apiRequest('/org/units', identity, { method: 'POST', body: JSON.stringify({ unitType: orgForm.unitType, parentId: orgForm.parentId || null, code: orgForm.code, name: orgForm.name, propertyCode: orgForm.unitType === 'HOTEL' ? orgForm.propertyCode : null, city: orgForm.city || null, roomCount: orgForm.roomCount ? Number(orgForm.roomCount) : null, openingDate: orgForm.openingDate || null, breakfastServiceEnabled: orgForm.unitType === 'HOTEL' && orgForm.breakfastServiceEnabled, guestRoomFloorCount: orgForm.unitType === 'HOTEL' ? Number(orgForm.guestRoomFloorCount || 1) : null, assistantWorkTemplateMode: orgForm.unitType === 'HOTEL' ? orgForm.assistantWorkTemplateMode : null, kitchenEnabled: orgForm.unitType === 'HOTEL' ? orgForm.kitchenEnabled : null, afternoonTeaEnabled: orgForm.unitType === 'HOTEL' ? orgForm.afternoonTeaEnabled : null, barEnabled: orgForm.unitType === 'HOTEL' ? orgForm.barEnabled : null, projectionEnabled: orgForm.unitType === 'HOTEL' ? orgForm.projectionEnabled : null, gymEnabled: orgForm.unitType === 'HOTEL' ? orgForm.gymEnabled : null, terraceEnabled: orgForm.unitType === 'HOTEL' ? orgForm.terraceEnabled : null, trendyPlayAreaEnabled: orgForm.unitType === 'HOTEL' ? orgForm.trendyPlayAreaEnabled : null, facilityEvidenceInstances: { serviceFacilityIds: parseEvidenceList(orgForm.serviceFacilityIds), publicAreaFacilityIds: parseEvidenceList(orgForm.publicAreaFacilityIds), eveningPublicAreaFacilityIds: parseEvidenceList(orgForm.eveningPublicAreaFacilityIds) }, assetEvidenceInstances: { warmingEquipmentIds: parseEvidenceList(orgForm.warmingEquipmentIds), equipmentAssetIds: parseEvidenceList(orgForm.equipmentAssetIds) }, sortOrder: orgForm.sortOrder ? Number(orgForm.sortOrder) : 0 }) })
      }
      if (modal === 'employee') {
        if (editingId) {
          await apiRequest(`/org/employees/${editingId}`, identity, { method: 'PUT', body: JSON.stringify(employeeBody()) })
          if (assignmentDraftStarted) await apiRequest(`/org/employees/${editingId}/assignments`, identity, { method: 'POST', body: JSON.stringify(assignmentBody()) })
        }
        else await apiRequest('/org/employees', identity, { method: 'POST', body: JSON.stringify({ employeeNo: employeeForm.employeeNo, name: employeeForm.name, mobile: employeeForm.mobile || null, hiredOn: employeeForm.hiredOn || null, loginName: employeeForm.loginName || null, temporaryPassword: employeeForm.temporaryPassword || null }) })
      }
      if (modal === 'assignment-scope') {
        await apiRequest(`/org/assignments/${assignmentForm.assignmentId}/hotel-scope`, identity, { method: 'PUT', body: JSON.stringify({ responsibleHotelIds: assignmentForm.responsibleHotelIds }) })
      }
      closeModal(); await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '保存失败') }
    finally { setSaving(false) }
  }

  return <section className="page-section configuration-page">
    <header className="page-title"><div><span className="eyebrow">ORGANIZATION & ACCESS</span><h1>组织、岗位与人员</h1><p>集团统一配置，门店账号只读取授权组织树；同一员工可绑定多个任职。</p></div><div className="page-actions"><span className="source-flag api">真实 PostgreSQL</span></div></header>
    <div className="config-tabs">{canManage && <button className={tab === 'org' ? 'active' : ''} onClick={() => setTab('org')}>门店与部门</button>}{canReadPosition && <button className={tab === 'position' ? 'active' : ''} onClick={() => setTab('position')}>岗位与功能</button>}{canManage && <button className={tab === 'employee' ? 'active' : ''} onClick={() => setTab('employee')}>人员与任职</button>}</div>
    {notice && <div className="inline-success page-success">{notice}</div>}
    {error && <div className="inline-error page-error">{error}</div>}
    {tab === 'position' ? <PositionAdministration identity={identity} canManage={canManagePosition} canPublish={canPublishPosition} positionId={routeParams.positionId} hotels={data.orgUnits.filter((item) => item.unitType === 'HOTEL' && item.status === 'ACTIVE').map((item) => ({ id: item.id, code: item.propertyCode ?? item.code, name: item.name }))} /> : loading ? <div className="state-card"><div className="spinner" /><strong>正在读取组织权限数据</strong></div> : <article className="panel table-panel config-panel">
      <header><div><span className="panel-kicker">{tab.toUpperCase()}</span><h2>{tab === 'org' ? '组织层级' : employeeView === 'current' ? '员工账号与多岗位任职' : '已删除员工'}</h2></div>{canManage && (tab !== 'employee' || employeeView === 'current') && <div className="panel-actions"><button className="primary" onClick={() => openCreate(tab === 'org' ? 'org' : 'employee')}>＋ 新建{tab === 'org' ? '组织' : '员工'}</button></div>}</header>
      {tab === 'org' && <div className="config-list">{data.orgUnits.map((item) => <div className={`config-row ${item.status === 'INACTIVE' ? 'inactive-row' : ''}`} key={item.id} style={{ paddingLeft: `${18 + (orgDepth.get(item.id) ?? 0) * 28}px` }}><i className={`org-icon ${item.unitType.toLowerCase()}`}>{item.unitType === 'HOTEL' ? '店' : item.unitType === 'DEPARTMENT' ? '部' : item.unitType === 'REGION' ? '区' : '集'}</i><span><strong>{item.name}</strong><small>{item.code} · {item.unitType}{item.city ? ` · ${item.city}` : ''}{item.roomCount !== undefined ? ` · ${item.roomCount}间` : ''}{item.unitType === 'HOTEL' ? ` · ${item.breakfastServiceEnabled ? '有早餐' : '无早餐'} · ${item.guestRoomFloorCount ?? 1}个客房楼层 · ${item.assistantWorkTemplateMode === 'WITHOUT_FRONT_OFFICE_SUPERVISOR' ? '店助无前厅主管模板' : '店助有前厅主管模板'}` : ''}</small></span><b className={item.status === 'INACTIVE' ? 'inactive' : ''}>{item.status === 'ACTIVE' ? '启用' : '已停用'}</b>{canManage && <div className="maintenance-actions"><button className="text-action" onClick={() => openOrgEdit(item)}>编辑</button>{item.unitType !== 'GROUP' && <button className="text-action" disabled={saving} onClick={() => void toggleOrg(item)}>{item.status === 'ACTIVE' ? '停用' : '启用'}</button>}<button className="text-action danger" disabled={saving || item.status === 'ACTIVE' || item.unitType === 'GROUP'} title={item.status === 'ACTIVE' ? '请先停用后再删除' : '删除后从组织配置隐藏，历史业务记录保留'} onClick={() => void deleteMasterData('units', item.id, item.name)}>删除</button></div>}</div>)}</div>}
      {tab === 'employee' && <>
        {canManage && <div className="employee-view-toolbar">
          <div className="employee-view-tabs" role="tablist" aria-label="员工数据视图">
            <button className={employeeView === 'current' ? 'active' : ''} role="tab" aria-selected={employeeView === 'current'} onClick={() => setEmployeeView('current')}>人员列表</button>
            <button className={employeeView === 'deleted' ? 'active' : ''} role="tab" aria-selected={employeeView === 'deleted'} onClick={() => setEmployeeView('deleted')}>已删除 <span>{data.deletedEmployees.length}</span></button>
          </div>
          {employeeView === 'current' && inactiveEmployeeCount > 0 && <button className="secondary danger-action" disabled={saving} onClick={() => void deleteAllInactiveEmployees()}>删除全部已停用（{inactiveEmployeeCount}）</button>}
        </div>}
        {employeeView === 'current' && <PersonnelDirectory orgs={data.orgUnits} employees={data.employeeHistory} state={personnelView} setState={setPersonnelView}
          saving={saving} onEdit={openEmployeeEdit} onEditAssignment={item => { openEmployeeEdit(item); openAssignmentEdit(item) }}
          onEnd={item => void endCurrentAssignment(item)} onToggle={item => void toggleEmployee(item)}
          onDelete={item => void deleteEmployee(item)} />}
        {canManage && employeeView === 'deleted' && <>
          <div className="recycle-bin-note"><strong>恢复后仍保持停用。</strong><span>请核对身份后再手动启用并重新分配任职；“永久删除”会清除身份信息，无法找回。</span></div>
          <div className="simple-table deleted-employees-table maintenance-table">
            <div className="simple-head"><span>员工</span><span>原登录账号</span><span>最近组织</span><span>最近岗位</span><span>删除记录</span><span>操作</span></div>
            {data.deletedEmployees.length === 0 && <div className="empty-table-state"><strong>“已删除”中暂无员工</strong><span>删除的已停用员工会显示在这里。</span></div>}
            {data.deletedEmployees.map((item) => <div key={item.id}>
              <span><strong>{item.name}</strong><small>{item.employeeNo}</small></span><span>{item.loginName || '未开通'}</span><span>{item.orgUnitName || '未分配'}</span><span>{item.positionName || '未分配'}</span><span><strong>{formatDateTime(item.deletedAt)}</strong><small>{item.deletedByName ? `操作人：${item.deletedByName}` : '操作人：—'}</small></span>
              <span className="maintenance-actions"><button className="text-action" disabled={saving} onClick={() => void restoreEmployee(item)}>恢复</button><button className="text-action danger" disabled={saving} onClick={() => void permanentlyDeleteEmployee(item)}>永久删除</button></span>
            </div>)}
          </div>
        </>}
      </>}
    </article>}

    {modal === 'org' && <Modal title={editingId ? '编辑组织/门店' : '新建组织/门店'} onClose={closeModal} onSave={submit} saving={saving}><div className="form-grid"><label>组织类型<select disabled={Boolean(editingId)} value={orgForm.unitType} onChange={(event) => setOrgForm({ ...orgForm, unitType: event.target.value })}><option value="REGION">区域</option><option value="HOTEL">门店</option><option value="DEPARTMENT">部门</option><option value="GROUP">集团</option></select></label><label>上级组织<select disabled={Boolean(editingId)} value={orgForm.parentId} onChange={(event) => setOrgForm({ ...orgForm, parentId: event.target.value })}><option value="">无（仅集团）</option>{data.orgUnits.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name} · {item.unitType}</option>)}</select></label><label>组织编码<input value={orgForm.code} onChange={(event) => setOrgForm({ ...orgForm, code: event.target.value })} /></label><label>组织名称<input value={orgForm.name} onChange={(event) => setOrgForm({ ...orgForm, name: event.target.value })} /></label><label>排序<input type="number" min="0" value={orgForm.sortOrder} onChange={(event) => setOrgForm({ ...orgForm, sortOrder: event.target.value })} /></label>{editingId && <label>状态<select disabled={orgForm.unitType === 'GROUP'} value={orgForm.status} onChange={(event) => setOrgForm({ ...orgForm, status: event.target.value })}><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label>}{orgForm.unitType === 'HOTEL' && <><label>门店编码<input value={orgForm.propertyCode} onChange={(event) => setOrgForm({ ...orgForm, propertyCode: event.target.value })} /></label><label>城市<input value={orgForm.city} onChange={(event) => setOrgForm({ ...orgForm, city: event.target.value })} /></label><label>房间数<input type="number" min="0" value={orgForm.roomCount} onChange={(event) => setOrgForm({ ...orgForm, roomCount: event.target.value })} /></label><label>客房楼层数<input type="number" min="1" max="100" value={orgForm.guestRoomFloorCount} onChange={(event) => setOrgForm({ ...orgForm, guestRoomFloorCount: event.target.value })} /></label><label>开业日期<input type="date" value={orgForm.openingDate} onChange={(event) => setOrgForm({ ...orgForm, openingDate: event.target.value })} /></label><label>店助日清模板<select value={orgForm.assistantWorkTemplateMode} onChange={(event) => setOrgForm({ ...orgForm, assistantWorkTemplateMode: event.target.value })}><option value="WITH_FRONT_OFFICE_SUPERVISOR">有前厅主管门店</option><option value="WITHOUT_FRONT_OFFICE_SUPERVISOR">无前厅主管门店</option></select></label><label className="checkbox-label"><input type="checkbox" checked={orgForm.breakfastServiceEnabled} onChange={(event) => setOrgForm({ ...orgForm, breakfastServiceEnabled: event.target.checked })} />供应早餐并启用早餐厅巡查</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.kitchenEnabled} onChange={(event) => setOrgForm({ ...orgForm, kitchenEnabled: event.target.checked })} />配置后厨巡查</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.afternoonTeaEnabled} onChange={(event) => setOrgForm({ ...orgForm, afternoonTeaEnabled: event.target.checked })} />提供下午茶</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.barEnabled} onChange={(event) => setOrgForm({ ...orgForm, barEnabled: event.target.checked })} />配置酒吧/吧台</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.projectionEnabled} onChange={(event) => setOrgForm({ ...orgForm, projectionEnabled: event.target.checked })} />配置音乐投影</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.gymEnabled} onChange={(event) => setOrgForm({ ...orgForm, gymEnabled: event.target.checked })} />配置健身房</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.terraceEnabled} onChange={(event) => setOrgForm({ ...orgForm, terraceEnabled: event.target.checked })} />配置露台</label><label className="checkbox-label"><input type="checkbox" checked={orgForm.trendyPlayAreaEnabled} onChange={(event) => setOrgForm({ ...orgForm, trendyPlayAreaEnabled: event.target.checked })} />配置潮玩区</label><label className="full-field">前厅服务设施证据清单<textarea rows={3} value={orgForm.serviceFacilityIds} onChange={(event) => setOrgForm({ ...orgForm, serviceFacilityIds: event.target.value })} /><small>每行填写一个门店真实业务名称，例如百宝箱、水吧台、行李房。</small></label><label className="full-field">公共区域证据清单<textarea rows={4} value={orgForm.publicAreaFacilityIds} onChange={(event) => setOrgForm({ ...orgForm, publicAreaFacilityIds: event.target.value })} /></label><label className="full-field">晚间公共区域证据清单<textarea rows={3} value={orgForm.eveningPublicAreaFacilityIds} onChange={(event) => setOrgForm({ ...orgForm, eveningPublicAreaFacilityIds: event.target.value })} /></label><label className="full-field">保温设备证据清单<textarea rows={2} value={orgForm.warmingEquipmentIds} onChange={(event) => setOrgForm({ ...orgForm, warmingEquipmentIds: event.target.value })} /></label><label className="full-field">空气能等设备证据清单<textarea rows={2} value={orgForm.equipmentAssetIds} onChange={(event) => setOrgForm({ ...orgForm, equipmentAssetIds: event.target.value })} /><small>员工端只能查看本清单并逐项上传，不能自行新增、删除或用空数组绕过；启用对应工作前请勿留空。</small></label></>}</div>{editingId && <div className="inline-warning">为保护组织树与历史权限，编辑时不允许更改组织类型和上级组织。店助模板类型必须由管理员明确选择，临时休假只使用当日转交，不自动切换模板。</div>}</Modal>}
    {modal === 'employee' && <Modal title={editingId ? '编辑员工与任职' : '新建员工与登录账号'} onClose={closeModal} onSave={submit} saving={saving} saveDisabled={Boolean(editingId) && (assignmentDraftInvalid || Boolean(editingAssignmentId))} className={editingId ? 'employee-edit-modal' : ''}>
      <section className="employee-edit-section">
        <header><div><span className="panel-kicker">EMPLOYEE PROFILE</span><h3>员工资料与登录账号</h3></div></header>
        <div className="form-grid"><label>员工编号<input value={employeeForm.employeeNo} onChange={(event) => setEmployeeForm({ ...employeeForm, employeeNo: event.target.value })} /></label><label>姓名<input value={employeeForm.name} onChange={(event) => setEmployeeForm({ ...employeeForm, name: event.target.value })} /></label><label>手机号<input value={employeeForm.mobile} onChange={(event) => setEmployeeForm({ ...employeeForm, mobile: event.target.value })} /></label><label>入职日期<input type="date" value={employeeForm.hiredOn} onChange={(event) => setEmployeeForm({ ...employeeForm, hiredOn: event.target.value })} /></label><label>登录账号<input autoComplete="off" value={employeeForm.loginName} onChange={(event) => setEmployeeForm({ ...employeeForm, loginName: event.target.value })} placeholder="姓名拼音或工号" /></label><label>{editingId ? '重置密码（选填）' : '初始密码'}<input type="password" autoComplete="new-password" value={employeeForm.temporaryPassword} onChange={(event) => setEmployeeForm({ ...employeeForm, temporaryPassword: event.target.value })} placeholder={editingId ? '留空则不修改密码' : '至少10位'} /></label>{editingId && <label>在职状态<select value={employeeForm.employmentStatus} onChange={(event) => setEmployeeForm({ ...employeeForm, employmentStatus: event.target.value })}><option value="ACTIVE">启用</option><option value="INACTIVE">停用</option></select></label>}</div>
      </section>
      {editingId && <>
        <section className="employee-edit-section employee-current-assignments">
          <header><div><span className="panel-kicker">CURRENT ASSIGNMENTS</span><h3>当前任职</h3></div><small>共 {employeeAssignments.length} 项</small></header>
          {employeeAssignments.length === 0 ? <div className="employee-assignment-empty">该员工尚未分配任职</div> : <div className="employee-assignment-list">{employeeAssignments.map((item) => <article className={editingAssignmentId === item.assignmentId ? 'editing' : ''} key={item.assignmentId}><span><strong>{item.positionName || '未命名岗位'}</strong><small>{item.orgUnitName || '未设置组织'} · {item.assignmentType === 'TEMPORARY' ? '临时' : item.assignmentType === 'ACTING' ? '代理' : '正式'}</small><small>{item.validFrom || '—'} 至 {item.validTo || '长期'}</small></span><div className="assignment-card-actions"><b>{item.primary ? '主岗' : '兼岗'}</b><button type="button" className="text-action" disabled={saving} onClick={() => openAssignmentEdit(item)}>修改</button><button type="button" className="text-action danger" disabled={saving} onClick={() => void endCurrentAssignment(item)}>结束</button></div>{item.authorizationScopeType === 'ASSIGNED_HOTELS' && <small>负责门店：{item.responsibleHotelNames || '尚未配置'}</small>}</article>)}</div>}
          {editingAssignmentId && <div className="employee-assignment-editor">
            <header><div><strong>修改当前任职</strong><small>组织或岗位变更会自动保留原任职历史，并生成新的任职记录。</small></div><button type="button" className="text-action" onClick={resetAssignmentDraft}>取消修改</button></header>
            <div className="form-grid"><label>组织/门店<select value={assignmentForm.orgUnitId} onChange={(event) => setAssignmentForm({ ...assignmentForm, orgUnitId: event.target.value })}>{data.orgUnits.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>岗位<select value={assignmentForm.positionId} onChange={(event) => setAssignmentForm({ ...assignmentForm, positionId: event.target.value, responsibleHotelIds: [] })}>{data.positions.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>上级任职<select value={assignmentForm.managerAssignmentId} onChange={(event) => setAssignmentForm({ ...assignmentForm, managerAssignmentId: event.target.value })}><option value="">暂不设置</option>{data.employees.filter((item) => item.id !== editingId && item.assignmentId && item.employmentStatus === 'ACTIVE').map((item) => <option value={item.assignmentId} key={item.assignmentId}>{item.name} · {item.positionName}</option>)}</select></label><label>任职类型<select value={assignmentForm.assignmentType} onChange={(event) => setAssignmentForm({ ...assignmentForm, assignmentType: event.target.value })}><option value="PERMANENT">正式</option><option value="TEMPORARY">临时</option><option value="ACTING">代理</option></select></label><label>开始日期<input type="date" value={assignmentForm.validFrom} onChange={(event) => setAssignmentForm({ ...assignmentForm, validFrom: event.target.value })} /></label><label>结束日期（选填）<input type="date" min={assignmentForm.validFrom} value={assignmentForm.validTo} onChange={(event) => setAssignmentForm({ ...assignmentForm, validTo: event.target.value })} /></label><label className="checkbox-label"><input type="checkbox" checked={assignmentForm.primary} onChange={(event) => setAssignmentForm({ ...assignmentForm, primary: event.target.checked })} />设为主岗</label>{selectedAssignmentPosition?.authorizationScopeType === 'ASSIGNED_HOTELS' && <fieldset className="full-field assignment-hotel-picker"><legend>该员工负责的门店（至少选择一家）</legend>{selectableAssignmentHotels.map((hotel) => <label key={hotel.id}><input type="checkbox" checked={assignmentForm.responsibleHotelIds.includes(hotel.id)} onChange={(event) => setAssignmentForm({ ...assignmentForm, responsibleHotelIds: event.target.checked ? [...assignmentForm.responsibleHotelIds, hotel.id] : assignmentForm.responsibleHotelIds.filter((id) => id !== hotel.id) })} />{hotel.code ? `${hotel.code} · ` : ''}{hotel.name}</label>)}</fieldset>}</div>
            {assignmentEditInvalid && <div className="inline-error">请完整填写组织、岗位和有效期；“指定负责门店”岗位还需至少选择一家门店。</div>}
            <div className="assignment-editor-actions"><button type="button" className="secondary" disabled={saving} onClick={resetAssignmentDraft}>取消</button><button type="button" className="primary" disabled={saving || assignmentEditInvalid} onClick={() => void saveCurrentAssignment()}>{saving ? '保存中…' : '保存任职修改'}</button></div>
          </div>}
        </section>
        {!editingAssignmentId && <section className="employee-edit-section employee-new-assignment">
          <header><div><span className="panel-kicker">ADD ASSIGNMENT</span><h3>新增任职</h3></div><small>选填</small></header>
          <p>如只修改员工资料，可保持组织和岗位为空；需要新增任职时，请完整选择以下信息。</p>
          <div className="form-grid"><label>组织/门店<select value={assignmentForm.orgUnitId} onChange={(event) => setAssignmentForm({ ...assignmentForm, orgUnitId: event.target.value })}><option value="">暂不新增任职</option>{data.orgUnits.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>岗位<select value={assignmentForm.positionId} onChange={(event) => setAssignmentForm({ ...assignmentForm, positionId: event.target.value, responsibleHotelIds: [] })}><option value="">暂不新增任职</option>{data.positions.filter((item) => item.status === 'ACTIVE').map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>上级任职<select value={assignmentForm.managerAssignmentId} onChange={(event) => setAssignmentForm({ ...assignmentForm, managerAssignmentId: event.target.value })}><option value="">暂不设置</option>{data.employees.filter((item) => item.id !== editingId && item.assignmentId && item.employmentStatus === 'ACTIVE').map((item) => <option value={item.assignmentId} key={item.assignmentId}>{item.name} · {item.positionName}</option>)}</select></label><label>任职类型<select value={assignmentForm.assignmentType} onChange={(event) => setAssignmentForm({ ...assignmentForm, assignmentType: event.target.value })}><option value="PERMANENT">正式</option><option value="TEMPORARY">临时</option><option value="ACTING">代理</option></select></label><label>开始日期<input type="date" value={assignmentForm.validFrom} onChange={(event) => setAssignmentForm({ ...assignmentForm, validFrom: event.target.value })} /></label><label>结束日期（选填）<input type="date" min={assignmentForm.validFrom} value={assignmentForm.validTo} onChange={(event) => setAssignmentForm({ ...assignmentForm, validTo: event.target.value })} /></label><label className="checkbox-label"><input type="checkbox" checked={assignmentForm.primary} onChange={(event) => setAssignmentForm({ ...assignmentForm, primary: event.target.checked })} />设为主岗</label>{selectedAssignmentPosition?.authorizationScopeType === 'ASSIGNED_HOTELS' && <fieldset className="full-field assignment-hotel-picker"><legend>该员工负责的门店（至少选择一家）</legend>{selectableAssignmentHotels.map((hotel) => <label key={hotel.id}><input type="checkbox" checked={assignmentForm.responsibleHotelIds.includes(hotel.id)} onChange={(event) => setAssignmentForm({ ...assignmentForm, responsibleHotelIds: event.target.checked ? [...assignmentForm.responsibleHotelIds, hotel.id] : assignmentForm.responsibleHotelIds.filter((id) => id !== hotel.id) })} />{hotel.code ? `${hotel.code} · ` : ''}{hotel.name}</label>)}</fieldset>}</div>
          {assignmentDraftInvalid && <div className="inline-error">新增任职信息未填写完整；已停用员工不能新增任职，“指定负责门店”岗位还需至少选择一家门店。</div>}
        </section>}
      </>}
      <div className="inline-warning">{editingId ? editingAssignmentId ? '请先保存或取消当前任职修改，再保存员工资料。结束任职会立即回收对应岗位权限，但不会删除历史任务和证据。' : '保存会同时更新员工资料；如已填写新增任职，则一并创建岗位权限。停用员工会同步停用账号、结束有效任职和角色授权。' : '初始密码仅本次创建时使用，请通过安全渠道交给员工。'}</div>
    </Modal>}
    {modal === 'assignment-scope' && <Modal title={`配置负责门店 · ${assignmentForm.employeeName}`} onClose={closeModal} onSave={submit} saving={saving} saveDisabled={assignmentForm.responsibleHotelIds.length === 0}><fieldset className="assignment-hotel-picker"><legend>可访问门店（至少选择一家）</legend>{selectableAssignmentHotels.map((hotel) => <label key={hotel.id}><input type="checkbox" checked={assignmentForm.responsibleHotelIds.includes(hotel.id)} onChange={(event) => setAssignmentForm({ ...assignmentForm, responsibleHotelIds: event.target.checked ? [...assignmentForm.responsibleHotelIds, hotel.id] : assignmentForm.responsibleHotelIds.filter((id) => id !== hotel.id) })} />{hotel.code ? `${hotel.code} · ` : ''}{hotel.name}</label>)}</fieldset><div className="inline-warning">保存后，该任职只可访问勾选门店及其下级部门的数据；其他运营人员的门店范围不会受影响。</div></Modal>}
  </section>
}

export function WorkPackageCenter({ identity, permissions }: { identity: RoleContext; permissions: string[] }) {
  const canManage = permissions.includes('work-package.manage') || permissions.includes('*')
  const canPublish = permissions.includes('work-package.publish') || permissions.includes('*')
  const canAllocate = permissions.includes('work-package.allocate') || permissions.includes('*')
  const [packages, setPackages] = useState<PackageRow[]>([])
  const [orgs, setOrgs] = useState<OrgUnit[]>([])
  const [positions, setPositions] = useState<Position[]>([])
  const [forms, setForms] = useState<SelectRow[]>([])
  const [standards, setStandards] = useState<SelectRow[]>([])
  const [assignments, setAssignments] = useState<Employee[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [modal, setModal] = useState(false)
  const [saving, setSaving] = useState(false)
  const [detail, setDetail] = useState<Row>()
  const [detailAllocations, setDetailAllocations] = useState<AllocationRow[]>([])
  const [allocation, setAllocation] = useState({ assignmentId: '', targetOrgUnitId: '' })
  const [itemDrafts, setItemDrafts] = useState<WorkPackageItemDraft[]>([])
  const [versionTitle, setVersionTitle] = useState('')
  const [versionDescription, setVersionDescription] = useState('')
  const [draft, setDraft] = useState({ code: '', name: '', description: '', positionId: '', ownerOrgUnitId: '', scopeOrgUnitId: '', formVersionId: '', standardVersionId: '', itemCode: 'DAILY-01', itemName: '', itemType: 'SCHEDULED_RECORD', periodType: 'DAY', dueLocalTime: '18:00', reviewMode: 'NONE', completionStatementRequired: true, exceptionStatementRequired: false, nextActionRequired: false, attachmentRequired: false, maxAttachments: '10', allowedExtensions: 'jpg,jpeg,png,pdf,docx,xlsx' })
  const publishedForms = useMemo(() => forms.filter((item) => item.status === 'PUBLISHED' && item.versionId).map((item) => ({ id: item.versionId!, name: item.name, code: item.code })), [forms])
  const publishedStandards = useMemo(() => standards.filter((item) => item.status === 'PUBLISHED' && item.versionId).map((item) => ({ id: item.versionId!, name: item.name, code: item.code })), [standards])
  const itemIssues = useMemo(() => validateWorkPackageItems(itemDrafts), [itemDrafts])
  const reload = async () => {
    setLoading(true); setError(undefined)
    try {
      const [packageRaw, setup, formRaw, standardRaw] = await Promise.all([
        apiRequest<unknown>('/work-packages', identity), loadSetup(identity, false), apiRequest<unknown>('/work-data/forms', identity), apiRequest<unknown>('/standards', identity),
      ])
      setPackages(asList<Row>(packageRaw).map((row) => ({ id: text(row, 'id'), code: text(row, 'code'), name: text(row, 'name'), positionName: text(row, 'position_name', 'positionName'), lifecycleStatus: text(row, 'lifecycle_status', 'lifecycleStatus') || 'DRAFT', versionNo: Number(field(row, 'version_no', 'versionNo') ?? 0), latestVersionId: text(row, 'latest_version_id', 'latestVersionId') || undefined, ownerOrgName: text(row, 'owner_org_unit_name', 'ownerOrgName') || undefined })))
      setOrgs(setup.orgUnits); setPositions(setup.positions)
      setAssignments(setup.employees.filter((item) => item.assignmentId))
      setForms(asList<Row>(formRaw).map((row) => ({ id: text(row, 'id'), code: text(row, 'code'), name: text(row, 'name'), status: text(row, 'lifecycle_status', 'lifecycleStatus'), versionId: text(row, 'latest_version_id', 'latestVersionId') || undefined, positionId: text(row, 'position_id', 'positionId') || undefined })))
      setStandards(asList<Row>(standardRaw).map((row) => ({ id: text(row, 'id'), code: text(row, 'code'), name: text(row, 'name'), status: text(row, 'lifecycle_status', 'lifecycleStatus'), versionId: text(row, 'latest_version_id', 'latestVersionId') || undefined })))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '工作包数据加载失败') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key])
  const create = async () => {
    setSaving(true); setError(undefined)
    try {
      const definition = await apiRequest<Row>('/work-packages', identity, { method: 'POST', body: JSON.stringify({ code: draft.code, name: draft.name, description: draft.description, positionId: draft.positionId, ownerOrgUnitId: draft.ownerOrgUnitId || null }) })
      const packageId = text(definition, 'id')
      const version = await apiRequest<Row>(`/work-packages/${packageId}/versions`, identity, { method: 'POST', body: JSON.stringify({ title: draft.name, description: draft.description }) })
      const versionId = text(version, 'id')
      const responsibilities: Row[] = [{ participantType: 'EXECUTOR', resolverType: 'CURRENT_ASSIGNMENT', scopeStrategy: 'TARGET_ORG', escalationLevel: 0 }]
      if (draft.reviewMode !== 'NONE') responsibilities.push({ participantType: 'ACCEPTOR', resolverType: 'DIRECT_MANAGER_ASSIGNMENT', scopeStrategy: 'TARGET_ORG', escalationLevel: 0 })
      await apiRequest(`/work-packages/${packageId}/versions/${versionId}`, identity, { method: 'PUT', body: JSON.stringify({
        title: draft.name, description: draft.description,
        scopes: [{ scopeType: 'ORG_TREE', orgUnitId: draft.scopeOrgUnitId }],
        items: [{ itemCode: draft.itemCode, name: draft.itemName, description: draft.description, itemType: draft.itemType, formVersionId: draft.formVersionId, sortOrder: 1, required: true, periodType: draft.periodType, timezoneMode: 'HOTEL', dueLocalTime: draft.dueLocalTime, graceMinutes: 30, weekdays: [], holidayPolicy: 'INCLUDE', waiverAllowed: false, targetGranularity: 'TARGET_ORG', reviewMode: draft.reviewMode, submissionPolicy: { completionStatementRequired: draft.completionStatementRequired, exceptionStatementRequired: draft.exceptionStatementRequired, nextActionRequired: draft.nextActionRequired, attachmentRequired: draft.attachmentRequired, maxAttachments: Number(draft.maxAttachments), maxFileSizeBytes: 20 * 1024 * 1024, allowedExtensions: draft.allowedExtensions.split(',').map((value) => value.trim().toLowerCase()).filter(Boolean) }, standards: draft.standardVersionId ? [{ standardVersionId: draft.standardVersionId, usageType: 'EXECUTION', weight: 1 }] : [], responsibilities }],
      }) })
      setModal(false); await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '工作包创建失败') }
    finally { setSaving(false) }
  }
  const openDetail = async (item: PackageRow) => {
    setError(undefined)
    setAllocation({ assignmentId: '', targetOrgUnitId: '' })
    try {
      const [packageDetail, allocationRaw] = await Promise.all([
        apiRequest<Row>(`/work-packages/${item.id}`, identity),
        apiRequest<unknown>(`/work-packages/${item.id}/allocations`, identity),
      ])
      const configuration = packageDetail.latestVersion && typeof packageDetail.latestVersion === 'object' ? packageDetail.latestVersion as Row : {}
      const latestVersion = asList<Row>(packageDetail.versions)[0] ?? {}
      setDetail(packageDetail)
      setItemDrafts(asList<Row>(configuration.items).map(mapWorkPackageItem))
      setVersionTitle(text(latestVersion, 'title') || text(packageDetail, 'name'))
      setVersionDescription(text(latestVersion, 'description') || text(packageDetail, 'description'))
      setDetailAllocations(asList<Row>(allocationRaw).map((row) => ({
        id: text(row, 'id'), assignmentId: text(row, 'position_assignment_id', 'positionAssignmentId'),
        targetOrgName: text(row, 'target_org_unit_name', 'targetOrgUnitName', 'targetOrgName'),
        assigneeName: text(row, 'employee_name', 'employeeName', 'assigneeName'),
        status: text(row, 'status'), validFrom: text(row, 'valid_from', 'validFrom'),
      })))
    } catch (reason) { setError(reason instanceof Error ? reason.message : '详情加载失败') }
  }
  const versionPayload = (source: Row, title = versionTitle) => {
    const configuration = source.latestVersion && typeof source.latestVersion === 'object' ? source.latestVersion as Row : {}
    const scopes = asList<Row>(configuration.scopes).map((scope) => ({ scopeType: text(scope, 'scope_type', 'scopeType'), brandId: text(scope, 'brand_id', 'brandId') || null, orgUnitId: text(scope, 'org_unit_id', 'orgUnitId') || null, positionId: text(scope, 'position_id', 'positionId') || null }))
    return { title: title.trim() || text(source, 'name'), description: versionDescription.trim() || null, scopes, items: serializeWorkPackageItems(itemDrafts) }
  }
  const refreshDetail = async (id: string) => {
    const packageDetail = await apiRequest<Row>(`/work-packages/${id}`, identity)
    const configuration = packageDetail.latestVersion && typeof packageDetail.latestVersion === 'object' ? packageDetail.latestVersion as Row : {}
    const latestVersion = asList<Row>(packageDetail.versions)[0] ?? {}
    setDetail(packageDetail)
    setItemDrafts(asList<Row>(configuration.items).map(mapWorkPackageItem))
    setVersionTitle(text(latestVersion, 'title') || text(packageDetail, 'name'))
    setVersionDescription(text(latestVersion, 'description') || text(packageDetail, 'description'))
  }
  const createEditableVersion = async () => {
    if (!detail) return
    setSaving(true); setError(undefined)
    try {
      const packageId = text(detail, 'id')
      const newTitle = `${text(detail, 'name')}（新版本）`
      const created = await apiRequest<Row>(`/work-packages/${packageId}/versions`, identity, { method: 'POST', body: JSON.stringify({ title: newTitle, description: versionDescription || text(detail, 'description') || null }) })
      await apiRequest(`/work-packages/${packageId}/versions/${text(created, 'id')}`, identity, { method: 'PUT', body: JSON.stringify(versionPayload(detail, newTitle)) })
      await refreshDetail(packageId); await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '创建工作包新版本失败') }
    finally { setSaving(false) }
  }
  const saveDraftConfiguration = async () => {
    if (!detail) return
    const latest = asList<Row>(detail.versions)[0]
    if (!latest || text(latest, 'lifecycle_status', 'lifecycleStatus') !== 'DRAFT') return
    const issues = validateWorkPackageItems(itemDrafts)
    if (issues.length) { setError(issues[0]); return }
    setSaving(true); setError(undefined)
    try { const packageId = text(detail, 'id'); await apiRequest(`/work-packages/${packageId}/versions/${text(latest, 'id')}`, identity, { method: 'PUT', body: JSON.stringify(versionPayload(detail)) }); await refreshDetail(packageId); await reload() }
    catch (reason) { setError(reason instanceof Error ? reason.message : '保存工作包草稿失败') }
    finally { setSaving(false) }
  }
  const detailAction = async () => {
    if (!detail) return
    const id = text(detail, 'id'); const versions = asList<Row>(detail.versions); const latest = versions[0]
    if (!latest) return
    const lifecycle = text(latest, 'lifecycle_status', 'lifecycleStatus')
    if (lifecycle !== 'DRAFT' && !canAllocate) { setDetail(undefined); return }
    if (lifecycle === 'DRAFT' && !canPublish) { setDetail(undefined); return }
    setSaving(true); setError(undefined)
    try {
      if (lifecycle === 'DRAFT') {
        const issues = validateWorkPackageItems(itemDrafts)
        if (issues.length) throw new Error(issues[0])
        await apiRequest(`/work-packages/${id}/versions/${text(latest, 'id')}`, identity, { method: 'PUT', body: JSON.stringify(versionPayload(detail)) })
        await apiRequest(`/work-packages/${id}/versions/${text(latest, 'id')}/validate`, identity, { method: 'POST' })
        await apiRequest(`/work-packages/${id}/versions/${text(latest, 'id')}/publish`, identity, { method: 'POST', body: JSON.stringify({ effectiveFrom: new Date().toISOString(), effectiveTo: null }) })
      } else {
        const selected = assignments.find((item) => item.assignmentId === allocation.assignmentId)
        if (!selected?.assignmentId || !allocation.targetOrgUnitId) throw new Error('请选择负责人任职与目标门店。')
        await apiRequest(`/work-packages/${id}/allocations`, identity, { method: 'POST', body: JSON.stringify({ workPackageVersionId: text(latest, 'id'), positionAssignmentId: selected.assignmentId, targetOrgUnitId: allocation.targetOrgUnitId, validFrom: new Date().toISOString().slice(0, 10), validTo: null, allocationSource: 'MANUAL' }) })
        const latestConfiguration = detail.latestVersion && typeof detail.latestVersion === 'object' ? detail.latestVersion as Row : {}
        const firstItem = asList<Row>(latestConfiguration.items)[0]
        const periodType = text(firstItem ?? {}, 'period_type', 'periodType') || 'DAY'
        await apiRequest('/work-expectations/actions/generate', identity, { method: 'POST', body: JSON.stringify({ positionAssignmentId: selected.assignmentId, targetOrgUnitId: allocation.targetOrgUnitId, businessDate: new Date().toISOString().slice(0, 10), periodType, dutyPeriodId: null }) })
      }
      setDetail(undefined); await reload()
    } catch (reason) { setError(reason instanceof Error ? reason.message : '工作包操作失败') }
    finally { setSaving(false) }
  }
  return <section className="page-section configuration-page"><header className="page-title"><div><span className="eyebrow">WORK PACKAGE CENTER</span><h1>工作包中心</h1><p>创建岗位工作包草稿，绑定表单、标准和门店范围，校验后发布。</p></div><div className="page-actions"><span className="source-flag api">实时 API</span>{canManage && <button className="primary" onClick={() => setModal(true)}>＋ 新建工作包</button>}</div></header>
    {error && <div className="inline-error page-error">{error}</div>}
    {loading ? <div className="state-card"><div className="spinner" /><strong>正在读取工作包</strong></div> : !packages.length ? <div className="state-card"><b>◇</b><strong>当前范围暂无工作包</strong><span>{canManage ? '点击右上角创建第一个工作包。' : '请联系集团管理员发布并分配工作包。'}</span></div> : <div className="card-grid">{packages.map((item) => <article className="package-card" key={item.id}><div className="card-top"><span>{item.code}</span><span className={`status-pill ${item.lifecycleStatus.toLowerCase()}`}>{item.lifecycleStatus === 'PUBLISHED' ? '已发布' : item.lifecycleStatus === 'DRAFT' ? '草稿' : item.lifecycleStatus}</span></div><h2>{item.name}</h2><p>{item.positionName} · {item.ownerOrgName || '集团范围'}</p><div className="package-stats"><span><strong>V{item.versionNo || '—'}</strong>当前版本</span><span><strong>{item.lifecycleStatus === 'PUBLISHED' ? '生效' : '待发布'}</strong>版本状态</span></div><button className="secondary" onClick={() => openDetail(item)}>{canAllocate ? '查看版本与下发' : canPublish ? '查看版本与发布' : '查看工作包详情'}</button></article>)}</div>}
    {modal && <Modal title="创建工作包与首个工作项" onClose={() => setModal(false)} onSave={create} saving={saving} saveLabel="创建草稿"><div className="form-grid"><label>工作包编码<input value={draft.code} onChange={(event) => setDraft({ ...draft, code: event.target.value })} placeholder="WP-FRONT-DAILY" /></label><label>工作包名称<input value={draft.name} onChange={(event) => setDraft({ ...draft, name: event.target.value })} /></label><label>目标岗位<select value={draft.positionId} onChange={(event) => setDraft({ ...draft, positionId: event.target.value })}><option value="">请选择</option>{positions.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>归属组织<select value={draft.ownerOrgUnitId} onChange={(event) => setDraft({ ...draft, ownerOrgUnitId: event.target.value })}><option value="">集团统一</option>{orgs.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>下发范围<select value={draft.scopeOrgUnitId} onChange={(event) => setDraft({ ...draft, scopeOrgUnitId: event.target.value })}><option value="">请选择门店/组织树</option>{orgs.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label><label>工作表单<select value={draft.formVersionId} onChange={(event) => setDraft({ ...draft, formVersionId: event.target.value })}><option value="">请选择已发布表单</option>{forms.filter((item) => item.status === 'PUBLISHED' && item.versionId).map((item) => <option value={item.versionId} key={item.versionId}>{item.name}</option>)}</select></label><label>工作项编码<input value={draft.itemCode} onChange={(event) => setDraft({ ...draft, itemCode: event.target.value })} /></label><label>工作项名称<input value={draft.itemName} onChange={(event) => setDraft({ ...draft, itemName: event.target.value })} placeholder="例如 完成前台交接记录" /></label><label>工作类型<select value={draft.itemType} onChange={(event) => setDraft({ ...draft, itemType: event.target.value })}><option value="SCHEDULED_RECORD">定时记录</option><option value="INSPECTION">巡检</option><option value="METRIC_REVIEW">指标复盘</option></select></label><label>执行周期<select value={draft.periodType} onChange={(event) => setDraft({ ...draft, periodType: event.target.value })}><option value="DAY">每日</option><option value="WEEK">每周</option><option value="SHIFT">每班次</option></select></label><label>截止时间<input type="time" value={draft.dueLocalTime} onChange={(event) => setDraft({ ...draft, dueLocalTime: event.target.value })} /></label><label>验收方式<select value={draft.reviewMode} onChange={(event) => setDraft({ ...draft, reviewMode: event.target.value })}><option value="NONE">提交即完成</option><option value="MANUAL">直属上级人工验收</option><option value="STANDARD_EVALUATION">按标准评价验收</option></select></label><label>关联标准<select value={draft.standardVersionId} onChange={(event) => setDraft({ ...draft, standardVersionId: event.target.value })}><option value="">暂不关联</option>{standards.filter((item) => item.status === 'PUBLISHED' && item.versionId).map((item) => <option value={item.versionId} key={item.versionId}>{item.name}</option>)}</select></label><label>最多附件数<input type="number" min="0" max="200" value={draft.maxAttachments} onChange={(event) => setDraft({ ...draft, maxAttachments: event.target.value })} /></label><label className="full-field">允许附件类型<input value={draft.allowedExtensions} onChange={(event) => setDraft({ ...draft, allowedExtensions: event.target.value })} /></label><label className="checkbox-label"><input type="checkbox" checked={draft.completionStatementRequired} onChange={(event) => setDraft({ ...draft, completionStatementRequired: event.target.checked })} />必须填写完成情况</label><label className="checkbox-label"><input type="checkbox" checked={draft.attachmentRequired} onChange={(event) => setDraft({ ...draft, attachmentRequired: event.target.checked })} />必须上传附件证据</label><label className="checkbox-label"><input type="checkbox" checked={draft.exceptionStatementRequired} onChange={(event) => setDraft({ ...draft, exceptionStatementRequired: event.target.checked })} />必须填写异常事项</label><label className="checkbox-label"><input type="checkbox" checked={draft.nextActionRequired} onChange={(event) => setDraft({ ...draft, nextActionRequired: event.target.checked })} />必须填写下一步行动</label><label className="full-field">说明<textarea rows={3} value={draft.description} onChange={(event) => setDraft({ ...draft, description: event.target.value })} /></label></div></Modal>}
    {detail && (() => {
      const latest = asList<Row>(detail.versions)[0] ?? {}
      const lifecycle = text(latest, 'lifecycle_status', 'lifecycleStatus')
      const matchingAssignments = assignments.filter((item) => item.positionId === text(detail, 'position_id', 'positionId'))
      return <Modal wide title={`${text(detail, 'name')} · 版本与下发`} onClose={() => setDetail(undefined)} onSave={detailAction} saving={saving} saveDisabled={lifecycle === 'DRAFT' && canPublish && itemIssues.length > 0} saveLabel={lifecycle === 'DRAFT' && canPublish ? '保存、校验并发布' : lifecycle === 'PUBLISHED' && canAllocate ? '下发并生成今日工作' : '关闭'}>
        <div className="version-summary"><strong>{text(detail, 'code')}</strong><p>{text(detail, 'description') || '暂无说明'}</p>{asList<Row>(detail.versions).map((version) => <div key={text(version, 'id')}><span>V{text(version, 'version_no', 'versionNo')}</span><b>{text(version, 'lifecycle_status', 'lifecycleStatus')}</b><small>{text(version, 'title')}</small></div>)}</div>
        {lifecycle === 'PUBLISHED' && canManage && <div className="allocation-box"><h3>CEO 标准工作模板维护</h3><p>创建新草稿版本后修改，不覆盖已发布版本和历史工作记录。</p><button className="secondary" disabled={saving} onClick={() => void createEditableVersion()}>{saving ? '创建中…' : '创建可编辑新版本'}</button></div>}
        {lifecycle === 'DRAFT' && canManage && <div className="allocation-box work-template-configuration"><header><div><h3>所有岗位通用工作模板配置</h3><p>可增删、复制、启停和排序工作项，并配置时间、提醒、证据链与日报规则。</p></div><button className="secondary" disabled={saving || itemIssues.length > 0} onClick={() => void saveDraftConfiguration()}>{saving ? '保存中…' : '保存全部草稿'}</button></header><div className="form-grid version-metadata-grid"><label>版本标题<input value={versionTitle} onChange={(event) => setVersionTitle(event.target.value)} /></label><label>版本说明<input value={versionDescription} onChange={(event) => setVersionDescription(event.target.value)} /></label></div><WorkPackageItemEditor items={itemDrafts} setItems={setItemDrafts} forms={publishedForms} standards={publishedStandards} issues={itemIssues} /></div>}
        {detailAllocations.length > 0 && <div className="allocation-box"><h3>当前有效下发</h3><div className="version-summary">{detailAllocations.filter((item) => item.status === 'ACTIVE').map((item) => <div key={item.id}><span>{item.assigneeName || '已分配员工'}</span><b>{item.status === 'ACTIVE' ? '生效' : item.status}</b><small>{item.targetOrgName || '任职组织'} · {item.validFrom}</small></div>)}</div></div>}
        {lifecycle === 'PUBLISHED' && canAllocate && <div className="allocation-box"><h3>下发到岗位任职</h3>{matchingAssignments.length === 0 && <div className="inline-warning">当前权限范围内没有该岗位的有效任职。请先到“组织与权限”创建员工并分配对应岗位。</div>}<div className="form-grid"><label>负责人任职<select value={allocation.assignmentId} onChange={(event) => { const assignmentId = event.target.value; const selected = matchingAssignments.find((item) => item.assignmentId === assignmentId); setAllocation({ assignmentId, targetOrgUnitId: selected?.orgUnitId ?? '' }) }}><option value="">请选择同岗位员工</option>{matchingAssignments.map((item) => <option value={item.assignmentId} key={item.assignmentId}>{item.name} · {item.positionName} · {item.orgUnitName}</option>)}</select></label><label>目标门店/部门<select value={allocation.targetOrgUnitId} onChange={(event) => setAllocation({ ...allocation, targetOrgUnitId: event.target.value })}><option value="">请选择</option>{orgs.map((item) => <option value={item.id} key={item.id}>{item.name}</option>)}</select></label></div><small>选择员工后默认使用其任职组织；下发成功会立即生成今天的真实工作。</small></div>}
        {lifecycle === 'DRAFT' && !canPublish && <div className="inline-warning">当前账号可查看草稿，但没有发布权限。</div>}
      </Modal>
    })()}
  </section>
}
