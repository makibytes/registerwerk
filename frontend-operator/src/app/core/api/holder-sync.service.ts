import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type NomineePoolKind = 'LENDING_MARKET' | 'DVP_ESCROW' | 'DESK' | 'FACILITY';

export interface NomineePoolResponse {
  holderId: string;
  assetId: string;
  walletAddress: string;
  investorId: string;
  nominalAmount: number;
}

/**
 * Register reconciliation actions (T2-18) behind the asset-detail "register not reconciled"
 * banner: register a pool contract as a nominee-pool holder, map a wallet to an investor, and
 * re-run the holder sync.
 */
@Injectable({ providedIn: 'root' })
export class HolderSyncService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/assets`;

  refreshHolders(assetId: string): Observable<{ status: string; message: string }> {
    return this.http.post<{ status: string; message: string }>(`${this.base}/${assetId}/holders/refresh`, {});
  }

  registerNomineePool(assetId: string, walletAddress: string, poolKind: NomineePoolKind,
                      investorId: string | null): Observable<NomineePoolResponse> {
    return this.http.post<NomineePoolResponse>(`${this.base}/${assetId}/nominee-pools`,
      { walletAddress, poolKind, investorId: investorId || null });
  }

  /** A wallet mapping (nominal 0) is a register entry: operator-only, step-up, recorded instruction (T3-13). */
  mapWalletToInvestor(assetId: string, walletAddress: string, investorId: string,
                      instructingParty: string, instructionReference: string, stepUpToken: string): Observable<unknown> {
    return this.http.post(`${this.base}/${assetId}/holders`,
      { investorId, walletAddress, nominalAmount: 0, instructingParty, instructionReference },
      { headers: new HttpHeaders({ Authorization: `Bearer ${stepUpToken}` }) });
  }
}
