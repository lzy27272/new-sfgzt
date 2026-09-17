export function bootstrapAssignmentId(authMode: string, acceptanceAssignmentId?: string): string {
  return authMode === 'dev-header' ? acceptanceAssignmentId ?? '' : ''
}

export function bootstrapAssignments<T>(authMode: string, acceptanceAssignments: T[]): T[] {
  return authMode === 'dev-header' ? acceptanceAssignments : []
}

export function canLoadSecondaryResources(
  authMode: string,
  identityLoading: boolean,
  identityError?: string,
): boolean {
  return authMode === 'dev-header' || (!identityLoading && !identityError)
}

export function shouldExpireAuthenticatedSession(authMode: string, status: number): boolean {
  return authMode !== 'dev-header' && status === 401
}

export function canRenderAuthenticatedShell(
  authMode: string,
  identityLoading: boolean,
  identityError?: string,
): boolean {
  return authMode === 'dev-header' || (!identityLoading && !identityError)
}

export function canUseStoredAcceptanceRole(authMode: string): boolean {
  return authMode === 'dev-header'
}
