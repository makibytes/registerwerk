import { afterEach, beforeEach, describe, expect, it } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WebhookService } from './webhook.service';
import { environment } from '../../../environments/environment';

describe('WebhookService', () => {
    let service: WebhookService;
    let httpMock: HttpTestingController;

    beforeEach(() => {
        TestBed.configureTestingModule({
            providers: [WebhookService, provideHttpClient(), provideHttpClientTesting()],
        });
        service = TestBed.inject(WebhookService);
        httpMock = TestBed.inject(HttpTestingController);
    });

    afterEach(() => httpMock.verify());

    it('stepUp() sends only code+method (no action/target)', () => {
        service.stepUp('123456', 'WEBHOOK_ROTATE_SECRET').subscribe();
        const req = httpMock.expectOne(`${environment.apiUrl}/auth/step-up`);
        expect(req.request.body).toEqual({ code: '123456', method: 'TOTP' });
        req.flush({ stepUpToken: 'x' });
    });
});
