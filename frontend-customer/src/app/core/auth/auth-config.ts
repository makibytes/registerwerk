import { InjectionToken } from '@angular/core';

/**
 * How this deployment signs users in. Fetched from `GET /public/auth/config` before Angular
 * bootstraps, because MSAL needs `clientId` and `authority` at provider-construction time —
 * baking them into `environment.prod.ts` would mean one frontend image per operator tenant.
 */
export interface AuthConfig {
  /** `LOCAL` = built-in email/password login. `ENTRA` = Microsoft Entra ID redirect. */
  mode: 'LOCAL' | 'ENTRA';
  /** OIDC authority, e.g. `https://login.microsoftonline.com/<tenant-id>`. Empty in LOCAL mode. */
  authority: string;
  /** The SPA's Entra app registration id. Empty in LOCAL mode. */
  clientId: string;
  /** Scopes to request for the backend API, e.g. `api://<api-client-id>/access_as_user`. */
  scopes: string[];
  /** Whether invite / password-reset links apply. False in Entra mode — those endpoints reject. */
  localRegistrationEnabled: boolean;
  /** Whether /security can report real status (requires the backend's Graph integration). */
  twoFactorPageEnabled: boolean;
  /** Whether users without a second factor are redirected to /security. */
  requireTwoFactorEnrolment: boolean;
  /** Microsoft's combined security-info page. We cannot register an authenticator ourselves. */
  mfaSetupUrl: string;
}

/**
 * What the app assumes when the config endpoint cannot be reached. LOCAL is the safe default:
 * it renders the password form, which either works or fails with a clear message, whereas
 * guessing ENTRA would attempt a redirect to an authority we do not know.
 */
export const LOCAL_AUTH_CONFIG: AuthConfig = {
  mode: 'LOCAL',
  authority: '',
  clientId: '',
  scopes: [],
  localRegistrationEnabled: true,
  twoFactorPageEnabled: false,
  requireTwoFactorEnrolment: false,
  mfaSetupUrl: 'https://mysignins.microsoft.com/security-info',
};

export const AUTH_CONFIG = new InjectionToken<AuthConfig>('AUTH_CONFIG');
