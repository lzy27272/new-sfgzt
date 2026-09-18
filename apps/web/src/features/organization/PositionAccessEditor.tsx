import { useMemo } from 'react'
import { buildPositionPreview, groupPermissionOptions, isModuleOption, permissionDescription, permissionLabel, positionFeatureChecks, scopeDescriptions, type AuthorizationScope, type PermissionOption, type ProfileContext } from './positionAccessModel'

export function PositionAccessSettings({ context, scope, selfSelectable, disabled, onScopeChange, onSelfSelectableChange }: {
  context?: ProfileContext; scope: AuthorizationScope; selfSelectable: boolean; disabled: boolean
  onScopeChange: (scope: AuthorizationScope) => void; onSelfSelectableChange: (value: boolean) => void
}) {
  return <section className="position-access-settings" aria-label="数据范围与入职申请">
    <div className="position-profile-controls">
      <label>任职数据范围<select value={scope} disabled={disabled || !context} onChange={(event) => onScopeChange(event.target.value as AuthorizationScope)}>
        {(context?.authorizationScopeTypes ?? [scope]).map((value) => <option key={value} value={value}>{scopeDescriptions[value].label}</option>)}
      </select></label>
      <label className="checkbox-label"><input type="checkbox" checked={selfSelectable}
        disabled={disabled || !context || (Boolean(context.selfSelectionBlockedReason) && !selfSelectable)}
        onChange={(event) => onSelfSelectableChange(event.target.checked)} />允许员工在企微申请时自选</label>
    </div>
    <div className="position-access-help">
      <p>{scopeDescriptions[scope].description}</p>
      {scope === 'TENANT' && <p>总部员工申请页不开放集团全部范围的岗位自选，请由有权审核员确认分配。</p>}
      <p>“适用门店”限定岗位可在哪里任职，不等于员工的数据权限；同岗不同门店请使用“指定负责门店”。</p>
      <p className={context?.selfSelectionBlockedReason ? 'position-access-warning' : ''}>{context?.selfSelectionBlockedReason ?? '发布后，符合条件的岗位才会出现在员工申请页。未开放的岗位不展示；员工可选择“岗位待分配”。'}</p>
      <p>总部受保护岗位由具备组织管理权限、且覆盖该组织的审核员分配；草稿配置不会自动通过入职申请。</p>
    </div>
  </section>
}

export function PermissionChecklist({ options, selected, query = '', disabled, onToggle }: {
  options: PermissionOption[]; selected: string[]; query?: string; disabled: boolean; onToggle: (code: string, checked: boolean) => void
}) {
  const groups = useMemo(() => groupPermissionOptions(options, query), [options, query])
  return <div className="position-permission-groups">
    {groups.map((group) => <section className="position-permission-group" key={group.id}>
      <h4>{group.label}<small>{group.items.filter((option) => selected.includes(option.permissionCode)).length} / {group.items.length} 已选</small></h4>
      {group.items.map((option) => <label className={`position-permission-option ${!option.delegable ? 'protected' : ''}`} key={option.permissionCode}>
        <input type="checkbox" checked={selected.includes(option.permissionCode)} disabled={disabled || (!option.delegable && !selected.includes(option.permissionCode))}
          onChange={(event) => onToggle(option.permissionCode, event.target.checked)} />
        <span><strong>{permissionLabel(option)}</strong><small>{permissionDescription(option)}</small>{!isModuleOption(option) && !option.delegable && <code>{option.permissionCode}</code>}</span>
      </label>)}
    </section>)}
    {!groups.length && <p className="position-empty">没有匹配的权限，请调整搜索条件。</p>}
  </div>
}

export function PositionAccessPreview({ label, permissions, context }: { label: string; permissions: string[]; context: ProfileContext }) {
  const centers = useMemo(() => buildPositionPreview(permissions, context, label), [permissions, context, label])
  const checks = useMemo(() => positionFeatureChecks(permissions, context), [permissions, context])
  return <aside className="position-preview" aria-label="岗位草稿预览">
    <h3>员工端菜单预览 · 当前草稿</h3>
    <p className="position-preview-note">按有效任职预览，不使用当前管理员的权限。个人附加授权、门店减权方案及功能开关仍会影响实际结果；保存草稿不会改变已发布版本。</p>
    {!permissions.some((code) => code.startsWith('ui.module.')) && <p className="position-preview-note">尚无显式菜单配置，按系统现有的历史兼容规则预览；页面操作仍受权限限制。</p>}
    <div className="position-center-preview">{centers.map((center) => <section key={center.id}><h4>{center.label}</h4><ul>{center.items.map((item) => <li key={item.id}>{item.label}</li>)}</ul></section>)}
      {!centers.length && <p>当前草稿没有可显示的业务菜单，请核对菜单入口及读取权限。</p>}
    </div>
    <h3 className="position-check-heading">新功能配置检查</h3>
    <div className="position-feature-checks">{checks.map((check) => <section key={check.label}>
      <header><strong>{check.label}</strong><span className={check.ready ? 'ready' : 'incomplete'}>{check.statusLabel ?? (check.ready ? '入口条件已具备' : '条件未齐 / 未开启')}</span></header><p>{check.detail}</p>
    </section>)}</div>
    {context.retainedPermissions.length > 0 && <details className="position-retained-permissions"><summary>系统保留权限（{context.retainedPermissions.length} 项，只读）</summary>
      <p>不会随本页草稿增删，也不会自动下放到任意任职。实际生效由服务端账号、岗位与组织授权决定。</p>
      <ul>{context.retainedPermissions.map((option) => <li key={option.permissionCode}><strong>{option.label}</strong><code>{option.permissionCode}</code></li>)}</ul>
    </details>}
  </aside>
}
