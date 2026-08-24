import { HttpInterceptorFn } from '@angular/common/http';

/**
 * The session token moved from a manually-attached `Authorization` header into an httpOnly
 * `rw_session` cookie (see backend `SessionCookieService`) — the browser attaches it
 * automatically for same-origin requests, and `withCredentials: true` is what makes that also
 * happen for the operator portal's cross-origin dev-mode calls (environment.ts points straight
 * at the backend, bypassing Kong; see its comment). Harmless no-op for same-origin production
 * traffic, where cookies are already sent regardless of this flag.
 *
 * Step-up/dual-control calls still attach their own explicit `Authorization` header — untouched
 * here, since this interceptor no longer sets that header at all.
 */
export const authInterceptor: HttpInterceptorFn = (req, next) => {
  return next(req.clone({ withCredentials: true }));
};
