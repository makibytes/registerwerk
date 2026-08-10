import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { routes } from './app.routes';
import { AUTH_CONFIG, AuthConfig } from './core/auth/auth-config';
import { CookieTokenSource } from './core/auth/cookie-token-source';
import { MsalTokenSource } from './core/auth/msal-token-source';
import { TokenSource } from './core/auth/token-source';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';
import { PLATFORM_CAPABILITIES, PlatformCapabilities } from './core/feature/platform-capabilities';

/**
 * Builds the application providers for a given sign-in mode.
 *
 * Takes the config as a parameter rather than injecting it because MSAL's
 * `PublicClientApplication` must be constructed with a `clientId` and `authority` already known
 * — see `main.ts`, which fetches them before bootstrapping.
 */
export function appConfig(cfg: AuthConfig, capabilities: PlatformCapabilities): ApplicationConfig {
  return {
    providers: [
      provideRouter(routes, withComponentInputBinding()),
      provideHttpClient(
        withInterceptors([authInterceptor, errorInterceptor]),
        withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' })
      ),
      { provide: AUTH_CONFIG, useValue: cfg },
      { provide: PLATFORM_CAPABILITIES, useValue: capabilities },
      {
        provide: TokenSource,
        useClass: cfg.mode === 'ENTRA' ? MsalTokenSource : CookieTokenSource,
      },
      // Runs before the router activates, so MSAL has already processed any redirect response
      // and chosen an active account by the time a guard asks whether the user is signed in.
      provideAppInitializer(() => inject(TokenSource).initialize()),
    ],
  };
}
