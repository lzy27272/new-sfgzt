import { useMemo, type Dispatch, type SetStateAction } from 'react'
import { currentAssignment, groupPersonnel, personnelScope, type DirectoryEmployee, type DirectoryOrg } from './personnelDirectoryModel'
import { localBusinessDate } from '../workbench/workBoardModel'
import '../workbench/workBoard.css'

export type PersonnelDirectoryState = {
  selected?: string; search: string; history: boolean; department: string; position: string; expanded?: string; scopeKind: string
}
export const initialPersonnelDirectory: PersonnelDirectoryState = {
  search: '', history: false, department: '', position: '', scopeKind: 'ALL',
}

export function PersonnelDirectory<T extends DirectoryEmployee>({ orgs, employees, saving, state, setState, onEdit, onEditAssignment, onEnd, onToggle, onDelete }: {
  orgs: DirectoryOrg[]; employees: T[]; saving: boolean
  state: PersonnelDirectoryState; setState: Dispatch<SetStateAction<PersonnelDirectoryState>>
  onEdit: (employee: T) => void; onEditAssignment: (employee: T) => void; onEnd: (employee: T) => void
  onToggle: (employee: T) => void; onDelete: (employee: T) => void
}) {
  const today = localBusinessDate()
  const scopes = useMemo(() => orgs.filter(org => ['HOTEL', 'GROUP'].includes(org.unitType) && org.status === 'ACTIVE'), [orgs])
  const selected = state.selected ?? (scopes.length === 1 ? scopes[0].id : '')
  const { search, history, department, position, expanded, scopeKind } = state
  const setSearch = (search: string) => setState(previous => ({ ...previous, search }))
  const setHistory = (history: boolean) => setState(previous => ({ ...previous, history }))
  const setDepartment = (department: string) => setState(previous => ({ ...previous, department }))
  const setPosition = (position: string) => setState(previous => ({ ...previous, position }))
  const setExpanded = (expanded?: string) => setState(previous => ({ ...previous, expanded }))
  const setScopeKind = (scopeKind: string) => setState(previous => ({ ...previous, scopeKind }))
  const groups = useMemo(() => groupPersonnel(employees, orgs, selected, today, history), [employees, orgs, selected, today, history])
  const currentGroups = useMemo(() => groupPersonnel(employees, orgs, selected, today), [employees, orgs, selected, today])
  const matchingGroups = groups.filter(rows => rows.some(row =>
    (!department || row.orgUnitId === department) && (!position || row.positionId === position))
    && [rows[0].name, rows[0].loginName].some(value => value?.toLocaleLowerCase().includes(search.toLocaleLowerCase())))
  const scopeName = selected === 'unassigned' ? '待分配人员' : scopes.find(org => org.id === selected)?.name || '当前组织'
  const choose = (id: string) => setState({ ...initialPersonnelDirectory, selected: id })
  const rowsInScope = employees.filter(row => personnelScope(row, orgs) === selected && currentAssignment(row, today))
  const departments = [...new Map(rowsInScope.filter(row => row.orgUnitId).map(row => [row.orgUnitId!, row.orgUnitName || '直属岗位'])).entries()]
  const positions = [...new Map(rowsInScope.filter(row => row.positionId).map(row => [row.positionId!, row.positionName || '岗位'])).entries()]
  return <div className="pd-directory">
    {!selected ? <>
      <div className="pd-heading"><div><h3>选择门店 / 总部</h3><p>先选择组织，再查看人员；人数按员工去重，不按兼职重复计算。</p></div></div>
      <div className="wb-scope-filters"><input aria-label="搜索门店或总部" placeholder="搜索门店 / 总部名称" value={search} onChange={event => setSearch(event.target.value)} />
        <select aria-label="组织类型" value={scopeKind} onChange={event => setScopeKind(event.target.value)}><option value="ALL">全部组织</option><option value="HOTEL">门店</option><option value="GROUP">集团总部</option></select>
      </div>
      <div className="wb-table-scroll" role="region" tabIndex={0} aria-label="数据表，可左右滚动"><table className="wb-summary-table"><thead><tr><th>门店 / 总部</th><th>在职人数</th><th>主岗人数</th><th>含兼职人数</th><th>操作</th></tr></thead>
        <tbody>{scopes.filter(org => (scopeKind === 'ALL' || org.unitType === scopeKind) && org.name.includes(search)).map(org => {
          const rows = groupPersonnel(employees, orgs, org.id, today)
          return <tr key={org.id}><td><strong>{org.unitType === 'GROUP' ? '集团总部 · ' : ''}{org.name}</strong><small>{org.city || (org.unitType === 'GROUP' ? '总部实际任职，不含仅负责门店的高管' : '本店及下属部门')}</small></td>
            <td data-label="在职人数">{rows.length}</td><td data-label="主岗人数">{rows.filter(group => group.some(row => row.primary)).length}</td><td data-label="含兼职人数">{rows.filter(group => group.some(row => !row.primary)).length}</td>
            <td><button className="text-action" onClick={() => choose(org.id)}>进入人员 ›</button></td></tr>
        })}</tbody></table></div>
      <button className="wb-compact-row" onClick={() => choose('unassigned')}><span>待分配 / 无有效任职人员</span><b>{groupPersonnel(employees, orgs, 'unassigned', today).length} 人 ›</b></button>
    </> : <>
      <div className="pd-heading"><div><button className="text-action" onClick={() => choose('')}>‹ 返回门店与总部</button><h3>{scopeName} · 人员与任职</h3></div>
        <select aria-label="切换人员门店" value={selected} onChange={event => choose(event.target.value)}>{scopes.map(org => <option key={org.id} value={org.id}>{org.unitType === 'GROUP' ? '集团总部 · ' : ''}{org.name}</option>)}<option value="unassigned">待分配人员</option></select></div>
      <div className="pd-metrics"><span>当前在职 <strong>{selected === 'unassigned' ? 0 : currentGroups.length}</strong> 人</span><span>本组织有效任职 <strong>{rowsInScope.length}</strong> 项</span><small>同一员工只显示一行；兼职、未生效及历史任职可展开查看</small></div>
      <div className="wb-scope-filters">
        <input aria-label="搜索员工" placeholder="员工姓名 / 登录账号" value={search} onChange={event => setSearch(event.target.value)} />
        <select aria-label="人员部门筛选" value={department} onChange={event => setDepartment(event.target.value)}><option value="">全部部门</option>{departments.map(([id, name]) => <option value={id} key={id}>{name}</option>)}</select>
        <select aria-label="人员岗位筛选" value={position} onChange={event => setPosition(event.target.value)}><option value="">全部岗位</option>{positions.map(([id, name]) => <option value={id} key={id}>{name}</option>)}</select>
        <label><input type="checkbox" checked={history} onChange={event => setHistory(event.target.checked)} /> 包含历史 / 未生效任职</label>
      </div>
      <div className="pd-employees">{matchingGroups.map(rows => {
        const employee = rows.find(row => row.primary && currentAssignment(row, today)) ?? rows[0]
        const isExpanded = expanded === employee.id
        return <article className="pd-employee" key={employee.id}>
          <div className="pd-employee-main">
            <span><strong>{employee.name}</strong><small>账号：{employee.loginName || '未开通'}</small></span>
            <span>{[...new Set(rows.map(row => row.positionName || '待分配'))].join('、')}<small>{rows.length} 项任职</small></span>
            <span className={'wb-badge ' + (employee.employmentStatus === 'ACTIVE' ? 'completed' : 'optional')}>{employee.employmentStatus === 'ACTIVE' ? '账号启用' : '账号停用'}</span>
            <div className="wb-actions"><button className="text-action" onClick={() => setExpanded(isExpanded ? undefined : employee.id)}>{isExpanded ? '收起任职' : '展开任职'}</button>
              <button className="text-action" onClick={() => onEdit(employee)}>编辑员工</button>
              <button className="text-action" disabled={saving} onClick={() => {
                if (window.confirm(employee.employmentStatus === 'ACTIVE'
                  ? '停用员工账号会结束该员工所有门店的有效任职。若只结束本店兼职，请展开任职后点击“结束任职”。确认停用全部岗位？'
                  : '确认启用员工账号？历史任职不会自动恢复，需要重新配置有效任职。')) onToggle(employee)
              }}>{employee.employmentStatus === 'ACTIVE' ? '停用全部岗位' : '启用账号'}</button>
              {employee.employmentStatus !== 'ACTIVE' && <button className="text-action danger" disabled={saving} onClick={() => onDelete(employee)}>删除员工</button>}
            </div>
          </div>
          {isExpanded && <div className="pd-assignments">{rows.map(row => <div className="pd-assignment" key={row.assignmentId || 'none'}>
            <span><strong>{row.positionName || '待分配'} · {row.orgUnitName || '未归属'}</strong><small>{row.primary ? '主岗' : '兼岗'} · {row.validFrom || '—'} 至 {row.validTo || '长期'}
              {' · '}{currentAssignment(row, today) ? '当前有效' : row.validFrom && row.validFrom > today ? '尚未生效' : '已结束 / 无效'}</small>
              {row.responsibleHotelNames && <small>管理范围：{row.responsibleHotelNames}（不等于门店人员归属）</small>}</span>
            {row.assignmentId && (!row.assignmentStatus || row.assignmentStatus === 'ACTIVE') && <div className="wb-actions">
              <button className="text-action" disabled={saving} onClick={() => onEditAssignment(row)}>修改任职</button>
              <button className="text-action danger" disabled={saving} onClick={() => onEnd(row)}>结束任职</button></div>}
          </div>)}</div>}
        </article>
      })}</div>
      {!matchingGroups.length && <p className="wb-empty">当前筛选下没有人员；可检查日期有效期或查看历史任职。</p>}
    </>}
  </div>
}
