import { describe, expect, it } from 'vitest';
import { HttpHeaders } from '@angular/common/http';
import { signInErrorMessage } from './login.component';

describe('signInErrorMessage', () => {
  const err = (status: number, retryAfter?: string) =>
    ({ status, headers: new HttpHeaders(retryAfter ? { 'Retry-After': retryAfter } : {}) });

  it('429 reports the wait from Retry-After, in seconds or minutes', () => {
    expect(signInErrorMessage(err(429, '7'))).toBe('Too many sign-in attempts. Please try again in 7 seconds.');
    expect(signInErrorMessage(err(429, '900'))).toBe('Too many sign-in attempts. Please try again in 15 minutes.');
  });

  it('429 without a usable Retry-After still says why', () => {
    expect(signInErrorMessage(err(429))).toBe('Too many sign-in attempts. Please try again later.');
    expect(signInErrorMessage(err(429, 'soon'))).toBe('Too many sign-in attempts. Please try again later.');
  });

  it('keeps the existing messages for wrong credentials and an unavailable service', () => {
    expect(signInErrorMessage(err(401))).toBe('Invalid credentials. Please check your email and password.');
    expect(signInErrorMessage(err(0))).toBe('The sign-in service is unavailable. Please try again shortly.');
    expect(signInErrorMessage(err(503))).toBe('The sign-in service is unavailable. Please try again shortly.');
  });
});
