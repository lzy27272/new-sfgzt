import type { AppNavigate, AppRouteId, AppSectionId } from '../app/routeConfig'
import { isCenterNavigationItemActive, type NavigationCenter, type NavigationCenterId } from '../app/navigationModel'
import styles from './centerNavigation.module.css'

function CenterIcon({ center }: { center: NavigationCenterId }) {
  const common = { viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor', strokeWidth: 1.7, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true } as const
  if (center === 'workbench') return <svg {...common}><path d="m3 10 9-7 9 7v10H15v-6H9v6H3z" /></svg>
  if (center === 'operations') return <svg {...common}><rect x="4" y="3" width="16" height="18" rx="2" /><path d="M8 7h1m3 0h4M8 12h1m3 0h4M8 17h1m3 0h4" /></svg>
  if (center === 'people') return <svg {...common}><circle cx="12" cy="7" r="4" /><path d="M4 21v-2a8 8 0 0 1 16 0v2z" /></svg>
  if (center === 'performance') return <svg {...common}><path d="M8 5H5a2 2 0 0 0-2 2v13h18V7a2 2 0 0 0-2-2h-3" /><rect x="8" y="3" width="8" height="4" rx="1" /><path d="m8 13 3 3 5-6" /></svg>
  return <svg {...common}><path d="M10 2h4l.7 3 2.1 1.2 3-.9 2 3.4-2.3 2.1v2.4l2.3 2.1-2 3.4-3-.9-2.1 1.2-.7 3h-4l-.7-3-2.1-1.2-3 .9-2-3.4 2.3-2.1v-2.4L2.2 8.7l2-3.4 3 .9L9.3 5z" /><circle cx="12" cy="12" r="3" /></svg>
}

type MainNavigationProps = {
  centers: readonly NavigationCenter[]
  activeCenter: NavigationCenterId
  go: AppNavigate
  resolveTarget: (id: AppRouteId) => AppRouteId
}

export function MainCenterNavigation({ centers, activeCenter, go, resolveTarget, mobile = false }: MainNavigationProps & { mobile?: boolean }) {
  return <nav
    className={mobile ? `mobile-bottom-nav ${styles.mobile}` : styles.main}
    style={mobile ? { gridTemplateColumns: `repeat(${centers.length || 1}, minmax(0, 1fr))` } : undefined}
    aria-label={mobile ? '手机端主导航' : '主导航'}
  >
    {centers.map((center) => <button
      key={center.id}
      type="button"
      className={activeCenter === center.id ? 'active' : ''}
      aria-current={activeCenter === center.id ? 'page' : undefined}
      aria-label={center.label}
      onClick={() => go(resolveTarget(center.items[0].id), center.items[0].params)}
    >
      <CenterIcon center={center.id} />
      <span>{mobile ? center.shortLabel : center.label}</span>
      {!mobile && center.id !== 'workbench' && <svg className={styles.chevron} viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m7 4 6 6-6 6" stroke="currentColor" strokeWidth="1.5" /></svg>}
    </button>)}
  </nav>
}

export function CenterFeatureNavigation({ center, sectionId, go, resolveTarget, unreadCount }: {
  center?: NavigationCenter
  sectionId: AppSectionId
  go: AppNavigate
  resolveTarget: (id: AppRouteId) => AppRouteId
  unreadCount: number
}) {
  if (!center) return null
  return <nav className={styles.features} aria-label={`${center.shortLabel}子功能`}>
    {center.items.map((item) => <button
      key={item.id}
      type="button"
      className={isCenterNavigationItemActive(item, sectionId) ? styles.selected : ''}
      aria-current={isCenterNavigationItemActive(item, sectionId) ? 'page' : undefined}
      onClick={() => go(resolveTarget(item.id), item.params)}
    >{item.label}{item.id === 'notifications' && unreadCount > 0 && <b>{unreadCount > 99 ? '99+' : unreadCount}</b>}</button>)}
  </nav>
}
