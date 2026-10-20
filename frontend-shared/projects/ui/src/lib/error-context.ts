import { HttpContextToken } from '@angular/common/http';

/**
 * Set on a request whose caller renders failures itself (inline, next to the form). The app's global
 * error interceptor then skips its generic "Access denied" (403) toast - and the customer portal's
 * "server error" (5xx) toast - which would otherwise cover the specific message or, worse, be the only
 * thing the user ever saw.
 * Authentication handling (401 redirect, claims challenge, forced password change) is unaffected.
 */
export const SUPPRESS_ERROR_TOAST = new HttpContextToken<boolean>(() => false);
