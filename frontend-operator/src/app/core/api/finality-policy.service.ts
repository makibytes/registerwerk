import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import {
  FinalityLevel,
  FinalityPolicyAssignmentView,
  FinalityPolicyOverrideView,
  FinalityPolicyProfile,
  TokenStandard,
} from '../models';

@Injectable({ providedIn: 'root' })
export class FinalityPolicyService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/finality-policies`;

  listAssignments(): Observable<FinalityPolicyAssignmentView[]> {
    return this.http.get<FinalityPolicyAssignmentView[]>(`${this.base}/assignments`);
  }

  listOverridesForAsset(assetId: string): Observable<FinalityPolicyOverrideView[]> {
    return this.http.get<FinalityPolicyOverrideView[]>(`${this.base}/assets/${assetId}/overrides`);
  }

  listOperations(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/operations`);
  }

  setGlobalProfile(profile: FinalityPolicyProfile, stepUpToken: string): Observable<FinalityPolicyAssignmentView> {
    return this.http.put<FinalityPolicyAssignmentView>(`${this.base}/global`, { profile },
      { headers: this.authHeader(stepUpToken) });
  }

  setTokenStandardProfile(
    tokenStandard: TokenStandard, profile: FinalityPolicyProfile, stepUpToken: string,
  ): Observable<FinalityPolicyAssignmentView> {
    return this.http.put<FinalityPolicyAssignmentView>(`${this.base}/token-standards/${tokenStandard}`,
      { profile }, { headers: this.authHeader(stepUpToken) });
  }

  setAssetProfile(
    assetId: string, profile: FinalityPolicyProfile, stepUpToken: string,
  ): Observable<FinalityPolicyAssignmentView> {
    return this.http.put<FinalityPolicyAssignmentView>(`${this.base}/assets/${assetId}`,
      { profile }, { headers: this.authHeader(stepUpToken) });
  }

  deleteAssignment(assignmentId: string, stepUpToken: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/assignments/${assignmentId}`,
      { headers: this.authHeader(stepUpToken) });
  }

  createOverride(
    assetId: string, operation: string, requiredLevel: FinalityLevel, reason: string, stepUpToken: string,
  ): Observable<FinalityPolicyOverrideView> {
    return this.http.post<FinalityPolicyOverrideView>(`${this.base}/assets/${assetId}/overrides`,
      { operation, requiredLevel, reason }, { headers: this.authHeader(stepUpToken) });
  }

  deleteOverride(overrideId: string, stepUpToken: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/overrides/${overrideId}`,
      { headers: this.authHeader(stepUpToken) });
  }

  private authHeader(stepUpToken: string): HttpHeaders {
    return new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
  }
}
