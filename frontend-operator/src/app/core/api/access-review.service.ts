import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { HttpHeaders } from '@angular/common/http';
import { environment } from '../../../environments/environment';

export interface AccessReviewCampaign {
  id: string;
  name: string;
  status: 'OPEN' | 'CLOSED';
  dueDate: string | null;
  startedBy: string;
  startedAt: string;
  closedBy: string | null;
  closedAt: string | null;
}

export interface AccessReviewItem {
  id: string;
  campaignId: string;
  appUserId: string;
  email: string;
  fullName: string | null;
  roles: string;
  /** REVOKE_PROPOSED: privileged account, waits for a different second reviewer; STALE: roles changed since the snapshot, reopen it. */
  decision: 'PENDING' | 'CONFIRMED' | 'REVOKED' | 'REVOKE_PROPOSED' | 'STALE';
  reviewedBy: string | null;
  reviewedAt: string | null;
  notes: string | null;
  proposedBy: string | null;
  proposedAt: string | null;
  /** Comma-separated segregation-of-duties role pairs ("A+B"): a warning only. */
  sodConflicts: string | null;
  reopenedCount: number;
}

@Injectable({ providedIn: 'root' })
export class AccessReviewService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/access-reviews`;

  listCampaigns(): Observable<AccessReviewCampaign[]> {
    return this.http.get<AccessReviewCampaign[]>(this.base);
  }

  getCampaign(id: string): Observable<AccessReviewCampaign> {
    return this.http.get<AccessReviewCampaign>(`${this.base}/${id}`);
  }

  startCampaign(body: { name: string; dueDate?: string }): Observable<AccessReviewCampaign> {
    return this.http.post<AccessReviewCampaign>(this.base, body);
  }

  listItems(campaignId: string): Observable<AccessReviewItem[]> {
    return this.http.get<AccessReviewItem[]>(`${this.base}/${campaignId}/items`);
  }

  /** Needs a step-up token as bearer. A privileged account's REVOKED first becomes REVOKE_PROPOSED. */
  recordDecision(campaignId: string, itemId: string, decision: 'CONFIRMED' | 'REVOKED', stepUpToken: string, notes?: string): Observable<AccessReviewItem> {
    return this.http.post<AccessReviewItem>(`${this.base}/${campaignId}/items/${itemId}/decision`, { decision, notes },
      { headers: new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` }) });
  }

  /** REGISTRY_ADMIN, step-up, reason: re-snapshots a decided or STALE item. */
  reopenItem(campaignId: string, itemId: string, reason: string, stepUpToken: string): Observable<AccessReviewItem> {
    return this.http.post<AccessReviewItem>(`${this.base}/${campaignId}/items/${itemId}/reopen`, { reason },
      { headers: new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` }) });
  }

  closeCampaign(campaignId: string): Observable<AccessReviewCampaign> {
    return this.http.post<AccessReviewCampaign>(`${this.base}/${campaignId}/close`, {});
  }
}
