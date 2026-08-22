import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CorporateAction } from '../models';

@Injectable({ providedIn: 'root' })
export class CorporateActionsService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/corporate-actions`;

  downloadPositionStatement(entityId: string): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/statements`,
      { responseType: 'blob' },
    );
  }

  downloadTaxCertificate(entityId: string, year: number): Observable<Blob> {
    return this.http.get(
      `${environment.apiUrl}/customers/${entityId}/tax-certificates/${year}`,
      { responseType: 'blob' },
    );
  }

  listForAsset(assetId: string): Observable<CorporateAction[]> {
    return this.http.get<CorporateAction[]>(this.base, { params: { assetId } });
  }

  /** Every issuer-submitted proposal awaiting review, across all assets, oldest first. */
  listPendingProposals(): Observable<CorporateAction[]> {
    return this.http.get<CorporateAction[]>(`${this.base}/proposals`);
  }

  approveProposal(corporateActionId: string, stepUpToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/approve-proposal`, {}, { headers });
  }

  rejectProposal(corporateActionId: string, reason: string, stepUpToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/reject-proposal`, { reason }, { headers });
  }

  /**
   * Operator confirmation — the second of the two required settlement-approval parties, after
   * the issuer's own attestation (or an operator override). Renamed from `approveSettlement`:
   * this no longer requires a second operator (dual control) — the issuer/operator split is the
   * cross-org control that replaces same-org 2x-operator dual control.
   */
  confirmSettlement(corporateActionId: string, stepUpToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/confirm-settlement`, {}, { headers });
  }

  /** Audited escape hatch for an issuer who never logs in to attest themselves. */
  overrideAttestation(corporateActionId: string, reason: string, stepUpToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/override-attestation`, { reason }, { headers });
  }

  /**
   * Manual fallback for every settlement path with no automated on-chain adapter (e.g. SPLIT,
   * which has no on-chain split primitive on any supported standard). Requires a second approver
   * (Vieraugenprinzip) — this is the one place a free-text claim becomes register truth with no
   * counterparty.
   */
  markSettled(corporateActionId: string, reference: string, stepUpToken: string, dualControlToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}`, 'X-Dual-Control-Token': dualControlToken });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/mark-settled`, { reference }, { headers });
  }

  /** Unwinds an already-live announcement (registrar act) — operator-only, dual control. */
  cancel(corporateActionId: string, reason: string, stepUpToken: string, dualControlToken: string): Observable<CorporateAction> {
    const headers = new HttpHeaders({ Authorization: `Bearer ${stepUpToken}`, 'X-Dual-Control-Token': dualControlToken });
    return this.http.post<CorporateAction>(`${this.base}/${corporateActionId}/cancel`, { reason }, { headers });
  }

  downloadConfirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${corporateActionId}/confirmation`, { responseType: 'blob' });
  }

  downloadIso20022Confirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.base}/${corporateActionId}/confirmation/iso20022`, { responseType: 'blob' });
  }
}
