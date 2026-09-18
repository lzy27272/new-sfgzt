import { useEffect, useMemo, useState } from 'react'
import { apiRequest } from '../../api/client'
import type { RoleContext } from '../../domain'
import { PermissionChecklist, PositionAccessPreview, PositionAccessSettings } from './PositionAccessEditor'
import { isModuleOption, type AuthorizationScope, type PermissionOption, type ProfileContext } from './positionAccessModel'

type HotelOption = { id: string; code: string; name: string }
type ProfileSummary = {
  profileId?: string
  draftVersion?: number
  draftRowVersion?: number
  publishedVersion?: number
  status: string
  draftDirty?: boolean
  permissionCodes: string[]
  authorizationScopeType?: AuthorizationScope
  wecomSelfSelectable?: boolean
}
export type PositionSummary = {
  id: string
  name: string
  status: string
  rowVersion: number
  appliesToAllHotels: boolean
  applicableHotels: HotelOption[]
  activeAssignmentCount: number
  profile: ProfileSummary
}

type PositionDraft = {
  name: string
  appliesToAllHotels: boolean
  applicableHotelIds: string[]
  copyFromPositionId: string
  permissionCodes: string[]
  authorizationScopeType: AuthorizationScope
  wecomSelfSelectable: boolean
}

const emptyDraft: PositionDraft = {
  name: '', appliesToAllHotels: true, applicableHotelIds: [], copyFromPositionId: '', permissionCodes: [],
  authorizationScopeType: 'SELF', wecomSelfSelectable: false,
}

function profileVersionLabel(profile: ProfileSummary) {
  if (!profile.publishedVersion) return `草稿 V${profile.draftVersion ?? 1}`
  if (profile.draftDirty) return `已发布 V${profile.publishedVersion} · 草稿 V${profile.draftVersion ?? profile.publishedVersion + 1}`
  return `已发布 V${profile.publishedVersion}`
}

function asPositionList(payload: unknown): PositionSummary[] {
  if (Array.isArray(payload)) return payload as PositionSummary[]
  if (payload && typeof payload === 'object') {
    const value = payload as Record<string, unknown>
    for (const key of ['items','data','content']) if (Array.isArray(value[key])) return value[key] as PositionSummary[]
  }
  return []
}

