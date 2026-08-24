import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { CorporateActionType, CorporateActionView } from '../models';

/**
 * Corporate-action self-service for both customer roles:
 * - Issuer side (`/api/v1/assets/{assetId}/corporate-actions`,
 *   `corporateactions.web.IssuerCorporateActionController`) — propose a DIVIDEND/SPLIT/CALL,
 *   withdraw a still-PROPOSED one, attest that settlement is ready.
 * - Investor side (`/api/v1/me/corporate-actions`, `corporateactions.web.MeCorporateActionController`)
 *   — see corporate actions affecting a holding, download confirmations. Scoped to holdings/issuer
 *   ownership the backend's `@assetAccessChecker` SpEL actually verifies — a 403 here just means
 *   the caller isn't the issuer or a holder of `assetId`.
 */
@Injectable({ providedIn: 'root' })
export class CorporateActionsService {
  private readonly http = inject(HttpClient);
  private readonly assetsBase = `${environment.apiUrl}/assets`;
  private readonly meBase = `${environment.apiUrl}/me/corporate-actions`;

  // ── Issuer side ──────────────────────────────────────────────────────────────

  listForAsset(assetId: string): Observable<CorporateActionView[]> {
    return this.http.get<CorporateActionView[]>(`${this.assetsBase}/${assetId}/corporate-actions`);
  }

  propose(assetId: string, request: ProposeCorporateActionRequest): Observable<CorporateActionView> {
    return this.http.post<CorporateActionView>(`${this.assetsBase}/${assetId}/corporate-actions`, request);
  }

  /** Withdraws the issuer's own still-PROPOSED action. */
  withdraw(assetId: string, corporateActionId: string): Observable<CorporateActionView> {
    return this.http.post<CorporateActionView>(
      `${this.assetsBase}/${assetId}/corporate-actions/${corporateActionId}/withdraw`, {});
  }

  /** The issuer's attestation that the underlying obligation/cash-leg is ready — not step-up
   *  gated (this app has no step-up UI today; see the corporate-actions plan's confirmed
   *  deferral), but authenticated: the caller must be the asset's issuer. */
  attestSettlement(assetId: string, corporateActionId: string, attestationReference: string): Observable<CorporateActionView> {
    return this.http.post<CorporateActionView>(
      `${this.assetsBase}/${assetId}/corporate-actions/${corporateActionId}/attest-settlement`,
      { attestationReference, acknowledged: true });
  }

  // ── Investor side ────────────────────────────────────────────────────────────

  /** Corporate actions for one asset, scoped to holdings the caller actually has — excludes an
   *  issuer's PROPOSED/REJECTED drafts. */
  listForHolder(assetId: string): Observable<CorporateActionView[]> {
    return this.http.get<CorporateActionView[]>(this.meBase, { params: new HttpParams().set('assetId', assetId) });
  }

  downloadConfirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.meBase}/${corporateActionId}/confirmation`, { responseType: 'blob' });
  }

  downloadIso20022Confirmation(corporateActionId: string): Observable<Blob> {
    return this.http.get(`${this.meBase}/${corporateActionId}/confirmation/iso20022`, { responseType: 'blob' });
  }
}

/** `corporateactions.web.dto.ProposeCorporateActionRequest` — per-type required fields are
 *  enforced server-side (`CorporateActionProposalValidator`), not duplicated here:
 *  - DIVIDEND: amountPerUnit, currency, recordDate, paymentDate
 *  - SPLIT: ratioNumerator, ratioDenominator, recordDate
 *  - CALL: either callScheduleIndex (0-based, into the bond's own AssetBondTerms.callSchedule —
 *    the backend resolves the real callDate/callPrice from there, never trusting client-supplied
 *    values for a scheduled call) or a custom paymentDate + amountPerUnit */
export interface ProposeCorporateActionRequest {
  actionType: CorporateActionType;
  announcementDate?: string;
  recordDate?: string;
  paymentDate?: string;
  amountPerUnit?: number;
  currency?: string;
  ratioNumerator?: number;
  ratioDenominator?: number;
  callScheduleIndex?: number;
  notes?: string;
}
