import { hasPermission, permissions as permissionCodes } from '../../app/permissions'
import type { AppNavigate } from '../../app/routeConfig'

type ShortcutProps = {
  permissions: string[]
  available: boolean
  go: AppNavigate
}

export function OwnDailyReportShortcut({ permissions, available, hasAssignment, go }: ShortcutProps & { hasAssignment: boolean }) {
  if (!available || !hasAssignment || !hasPermission(permissions, permissionCodes.dailyReport.readOwn)) return null
  const canSubmit = hasPermission(permissions, permissionCodes.dailyReport.submit)
  return <button type="button" className={canSubmit ? 'primary' : 'secondary'}
    title="打开本人当前任职的日报" onClick={() => go('daily-reports-my')}>
    {canSubmit ? '日报填报' : '我的日报'}
  </button>
}

export function TeamDailyReportShortcut({ permissions, available, hotelId, go }: ShortcutProps & { hotelId: string }) {
  if (!available || !hotelId || !hasPermission(permissions, permissionCodes.dailyReport.readTeam)) return null
  return <button type="button" className="text-action" onClick={() => go('daily-reports-team', { orgUnitId: hotelId })}>团队日报</button>
}
