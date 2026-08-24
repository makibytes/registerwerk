import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
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
  decision: 'PENDING' | 'CONFIRMED' | 'REVOKED';
  reviewedBy: string | null;
  reviewedAt: string | null;
  notes: string | null;
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

  recordDecision(campaignId: string, itemId: string, decision: 'CONFIRMED' | 'REVOKED', notes?: string): Observable<AccessReviewItem> {
    return this.http.post<AccessReviewItem>(`${this.base}/${campaignId}/items/${itemId}/decision`, { decision, notes });
  }

  closeCampaign(campaignId: string): Observable<AccessReviewCampaign> {
    return this.http.post<AccessReviewCampaign>(`${this.base}/${campaignId}/close`, {});
  }
}
