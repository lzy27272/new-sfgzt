const MANUAL_LOGIN_KEY = 'hotel-ai-os-login-method'

type LoginBrowser = {
  location: Pick<Location, 'hash'>
  sessionStorage: Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
}

// This is only a UI choice, never a credential or an authorization decision.
// The hash also keeps recovery usable when the WebView blocks browser storage.
export function requestsManualLogin(browser: LoginBrowser = window): boolean {
  if (browser.location.hash === '#/login') return true
  try { return browser.sessionStorage.getItem(MANUAL_LOGIN_KEY) === 'password' } catch { return false }
}

export function rememberManualLogin(browser: LoginBrowser = window): void {
  try { browser.sessionStorage.setItem(MANUAL_LOGIN_KEY, 'password') } catch { /* URL remains the fallback */ }
}

export function clearManualLogin(browser: LoginBrowser = window): void {
  try { browser.sessionStorage.removeItem(MANUAL_LOGIN_KEY) } catch { /* storage may be disabled */ }
}
