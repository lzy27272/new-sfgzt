import type { RolePresentationKey } from '../../app/rolePresentationPolicy'

export type TaskDispatchMode = 'EXECUTIVE' | 'MANAGEMENT'

export function hasGrantedPermission(permissions: string[], permission: string): boolean {
  return permissions.includes('*') || permissions.includes(permission)
}

export function resolveTaskDispatchMode(
  presentationKey: RolePresentationKey,
  executiveTasksEnabled: boolean,
  permissions: string[],
): TaskDispatchMode | undefined {
  if (presentationKey === 'GROUP_CHAIRMAN') {
    return executiveTasksEnabled && hasGrantedPermission(permissions, 'executive-task.assign')
      ? 'EXECUTIVE'
      : undefined
  }
  return hasGrantedPermission(permissions, 'task.create') && hasGrantedPermission(permissions, 'task.dispatch')
    ? 'MANAGEMENT'
    : undefined
}

export function loadOptionalTaskContext<T>(
  permissions: string[],
  permission: string,
  loader: () => Promise<T[]>,
): Promise<T[]> {
  return hasGrantedPermission(permissions, permission) ? loader() : Promise.resolve([])
}
