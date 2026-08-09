import { ApplicationConfig } from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideHttpClient, withInterceptors, withXsrfConfiguration } from '@angular/common/http';
import { routes } from './app.routes';
import { authInterceptor } from './core/interceptors/auth.interceptor';
import { errorInterceptor } from './core/interceptors/error.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideRouter(routes, withComponentInputBinding()),
    provideHttpClient(
      withInterceptors([authInterceptor, errorInterceptor]),
      // Reads the (non-httpOnly) XSRF-TOKEN cookie SpaCsrfConfig sets on the backend and
      // attaches it as X-XSRF-TOKEN on mutating requests — these are Angular's own default
      // names, chosen to match Spring Security's CookieCsrfTokenRepository defaults exactly,
      // required now that the session cookie is httpOnly (see SessionCookieService).
      withXsrfConfiguration({ cookieName: 'XSRF-TOKEN', headerName: 'X-XSRF-TOKEN' }),
    ),
  ]
};
