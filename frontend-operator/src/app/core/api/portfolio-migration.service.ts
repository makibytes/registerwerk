import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { PortfolioMigrationRequest } from '../models';

/**
 * Investor-side portfolio migration — wraps `registertransfer.web.PortfolioMigrationController`,
 * which had no frontend caller at all. Records the handover of one investor's one holding to a
 * successor registrar (standalone request, or as part of customer offboarding); the on-chain
 * transfer itself uses the existing token-admin tooling.
 */
@Injectable({ providedIn: 'root' })
export class PortfolioMigrationService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/portfolio-migrations`;

  listForInvestor(investorEntityId: string): Observable<PortfolioMigrationRequest[]> {
    return this.http.get<PortfolioMigrationRequest[]>(`${this.base}/investors/${investorEntityId}`);
  }

  initiate(holderId: string, reason: string): Observable<PortfolioMigrationRequest> {
    return this.http.post<PortfolioMigrationRequest>(this.base, { holderId, reason });
  }

  setDestination(migrationId: string, destinationRegistrarName: string | undefined, destinationRegistrarIdentifier: string | undefined, destinationWalletAddress: string): Observable<PortfolioMigrationRequest> {
    return this.http.put<PortfolioMigrationRequest>(`${this.base}/${migrationId}/destination`, {
      destinationRegistrarName, destinationRegistrarIdentifier, destinationWalletAddress,
    });
  }

  export(migrationId: string): Observable<Blob> {
    return this.http.post(`${this.base}/${migrationId}/export`, {}, { responseType: 'blob' });
  }

  recordOnchainTransfer(migrationId: string, txHash: string, stepUpToken: string, dualControlToken: string): Observable<PortfolioMigrationRequest> {
    const headers = new HttpHeaders({
      Authorization: `Bearer ${stepUpToken}`,
      'X-Dual-Control-Token': dualControlToken,
    });
    return this.http.post<PortfolioMigrationRequest>(`${this.base}/${migrationId}/onchain-transfer`, { txHash }, { headers });
  }

  complete(migrationId: string): Observable<PortfolioMigrationRequest> {
    return this.http.post<PortfolioMigrationRequest>(`${this.base}/${migrationId}/complete`, {});
  }

  cancel(migrationId: string, reason: string): Observable<PortfolioMigrationRequest> {
    return this.http.post<PortfolioMigrationRequest>(`${this.base}/${migrationId}/cancel`, { reason });
  }
}
