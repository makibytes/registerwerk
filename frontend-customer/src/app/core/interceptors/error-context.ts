import { HttpContextToken } from '@angular/common/http';

/**
 * Set on a request whose caller renders failures itself (inline, next to the form). The global error
 * interceptor then skips its generic "Access denied" / "server error" toast for 403 and 5xx, which
 * would otherwise cover the specific message — or, worse, be the only thing the user ever saw.
 * Authentication handling (401 redirect, claims challenge, forced password change) is unaffected.
 */
export const SUPPRESS_ERROR_TOAST = new HttpContextToken<boolean>(() => false);
