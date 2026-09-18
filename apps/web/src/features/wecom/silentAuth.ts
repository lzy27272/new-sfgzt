const WECOM_USER_AGENT = /\bwxwork\b/i

export function isWeComBrowser(userAgent: string = globalThis.navigator?.userAgent ?? ''): boolean {
  return WECOM_USER_AGENT.test(userAgent)
}

export function buildWeComSilentAuthUrl(returnTo = '#/workbench'): string {
  const query = new URLSearchParams({ returnTo })
  return `/api/v1/integrations/wecom/oauth/start?${query.toString()}`
}