function PositionModal({ title, draft, hotels, positions, options, context, saving, configureProfile, onChange, onClose, onSave }: {
  title: string
  draft: PositionDraft
  hotels: HotelOption[]
  positions: PositionSummary[]
  options: PermissionOption[]
  context?: ProfileContext
  saving: boolean
  configureProfile: boolean
  onChange: (next: PositionDraft) => void
  onClose: () => void
  onSave: () => void
}) {
  const moduleOptions = useMemo(() => options.filter(isModuleOption), [options])
  const actionOptions = useMemo(() => options.filter((option) => !isModuleOption(option)), [options])
  const togglePermission = (code: string, checked: boolean) => onChange({ ...draft, permissionCodes: checked ? [...new Set([...draft.permissionCodes, code])] : draft.permissionCodes.filter((item) => item !== code) })
  const copyProfile = (positionId: string) => {
    const source = positions.find((position) => position.id === positionId)
    const configurableCodes = new Set(options.filter((option) => option.delegable).map((option) => option.permissionCode))
    onChange({
      ...draft,
      copyFromPositionId: positionId,
      permissionCodes: (source?.profile.permissionCodes ?? draft.permissionCodes).filter((code) => configurableCodes.has(code)),
      authorizationScopeType: source?.profile.authorizationScopeType ?? draft.authorizationScopeType,
      wecomSelfSelectable: source?.profile.wecomSelfSelectable ?? draft.wecomSelfSelectable,
    })
  }
  return <div className="modal-backdrop"><section className="modal position-editor-modal" role="dialog" aria-modal="true">
    <header><div><span className="panel-kicker">POSITION SETTINGS</span><h2>{title}</h2></div><button className="close" onClick={onClose}>×</button></header>
    <div className="position-editor-body">
      <div className="form-grid">
        <label>岗位名称<input autoFocus value={draft.name} onChange={(event) => onChange({ ...draft, name: event.target.value })} placeholder="例如 前厅接待" /></label>
        {configureProfile && <label>功能方案来源<select value={draft.copyFromPositionId} onChange={(event) => copyProfile(event.target.value)}><option value="">基础空白方案</option>{positions.map((position) => <option key={position.id} value={position.id}>复制“{position.name}”</option>)}</select></label>}
        <label className="checkbox-label"><input type="checkbox" checked={draft.appliesToAllHotels} onChange={(event) => onChange({ ...draft, appliesToAllHotels: event.target.checked, applicableHotelIds: event.target.checked ? [] : draft.applicableHotelIds })} />适用于全部门店</label>

      </div>
      {configureProfile && <PositionAccessSettings context={context} scope={draft.authorizationScopeType} selfSelectable={draft.wecomSelfSelectable} disabled={saving}
        onScopeChange={(scope) => onChange({ ...draft, authorizationScopeType: scope })} onSelfSelectableChange={(value) => onChange({ ...draft, wecomSelfSelectable: value })} />}
      {!draft.appliesToAllHotels && <fieldset className="position-hotel-picker"><legend>适用门店</legend>{hotels.map((hotel) => <label key={hotel.id}><input type="checkbox" checked={draft.applicableHotelIds.includes(hotel.id)} onChange={(event) => onChange({ ...draft, applicableHotelIds: event.target.checked ? [...draft.applicableHotelIds, hotel.id] : draft.applicableHotelIds.filter((id) => id !== hotel.id) })} />{hotel.code ? `${hotel.code} · ` : ''}{hotel.name}</label>)}</fieldset>}
      {configureProfile ? <>
        <section className="position-permission-picker"><header><h3>菜单入口</h3><small>只控制显示位置，不授予页面内操作权限。</small></header>
          <PermissionChecklist options={moduleOptions} selected={draft.permissionCodes} disabled={saving || !context} onToggle={togglePermission} />
        </section>
        <section className="position-permission-picker"><header><h3>操作权限</h3><small>按业务中心配置；系统保留权限不可复制下放。</small></header>
          <PermissionChecklist options={actionOptions} selected={draft.permissionCodes} disabled={saving || !context} onToggle={togglePermission} />
        </section>
      </> : <div className="inline-warning">岗位名称与适用门店在此维护；模块与页面内权限请在岗位详情中保存草稿并单独发布。</div>}
    </div>
    <footer><button className="secondary" onClick={onClose}>取消</button><button className="primary" disabled={saving || (configureProfile && !context) || !draft.name.trim() || (!draft.appliesToAllHotels && draft.applicableHotelIds.length === 0)} onClick={onSave}>{saving ? '保存中…' : '保存岗位'}</button></footer>
  </section></div>
}

