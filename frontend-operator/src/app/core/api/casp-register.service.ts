import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';
import { environment } from '../../../environments/environment';

export type CaspAuthorizationStatus =
  | 'AUTHORIZED'
  | 'TRANSITIONAL'
  | 'NOT_AUTHORIZED'
  | 'REVOKED'
  /** A third-country CASP reviewed by compliance; `validUntil` is required. */
  | 'THIRD_COUNTRY_REVIEWED';

export interface CaspImportResult {
  created: number;
  updated: number;
  statusChanged: number;
  failed: number;
  errors: string[];
  /** false for a preview: nothing was written. */
  committed: boolean;
  /** Binds the commit to exactly this previewed diff. */
  diffDigest: string;
}

export interface CaspAuthorization {
  id?: string;
  vaspDid: string;
  legalName: string;
  lei?: string | null;
  homeMemberState?: string | null;
  status: CaspAuthorizationStatus;
  authorizationId?: string | null;
  validFrom?: string | null;
  validUntil?: string | null;
  source?: string | null;
  notes?: string | null;
}

/**
 * Counterparty CASP authorization register (MiCA Reg (EU) 2023/1114).
 * Entries mirror the ESMA / NCA registers; the backend uses them to block
 * Travel Rule transfers to unauthorized counterparties after 1 July 2026.
 */
@Injectable({ providedIn: 'root' })
export class CaspRegisterService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/compliance/casp-register`;

  list(): Observable<CaspAuthorization[]> {
    return this.http.get<CaspAuthorization[]>(this.base);
  }

  /** Idempotent upsert keyed by vaspDid. Step-up + second approver (`CASP_REGISTER_EDIT`). */
  upsert(entry: CaspAuthorization, tokens: DualControlTokens): Observable<CaspAuthorization> {
    return this.http.put<CaspAuthorization>(this.base, entry, { headers: dualControlHeaders(tokens) });
  }

  /** Step-up + second approver (`CASP_REGISTER_DELETE`). */
  delete(id: string, tokens: DualControlTokens): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`, { headers: dualControlHeaders(tokens) });
  }

  /** Step 1 of a bulk CSV import: dry run that returns the diff and its digest; nothing is written. */
  previewImport(csv: string): Observable<CaspImportResult> {
    return this.http.post<CaspImportResult>(`${this.base}/import/preview`, csv, {
      headers: new HttpHeaders({ 'Content-Type': 'text/csv' }),
    });
  }

  /** Step 2: commits the previewed CSV (`CASP_REGISTER_IMPORT`, step-up + second approver bound to this URL incl. the digest). */
  importCsv(csv: string, diffDigest: string, tokens: DualControlTokens): Observable<CaspImportResult> {
    return this.http.post<CaspImportResult>(`${this.base}/import?diffDigest=${encodeURIComponent(diffDigest)}`, csv, {
      headers: dualControlHeaders(tokens).set('Content-Type', 'text/csv'),
    });
  }
}
