import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type RuleType = 'MINT_ALLOWANCE' | 'AUTO_APPROVE_TRANSFER' | 'AUTO_APPROVE_BURN';

export interface MintControlRule {
  id: string;
  targetAddress: string;
  ruleType: RuleType;
  maxAmount: string | null;
  active: boolean;
  createdAt: string;
}

@Injectable({ providedIn: 'root' })
export class MintControlService {
  private readonly http = inject(HttpClient);

  private rulesUrl(assetId: string, depId: string): string {
    return `${environment.apiUrl}/assets/${assetId}/deployments/${depId}/mint-rules`;
  }

  listRules(assetId: string, depId: string): Observable<MintControlRule[]> {
    return this.http.get<MintControlRule[]>(this.rulesUrl(assetId, depId));
  }

  createRule(assetId: string, depId: string, body: {
    targetAddress: string;
    ruleType: RuleType;
    maxAmount?: string;
  }): Observable<MintControlRule> {
    return this.http.post<MintControlRule>(this.rulesUrl(assetId, depId), body);
  }

  deleteRule(assetId: string, depId: string, ruleId: string): Observable<void> {
    return this.http.delete<void>(`${this.rulesUrl(assetId, depId)}/${ruleId}`);
  }

  syncRules(assetId: string, depId: string): Observable<void> {
    return this.http.post<void>(`${this.rulesUrl(assetId, depId)}/sync`, {});
  }
}
