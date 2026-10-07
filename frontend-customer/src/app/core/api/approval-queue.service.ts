import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { ApprovalQueueClient } from '@registerwerk/ui';
import { environment } from '../../../environments/environment';

/**
 * In-app approval queue (`/api/v1/approvals`, T8-02), requester side only: customer users file a request,
 * wait for a decision by operator staff and claim the approver token. All logic lives in the shared
 * {@link ApprovalQueueClient} / flow in `@registerwerk/ui`; this is only the DI + base-URL wiring.
 */
@Injectable({ providedIn: 'root' })
export class ApprovalQueueService extends ApprovalQueueClient {
  constructor() {
    super(inject(HttpClient), `${environment.apiUrl}/approvals`);
  }
}
