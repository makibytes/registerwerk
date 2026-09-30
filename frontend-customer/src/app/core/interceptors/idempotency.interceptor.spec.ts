import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { idempotencyInterceptor, resetIdempotencyKeys } from './idempotency.interceptor';

describe('idempotencyInterceptor', () => {
  let http: HttpClient;
  let httpMock: HttpTestingController;
  const mint = '/api/v1/assets/a1/deployments/d1/issuer/mint';

  beforeEach(() => {
    resetIdempotencyKeys();
    TestBed.configureTestingModule({
      providers: [provideHttpClient(withInterceptors([idempotencyInterceptor])), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpClient);
    httpMock = TestBed.inject(HttpTestingController);
  });
  afterEach(() => httpMock.verify());

  it('adds an Idempotency-Key to a money-moving POST', () => {
    http.post(mint, { toAddress: '0x1', amount: '5' }).subscribe();
    const req = httpMock.expectOne(mint);
    expect(req.request.headers.get('Idempotency-Key')).toMatch(/^[A-Za-z0-9._:-]{8,}$/);
    req.flush({});
  });

  it('does not touch reads or unrelated endpoints', () => {
    http.get(mint).subscribe();
    httpMock.expectOne(mint).flush({});
    http.post('/api/v1/investments', {}).subscribe();
    expect(httpMock.expectOne('/api/v1/investments').request.headers.has('Idempotency-Key')).toBe(false);
  });

  it('reuses the SAME key when the identical request is retried after a network failure / 5xx', () => {
    const body = { toAddress: '0x1', amount: '5' };
    http.post(mint, body).subscribe({ error: () => undefined });
    const first = httpMock.expectOne(mint);
    const key = first.request.headers.get('Idempotency-Key');
    first.error(new ProgressEvent('timeout'));

    http.post(mint, body).subscribe({ error: () => undefined });
    const second = httpMock.expectOne(mint);
    expect(second.request.headers.get('Idempotency-Key')).toBe(key);
    second.flush({}, { status: 503, statusText: 'Unavailable' });

    http.post(mint, body).subscribe();
    const third = httpMock.expectOne(mint);
    expect(third.request.headers.get('Idempotency-Key')).toBe(key);
    third.flush({});
  });

  it('uses a NEW key after a success, and for a different body', () => {
    http.post(mint, { amount: '5' }).subscribe();
    const a = httpMock.expectOne(mint);
    const key = a.request.headers.get('Idempotency-Key');
    a.flush({});
    http.post(mint, { amount: '5' }).subscribe();
    const b = httpMock.expectOne(mint);
    expect(b.request.headers.get('Idempotency-Key')).not.toBe(key);
    b.flush({});
    http.post(mint, { amount: '6' }).subscribe();
    const c = httpMock.expectOne(mint);
    expect(c.request.headers.get('Idempotency-Key')).not.toBe(b.request.headers.get('Idempotency-Key'));
    c.flush({});
  });

  it('forgets the key after a definitive 4xx so a corrected retry is a new action', () => {
    http.post(mint, { amount: '5' }).subscribe({ error: () => undefined });
    const a = httpMock.expectOne(mint);
    const key = a.request.headers.get('Idempotency-Key');
    a.flush({}, { status: 400, statusText: 'Bad Request' });
    http.post(mint, { amount: '5' }).subscribe();
    const b = httpMock.expectOne(mint);
    expect(b.request.headers.get('Idempotency-Key')).not.toBe(key);
    b.flush({});
  });
});
