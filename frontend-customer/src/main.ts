import { bootstrapApplication } from '@angular/platform-browser';
import { AppComponent } from './app/app.component';
import { appConfig } from './app/app.config';
import { AuthConfig, LOCAL_AUTH_CONFIG } from './app/core/auth/auth-config';
import { environment } from './environments/environment';
import {
  DISABLED_PLATFORM_CAPABILITIES,
  PlatformCapabilities,
} from './app/core/feature/platform-capabilities';

/**
 * Fetches the sign-in configuration before Angular starts.
 *
 * MSAL's `PublicClientApplication` needs `clientId` and `authority` at construction time, so
 * they cannot be injected later. Fetching them here — rather than baking them into
 * `environment.prod.ts` — keeps a single frontend image deployable against any operator tenant.
 *
 * On any failure the app falls back to local sign-in: that renders the password form, which
 * either works or fails with a clear message, whereas guessing Entra would redirect to an
 * authority we do not know.
 */
async function loadAuthConfig(): Promise<AuthConfig> {
  try {
    const response = await fetch(`${environment.apiUrl}/public/auth/config`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      console.warn(`[auth] Config endpoint returned ${response.status}; assuming local sign-in.`);
      return LOCAL_AUTH_CONFIG;
    }
    return (await response.json()) as AuthConfig;
  } catch (err) {
    console.warn('[auth] Config endpoint unreachable; assuming local sign-in.', err);
    return LOCAL_AUTH_CONFIG;
  }
}

async function loadPlatformCapabilities(): Promise<PlatformCapabilities> {
  try {
    const response = await fetch(`${environment.apiUrl}/public/platform-capabilities`, {
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      console.warn(`[capabilities] Endpoint returned ${response.status}; optional features stay disabled.`);
      return DISABLED_PLATFORM_CAPABILITIES;
    }
    return (await response.json()) as PlatformCapabilities;
  } catch (err) {
    console.warn('[capabilities] Endpoint unreachable; optional features stay disabled.', err);
    return DISABLED_PLATFORM_CAPABILITIES;
  }
}

Promise.all([loadAuthConfig(), loadPlatformCapabilities()])
  .then(([cfg, capabilities]) =>
    // The build-time override exists so MSAL can be exercised locally without an Entra-configured
    // backend. Applied last, so it wins over whatever the server reported.
    bootstrapApplication(
      AppComponent,
      appConfig({ ...cfg, ...environment.authConfigOverride }, capabilities),
    )
  )
  .catch(err => console.error(err));
