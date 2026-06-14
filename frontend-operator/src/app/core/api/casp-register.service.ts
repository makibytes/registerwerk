import { Injectable, inject } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';

export type CaspAuthorizationStatus =
  | 'AUTHORIZED'
  | 'TRANSITIONAL'
  | 'NOT_AUTHORIZED'
  | 'REVOKED';

export interface CaspImportResult {
  created: number;
  updated: number;
  failed: number;
  errors: string[];
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

  /** Idempotent upsert keyed by vaspDid. */
  upsert(entry: CaspAuthorization): Observable<CaspAuthorization> {
    return this.http.put<CaspAuthorization>(this.base, entry);
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}`);
  }

  /** Bulk CSV import (transformed ESMA register export); raw CSV text body. */
  importCsv(csv: string): Observable<CaspImportResult> {
    return this.http.post<CaspImportResult>(`${this.base}/import`, csv, {
      headers: new HttpHeaders({ 'Content-Type': 'text/csv' }),
    });
  }
}