export function PositionAdministration({ identity, canManage, canPublish, hotels, positionId }: { identity: RoleContext; canManage: boolean; canPublish: boolean; hotels: HotelOption[]; positionId?: string }) {
  const [positions, setPositions] = useState<PositionSummary[]>([])
  const [deleted, setDeleted] = useState<PositionSummary[]>([])
  const [options, setOptions] = useState<PermissionOption[]>([])
  const [selectedOptions, setSelectedOptions] = useState<PermissionOption[]>([])
  const [createContext, setCreateContext] = useState<ProfileContext>()
  const [profileContext, setProfileContext] = useState<{ positionId: string; data: ProfileContext }>()
  const [contextLoadFailed, setContextLoadFailed] = useState(false)
  const [contextRefresh, setContextRefresh] = useState(0)
  const [positionSearch, setPositionSearch] = useState('')
  const [permissionSearch, setPermissionSearch] = useState('')
  const [permissionTab, setPermissionTab] = useState<'modules' | 'actions'>('modules')
  const [view, setView] = useState<'active' | 'deleted'>('active')
  const [selectedId, setSelectedId] = useState<string>()
  const [draftPermissions, setDraftPermissions] = useState<string[]>([])
  const [draftScope, setDraftScope] = useState<PositionDraft['authorizationScopeType']>('SELF')
  const [draftSelfSelectable, setDraftSelfSelectable] = useState(false)
  const [editor, setEditor] = useState<{ mode: 'create' | 'edit'; draft: PositionDraft; position?: PositionSummary }>()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string>()
  const [notice, setNotice] = useState<string>()
  const moduleOptions = useMemo(() => selectedOptions.filter(isModuleOption), [selectedOptions])
  const actionOptions = useMemo(() => selectedOptions.filter((option) => !isModuleOption(option)), [selectedOptions])

  const reload = async () => {
    setLoading(true); setError(undefined)
    try {
      const [activeRaw, deletedRaw, optionRaw, contextRaw] = await Promise.all([
        apiRequest<unknown>('/org/positions', identity),
        canManage ? apiRequest<unknown>('/org/positions/deleted', identity) : Promise.resolve([]),
        apiRequest<unknown>('/org/positions/function-options', identity),
        apiRequest<ProfileContext>('/org/positions/profile-context', identity),
      ])
      const active = asPositionList(activeRaw)
      setPositions(active); setDeleted(asPositionList(deletedRaw))
      setCreateContext(contextRaw)
      setOptions(Array.isArray(optionRaw) ? optionRaw as PermissionOption[] : ((optionRaw as { items?: PermissionOption[] })?.items ?? []))
      setSelectedId((current) => active.some((item) => item.id === current) ? current : active[0]?.id)
    } catch (reason) { setError(reason instanceof Error ? reason.message : '岗位配置加载失败') }
    finally { setLoading(false) }
  }
  useEffect(() => { void reload() }, [identity.key, canManage])
  useEffect(() => {
    if (!positionId) return
    if (positions.some((position) => position.id === positionId)) {
      setView('active'); setSelectedId(positionId)
    } else if (deleted.some((position) => position.id === positionId)) {
      setView('deleted'); setSelectedId(positionId)
    }
  }, [positionId, positions, deleted])
  const source = (view === 'active' ? positions : deleted)
  const selected = source.find((position) => position.id === selectedId) ?? source[0]
  const matchingPositions = source.filter((position) => position.name.toLocaleLowerCase().includes(positionSearch.trim().toLocaleLowerCase()))
  const selectedContext = profileContext?.positionId === selected?.id ? profileContext.data : undefined
  const configurationReady = Boolean(selectedContext)
  const selfSelectionInvalid = Boolean(selectedContext?.selfSelectionBlockedReason && draftSelfSelectable)
  const togglePermission = (code: string, checked: boolean) => setDraftPermissions((current) => checked ? [...new Set([...current, code])] : current.filter((item) => item !== code))
  useEffect(() => {
    if (!selected?.id || view !== 'active') { setSelectedOptions([]); setProfileContext(undefined); return }
    let active = true
    setContextLoadFailed(false)
    setSelectedOptions([])
    setProfileContext(undefined)
    Promise.all([
      apiRequest<unknown>(`/org/positions/function-options?positionId=${encodeURIComponent(selected.id)}`, identity),
      apiRequest<ProfileContext>(`/org/positions/profile-context?positionId=${encodeURIComponent(selected.id)}`, identity),
    ])
      .then(([payload, context]) => {
        if (!active) return
        setSelectedOptions(Array.isArray(payload) ? payload as PermissionOption[] : ((payload as { items?: PermissionOption[] })?.items ?? []))
        setProfileContext({ positionId: selected.id, data: context })
      })
      .catch((reason) => { if (active) { setContextLoadFailed(true); setError(reason instanceof Error ? reason.message : '岗位可配置权限加载失败') } })
    return () => { active = false }
  }, [identity.key, selected?.id, selected?.rowVersion, view, contextRefresh])
  useEffect(() => {
    setDraftPermissions(selected?.profile.permissionCodes ?? [])
    setDraftScope(selected?.profile.authorizationScopeType ?? 'SELF')
    setDraftSelfSelectable(selected?.profile.wecomSelfSelectable ?? false)
  }, [selected?.id, selected?.profile.draftVersion, selected?.profile.draftRowVersion])

  const run = async (operation: () => Promise<unknown>, success: string) => {
    setSaving(true); setError(undefined); setNotice(undefined)
    try { await operation(); setNotice(success); await reload(); return true }
    catch (reason) { setError(reason instanceof Error ? reason.message : '操作失败'); return false }
    finally { setSaving(false) }
  }
  const createOrEdit = async () => {
    if (!editor) return
    const body = {
      name: editor.draft.name.trim(), appliesToAllHotels: editor.draft.appliesToAllHotels,
      applicableHotelIds: editor.draft.applicableHotelIds,
    }
    const currentHotelIds = editor.position?.applicableHotels.map((hotel) => hotel.id).sort() ?? []
    const requestedHotelIds = [...body.applicableHotelIds].sort()
    const applicabilityChanged = editor.mode === 'edit' && (
      editor.position!.appliesToAllHotels !== body.appliesToAllHotels
      || (!body.appliesToAllHotels && currentHotelIds.join('|') !== requestedHotelIds.join('|'))
    )
    if (editor.mode === 'edit' && applicabilityChanged) {
      type ApplicabilityImpact = {
        rowVersion: number
        activeAssignmentCount?: number
        affectedEmployeeCount?: number
        affectedActiveBindingCount?: number
        affectedRoleGrantCount?: number
        removedHotels?: HotelOption[]
      }
      let impact: ApplicabilityImpact
      setSaving(true); setError(undefined); setNotice(undefined)
      try {
        impact = await apiRequest<ApplicabilityImpact>(`/org/positions/${editor.position!.id}/impact-preview`, identity, {
          method: 'POST',
          body: JSON.stringify({
            operation: 'UPDATE_APPLICABILITY',
            appliesToAllHotels: body.appliesToAllHotels,
            applicableHotelIds: body.applicableHotelIds,
          }),
        })
      } catch (reason) {
        setError(reason instanceof Error ? `无法取得适用门店影响预览：${reason.message}` : '无法取得适用门店影响预览，已阻止本次修改')
        return
      } finally {
        setSaving(false)
      }
      if (impact.rowVersion !== editor.position!.rowVersion) {
        setError('岗位信息已被其他管理员修改，请刷新后重新操作')
        return
      }
      if (impact.removedHotels?.length) {
        const hotelNames = impact.removedHotels.map((hotel) => hotel.code ? `${hotel.code} · ${hotel.name}` : hotel.name).join('、')
        if (!window.confirm(`确认缩小“${editor.position!.name}”的适用门店？\n\n移除：${hotelNames}\n将暂停 ${impact.activeAssignmentCount ?? 0} 个有效任职、${impact.affectedRoleGrantCount ?? 0} 个岗位来源权限、${impact.affectedActiveBindingCount ?? 0} 个企业微信绑定，影响 ${impact.affectedEmployeeCount ?? 0} 名员工；群推送开关不变。`)) return
      }
    }
    let createdId: string | undefined
    const done = editor.mode === 'create'
      ? await run(async () => { const created = await apiRequest<PositionSummary>('/org/positions', identity, { method: 'POST', body: JSON.stringify({ ...body, copyFromPositionId: editor.draft.copyFromPositionId || null, permissionCodes: editor.draft.permissionCodes, authorizationScopeType: editor.draft.authorizationScopeType, wecomSelfSelectable: editor.draft.wecomSelfSelectable }) }); createdId = created.id }, '岗位已创建，功能方案为草稿；请检查后发布')
      : await run(() => apiRequest(`/org/positions/${editor.position!.id}`, identity, { method: 'PUT', body: JSON.stringify({ ...body, expectedVersion: editor.position!.rowVersion }) }), '岗位信息已更新')
    if (done) { setEditor(undefined); if (createdId) setSelectedId(createdId) }
  }
  const saveProfile = () => selected && configurationReady && !selfSelectionInvalid && run(() => apiRequest(`/org/positions/${selected.id}/profile/draft`, identity, { method: 'PUT', body: JSON.stringify({ expectedProfileVersion: selected.profile.draftRowVersion ?? 0, permissionCodes: draftPermissions, authorizationScopeType: draftScope, wecomSelfSelectable: draftSelfSelectable }) }), '岗位功能草稿已保存')
  const publishProfile = async () => {
    if (!selected || !configurationReady || selfSelectionInvalid) return
    type PublishImpact = { affectedEmployeeCount?: number; addedPermissionCodes?: string[]; removedPermissionCodes?: string[]; blockedPermissionCodes?: string[] }
    setSaving(true); setError(undefined); setNotice(undefined)
    let stage = '保存岗位功能草稿'
    try {
      let expectedProfileVersion = selected.profile.draftRowVersion ?? 0
      if (canManage) {
        const saved = await apiRequest<ProfileSummary>(`/org/positions/${selected.id}/profile/draft`, identity, { method: 'PUT', body: JSON.stringify({ expectedProfileVersion, permissionCodes: draftPermissions, authorizationScopeType: draftScope, wecomSelfSelectable: draftSelfSelectable }) })
        expectedProfileVersion = saved.draftRowVersion ?? expectedProfileVersion + 1
      }
      stage = '获取发布影响预览'
      const impact = await apiRequest<PublishImpact>(`/org/positions/${selected.id}/impact-preview`, identity, { method: 'POST', body: JSON.stringify({ operation: 'PUBLISH_PROFILE', permissionCodes: draftPermissions }) })
      if (impact.blockedPermissionCodes?.length) throw new Error(`包含受保护权限，不能发布：${impact.blockedPermissionCodes.join('、')}`)
      const assignedHotelNotice = draftScope === 'ASSIGNED_HOTELS' ? '\n采用此范围后，每个任职必须单独配置负责门店；未配置时默认无门店数据权限。' : ''
      if (!window.confirm(`确认发布“${selected.name}”功能方案？\n\n预计影响 ${impact.affectedEmployeeCount ?? selected.activeAssignmentCount} 名在职员工；权限将在重新读取身份后生效。${assignedHotelNotice}`)) {
        setNotice(canManage ? '当前修改已保存为草稿，尚未发布' : undefined)
        await reload()
        return
      }
      stage = '发布岗位功能方案'
      await apiRequest(`/org/positions/${selected.id}/profile/publish`, identity, { method: 'POST', body: JSON.stringify({ expectedProfileVersion, expectedPositionVersion: selected.rowVersion }) })
      setNotice('岗位功能方案已发布')
      await reload()
    } catch (reason) {
      setError(reason instanceof Error ? `${stage}失败：${reason.message}` : `${stage}失败，已阻止本次发布`)
    } finally {
      setSaving(false)
    }
  }
  const deletePosition = async (position: PositionSummary) => {
    let impact: { rowVersion?: number; affectedEmployeeCount?: number; activeAssignmentCount?: number; affectedActiveBindingCount?: number; affectedRoleGrantCount?: number }
    setSaving(true); setError(undefined); setNotice(undefined)
    try {
      impact = await apiRequest<{ rowVersion?: number; affectedEmployeeCount?: number; activeAssignmentCount?: number; affectedActiveBindingCount?: number; affectedRoleGrantCount?: number }>(`/org/positions/${position.id}/impact-preview`, identity, { method: 'POST', body: JSON.stringify({ operation: 'DELETE' }) })
    } catch (reason) {
      setError(reason instanceof Error ? `无法取得删除影响预览：${reason.message}` : '无法取得删除影响预览，已阻止本次删除')
      return
    } finally {
      setSaving(false)
    }
    if (impact.rowVersion !== undefined && impact.rowVersion !== position.rowVersion) {
      setError('岗位信息已被其他管理员修改，请刷新后重新操作')
      return
    }
    if (!window.confirm(`确认删除“${position.name}”并移入“已删除”？\n\n将暂停 ${impact.activeAssignmentCount ?? position.activeAssignmentCount} 个有效任职、${impact.affectedRoleGrantCount ?? 0} 个岗位来源权限、${impact.affectedActiveBindingCount ?? 0} 个企业微信绑定，影响 ${impact.affectedEmployeeCount ?? 0} 名员工；历史任务和日报保留。`)) return
    if (await run(() => apiRequest(`/org/positions/${position.id}?expectedVersion=${position.rowVersion}`, identity, { method: 'DELETE' }), `“${position.name}”已移入“已删除”`)) setView('deleted')
  }
  const restorePosition = async (position: PositionSummary) => {
    const restoreAssignments = window.confirm(`恢复“${position.name}”时，是否同时尝试恢复原有效任职？\n\n选择“取消”仍可继续恢复岗位，但不恢复任职。`)
    if (!window.confirm(`确认恢复岗位“${position.name}”？${restoreAssignments ? '\n系统会逐项校验并恢复仍有效的原任职。' : '\n原任职继续保持暂停。'}`)) return
    if (await run(() => apiRequest(`/org/positions/${position.id}/restore`, identity, { method: 'POST', body: JSON.stringify({ expectedVersion: position.rowVersion, restoreAssignments }) }), `“${position.name}”已恢复`)) setView('active')
  }
  const permanentlyDelete = async (position: PositionSummary) => {
    const confirmation = window.prompt(`永久删除后不能找回；历史记录只保留“${position.name}（已删除）”。\n\n请输入岗位名称确认：`)
    if (confirmation !== position.name) return
    await run(() => apiRequest(`/org/positions/${position.id}/permanent?expectedVersion=${position.rowVersion}`, identity, { method: 'DELETE' }), `“${position.name}”已永久删除`)
  }

  if (loading) return <div className="state-card"><div className="spinner"/><strong>正在读取岗位与功能方案</strong></div>
  return <>
    {notice && <div className="inline-success page-success">{notice}</div>}{error && <div className="inline-error page-error">{error}</div>}
    <article className="panel position-administration">
      <header><div><span className="panel-kicker">POSITION ACCESS</span><h2>岗位与功能配置</h2></div>{canManage && <div className="panel-actions"><button className="secondary" onClick={() => setView(view === 'active' ? 'deleted' : 'active')}>{view === 'active' ? `已删除（${deleted.length}）` : '返回岗位列表'}</button>{view === 'active' && <button className="primary" disabled={!createContext || saving} onClick={() => setEditor({ mode: 'create', draft: emptyDraft })}>＋ 新建岗位</button>}</div>}</header>
      <div className="position-admin-layout">
        <aside className="position-list"><label>搜索岗位<input placeholder="输入岗位名称" value={positionSearch} onChange={(event) => setPositionSearch(event.target.value)} /></label>
          {matchingPositions.map((position) => <button disabled={saving} className={selected?.id === position.id ? 'active' : ''} key={position.id} onClick={() => setSelectedId(position.id)}>
            <span><strong>{position.name}</strong><small>{position.activeAssignmentCount} 个有效任职</small><b>{profileVersionLabel(position.profile)}</b></span>
          </button>)}
          {!matchingPositions.length && <div className="position-empty">{positionSearch ? '没有匹配的岗位' : '暂无岗位'}</div>}
        </aside>
        {selected ? <section className="position-profile-panel"><header><div><h3>{selected.name} · {view === 'active' ? '功能方案' : '已删除岗位'}</h3><p>{selected.appliesToAllHotels ? '集团标准，适用于全部门店' : `适用于 ${selected.applicableHotels.length} 家门店`}</p></div><div className="panel-actions">{view === 'active' && canPublish && <button className="primary" disabled={saving || !configurationReady || selfSelectionInvalid} onClick={() => void publishProfile()}>{saving ? '处理中…' : '发布当前草稿'}</button>}{canManage && (view === 'active' ? <><button className="secondary" onClick={() => setEditor({ mode: 'edit', position: selected, draft: { name: selected.name, appliesToAllHotels: selected.appliesToAllHotels, applicableHotelIds: selected.applicableHotels.map((hotel) => hotel.id), copyFromPositionId: '', permissionCodes: selected.profile.permissionCodes, authorizationScopeType: selected.profile.authorizationScopeType ?? 'SELF', wecomSelfSelectable: selected.profile.wecomSelfSelectable ?? false } })}>编辑岗位</button><button className="secondary danger-action" onClick={() => void deletePosition(selected)}>删除</button></> : <><button className="secondary" onClick={() => void restorePosition(selected)}>恢复</button><button className="secondary danger-action" onClick={() => void permanentlyDelete(selected)}>永久删除</button></>)}</div></header>
          {view === 'active' && <>
            <PositionAccessSettings context={selectedContext} scope={draftScope} selfSelectable={draftSelfSelectable} disabled={!canManage || saving}
              onScopeChange={setDraftScope} onSelfSelectableChange={setDraftSelfSelectable} />
            <div className="position-permission-toolbar">
              <div className="config-tabs" aria-label="权限配置分类">
                <button className={permissionTab === 'modules' ? 'active' : ''} aria-pressed={permissionTab === 'modules'} onClick={() => setPermissionTab('modules')}>菜单入口</button>
                <button className={permissionTab === 'actions' ? 'active' : ''} aria-pressed={permissionTab === 'actions'} onClick={() => setPermissionTab('actions')}>操作权限</button>
              </div>
              <label>搜索功能<input type="search" value={permissionSearch} placeholder="功能名称或权限代码" onChange={(event) => setPermissionSearch(event.target.value)} /></label>
            </div>
            {configurationReady ? <PermissionChecklist options={permissionTab === 'modules' ? moduleOptions : actionOptions}
              selected={draftPermissions} query={permissionSearch} disabled={!canManage || saving} onToggle={togglePermission} />
              : <div className="position-empty" role="status">{contextLoadFailed ? <>岗位配置规则读取失败，未允许保存或发布。<button className="secondary" onClick={() => { setError(undefined); setContextRefresh((value) => value + 1) }}>重新读取</button></> : '正在读取岗位配置规则；加载成功前不可保存或发布。'}</div>}
            {(canManage || canPublish) && <footer><span>影响 <strong>{selected.activeAssignmentCount}</strong> 个有效任职 · 草稿需发布后生效</span>
              {canManage && <button className="secondary" disabled={saving || !configurationReady || selfSelectionInvalid} onClick={() => void saveProfile()}>保存草稿</button>}
              {canPublish && <button className="primary" disabled={saving || !configurationReady || selfSelectionInvalid} onClick={() => void publishProfile()}>提交发布</button>}
            </footer>}
          </>}
          {view === 'deleted' && <div className="recycle-bin-note"><strong>岗位已从可选列表隐藏。</strong><span>恢复后可选择是否恢复原任职；再次删除将永久从管理界面消失。</span></div>}
        </section> : <div className="state-card"><strong>请选择岗位</strong></div>}
        {selected && view === 'active' && selectedContext && <PositionAccessPreview label={selected.name} permissions={draftPermissions} context={selectedContext} />}
      </div>
    </article>
    {editor && <PositionModal title={editor.mode === 'create' ? '新建岗位' : '编辑岗位'} draft={editor.draft} hotels={hotels} positions={positions} options={options} context={createContext} saving={saving} configureProfile={editor.mode === 'create'} onChange={(draft) => setEditor({ ...editor, draft })} onClose={() => setEditor(undefined)} onSave={() => void createOrEdit()} />}
  </>
}
