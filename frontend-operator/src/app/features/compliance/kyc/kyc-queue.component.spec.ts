import { beforeEach, describe, expect, it, type MockedObject, vi } from 'vitest';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { KycQueueComponent } from './kyc-queue.component';
import { KycService } from '../../../core/api/kyc.service';
import { KycQueueItem } from '../../../core/models';

const item = (over: Partial<KycQueueItem> = {}): KycQueueItem => ({
  entityId: 'e-1',
  entityName: 'Acme GmbH',
  homeJurisdiction: 'DE_EWPG',
  kycStatus: 'IN_PROGRESS',
  kycExpiryDate: null,
  reasons: ['KYC_IN_PROGRESS', 'BO_UNVERIFIED'],
  ...over,
});

describe('KycQueueComponent', () => {
  let service: MockedObject<Pick<KycService, 'getQueue'>>;

  beforeEach(() => {
    service = { getQueue: vi.fn() };
    TestBed.configureTestingModule({
      imports: [KycQueueComponent],
      providers: [provideRouter([]), { provide: KycService, useValue: service }],
    });
  });

  it('loads the queue on init and shows the entity with readable reasons and a link to its review', async () => {
    service.getQueue.mockReturnValue(of([item()]));
    const fixture = TestBed.createComponent(KycQueueComponent);
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();

    const text = (fixture.nativeElement as HTMLElement).textContent ?? '';
    expect(service.getQueue).toHaveBeenCalledTimes(1);
    expect(fixture.componentInstance.state).toBe('ready');
    expect(text).toContain('Acme GmbH');
    expect(text).toContain('Awaiting a KYC decision; Beneficial owner not verified against a document');
    const link = (fixture.nativeElement as HTMLElement).querySelector('a[href="/compliance/kyc/e-1"]');
    expect(link).not.toBeNull();
  });

  it('shows an error state when the queue cannot be loaded and retries on demand', () => {
    service.getQueue.mockReturnValueOnce(throwError(() => new Error('boom')));
    const fixture = TestBed.createComponent(KycQueueComponent);
    fixture.detectChanges();
    expect(fixture.componentInstance.state).toBe('error');

    service.getQueue.mockReturnValueOnce(of([]));
    fixture.componentInstance.load();
    expect(fixture.componentInstance.state).toBe('ready');
    expect(service.getQueue).toHaveBeenCalledTimes(2);
  });
});
