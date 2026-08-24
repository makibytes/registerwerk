/**
 * Re-entry guard for the MSAL redirect flows triggered from the HTTP error interceptor.
 *
 * In Entra mode `AuthService.login()` and `acquireTokenWithClaims()` are `pca.loginRedirect()` /
 * `pca.acquireTokenRedirect()` — full browser navigations, fired automatically on a 401 with no
 * user gesture. Two things then go wrong without a guard:
 *
 *  1. **Fan-out.** A dashboard `forkJoin` issues its API calls in parallel. If the token is
 *     rejected, every one of them 401s and every one independently starts a redirect.
 *  2. **Reload loop.** If Entra still has a live session it returns a token immediately — the
 *     same token the backend rejects (a mismatched `JWT_AUDIENCE` or issuer is the classic
 *     misconfiguration). The app boots, calls the API, 401s, and redirects again. Each cycle
 *     re-downloads index.html and every lazy chunk, so it presents as sustained load rather than
 *     as an error anyone can see.
 *
 * The in-memory flag handles (1); the sessionStorage timestamp survives the navigation and so
 * handles (2), converting the second attempt within the cooldown into a normal surfaced error.
 */

const LAST_REDIRECT_KEY = 'rw.auth.lastRedirectAt';

/** How long after a redirect a second automatic redirect is treated as a loop, not a retry. */
const COOLDOWN_MS = 10_000;

/** Guards against several in-flight 401s racing to redirect within a single page lifetime. */
let redirectInFlight = false;

function now(): number {
  return Date.now();
}

function readLastRedirect(): number {
  try {
    return Number(sessionStorage.getItem(LAST_REDIRECT_KEY)) || 0;
  } catch {
    // Private-browsing or blocked storage — fall back to the in-memory flag alone.
    return 0;
  }
}

function writeLastRedirect(at: number): void {
  try {
    sessionStorage.setItem(LAST_REDIRECT_KEY, String(at));
  } catch {
    /* ignore — see readLastRedirect */
  }
}

/**
 * Runs `redirect` at most once per page lifetime, and at most once per {@link COOLDOWN_MS} across
 * page loads.
 *
 * @returns true if the redirect was started; false if it was suppressed as a loop, in which case
 *          the caller should surface the error to the user instead.
 */
export function requestAuthRedirect(redirect: () => void): boolean {
  if (redirectInFlight) return false;

  const elapsed = now() - readLastRedirect();
  if (elapsed < COOLDOWN_MS) return false;

  redirectInFlight = true;
  writeLastRedirect(now());
  redirect();
  return true;
}

/**
 * Clears the cooldown after a request succeeds, so a later genuine session expiry is not mistaken
 * for a loop. Called from the auth interceptor on any successful response.
 */
export function clearAuthRedirectCooldown(): void {
  redirectInFlight = false;
  try {
    sessionStorage.removeItem(LAST_REDIRECT_KEY);
  } catch {
    /* ignore */
  }
}
