import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { HttpClient, provideHttpClient, withInterceptors, } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { authInterceptor } from './auth.interceptor';

describe('authInterceptor', () => {
    let httpMock: HttpTestingController;
    let http: HttpClient;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [
                provideHttpClient(withInterceptors([authInterceptor])),
                provideHttpClientTesting(),
            ],
        });
        httpMock = TestBed.inject(HttpTestingController);
        http = TestBed.inject(HttpClient);
    });

    afterEach(() => httpMock.verify());

    it('attaches withCredentials: true to every outgoing request', () => {
        http.get('/api/v1/some-resource').subscribe();

        const req = httpMock.expectOne('/api/v1/some-resource');
        expect(req.request.withCredentials).toBe(true);
        req.flush({});
    });

    it('does not alter the request method, URL, or body', () => {
        http.post('/api/v1/thing', { a: 1 }).subscribe();

        const req = httpMock.expectOne('/api/v1/thing');
        expect(req.request.method).toBe('POST');
        expect(req.request.body).toEqual({ a: 1 });
        expect(req.request.withCredentials).toBe(true);
        req.flush({});
    });

    it('preserves query params and headers already set on the request', () => {
        http.get('/api/v1/thing', { params: { foo: 'bar' }, headers: { 'X-Test': '1' } }).subscribe();

        const req = httpMock.expectOne(r => r.url === '/api/v1/thing');
        expect(req.request.params.get('foo')).toBe('bar');
        expect(req.request.headers.get('X-Test')).toBe('1');
        expect(req.request.withCredentials).toBe(true);
        req.flush({});
    });
});
