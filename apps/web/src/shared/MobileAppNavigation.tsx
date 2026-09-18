import type { AppNavigate, AppRouteId, AppSectionId } from '../app/routeConfig'
import { buildNavigationCenters } from '../app/navigationModel'

export type FunctionNavigationItem = {
  id: AppRouteId
  sectionId?: string
  label: string
  icon: string
  group?: string
}

export type MobileNavigationIcon = 'workbench' | 'tasks' | 'reports' | 'operations' | 'performance' | 'notifications' | 'profile'

export type MobileNavigationItem = {
  key: string
  label: string
  target: AppRouteId
  matchSections: readonly AppSectionId[]
  icon: MobileNavigationIcon
  badge?: 'unread'
  disabled?: boolean
}

export type MobileNavigationItems = readonly [
  MobileNavigationItem,
  MobileNavigationItem,
  MobileNavigationItem,
  MobileNavigationItem,
  MobileNavigationItem,
]

function TabIcon({ icon }: { icon: MobileNavigationIcon }) {
  const common = { width: 22, height: 22, viewBox: '0 0 24 24', fill: 'none', 'aria-hidden': true } as const
  if (icon === 'workbench') return <svg {...common}><path d="M3.5 10.2 12 3.5l8.5 6.7v9.3a1 1 0 0 1-1 1h-5v-6h-5v6h-5a1 1 0 0 1-1-1z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /></svg>
  if (icon === 'tasks') return <svg {...common}><rect x="5" y="4.5" width="14" height="16" rx="2" stroke="currentColor" strokeWidth="1.8" /><path d="M9 4.5v-1h6v1M8.5 10.5l1.5 1.5 3-3M8.5 16h7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
  if (icon === 'reports') return <svg {...common}><path d="M6 3.5h9l3 3v14H6z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M15 3.5v3h3M9 11h6M9 15h6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
  if (icon === 'operations') return <svg {...common}><path d="M4 19.5V10l8-5.5 8 5.5v9.5z" stroke="currentColor" strokeWidth="1.8" strokeLinejoin="round" /><path d="M8 19.5v-5h8v5M8 10h.01M12 10h.01M16 10h.01" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
  if (icon === 'performance') return <svg {...common}><path d="M5 19V9M12 19V4M19 19v-7" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /><path d="M3.5 19.5h17" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
  if (icon === 'notifications') return <svg {...common}><path d="M5 10a7 7 0 0 1 14 0v4l1.5 2.5h-17L5 14zM9.5 20h5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
  return <svg {...common}><circle cx="12" cy="8" r="3.5" stroke="currentColor" strokeWidth="1.8" /><path d="M5.5 20a6.5 6.5 0 0 1 13 0" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" /></svg>
}

export function MobileBottomNavigation({
  sectionId,
  go,
  items,
  unreadCount,
}: {
  sectionId: AppSectionId
  go: AppNavigate
  items: MobileNavigationItems
  unreadCount: number
}) {
  return <nav className="mobile-bottom-nav" aria-label="手机端主导航">
    {items.map((item) => {
      const active = item.matchSections.includes(sectionId)
      const itemUnreadCount = item.badge === 'unread' ? unreadCount : 0
      return <button
        type="button"
        key={item.key}
        className={active ? 'active' : ''}
        disabled={item.disabled}
        onClick={() => go(item.target)}
        aria-current={active ? 'page' : undefined}
        aria-label={itemUnreadCount > 0 ? `${item.label}，${itemUnreadCount}条未读` : item.label}
      >
        <span className="mobile-tab-icon"><TabIcon icon={item.icon} />{itemUnreadCount > 0 && <b>{itemUnreadCount > 99 ? '99+' : itemUnreadCount}</b>}</span>
        <span>{item.label}</span>
      </button>
    })}
  </nav>
}

export function AllFunctionsPage({
  items,
  roleLabel,
  orgName,
  go,
  resolveTarget,
  onChangePassword,
  onLogout,
}: {
  items: FunctionNavigationItem[]
  roleLabel: string
  orgName: string
  go: AppNavigate
  resolveTarget: (id: AppRouteId) => AppRouteId
  onChangePassword?: () => void
  onLogout?: () => void
}) {
  const groups = buildNavigationCenters(items.filter((item) => item.id !== 'workbench'), roleLabel)

  return <section className="all-functions-page">
    <header>
      <h1>全部功能</h1>
      <p>仅显示当前岗位可使用的功能</p>
      <div className="mobile-identity-summary"><strong>{roleLabel}</strong><span>{orgName}</span></div>
    </header>
    <div className="all-function-groups">
      {groups.map((group) => <section key={group.id}>
        <h2>{group.label}</h2>
        <div className="all-function-list">
          {group.items.map((item) => <button type="button" key={item.id} onClick={() => go(resolveTarget(item.id), item.params)}>
            <i aria-hidden="true">{item.icon}</i><span>{item.label}</span><b aria-hidden="true">›</b>
          </button>)}
        </div>
      </section>)}
      {(onChangePassword || onLogout) && <section>
        <h2>个人与系统</h2>
        <div className="all-function-list">
          {onChangePassword && <button type="button" onClick={onChangePassword}><i aria-hidden="true">⌁</i><span>修改密码</span><b aria-hidden="true">›</b></button>}
          {onLogout && <button type="button" onClick={onLogout}><i aria-hidden="true">↪</i><span>退出登录</span><b aria-hidden="true">›</b></button>}
        </div>
      </section>}
    </div>
  </section>
}
