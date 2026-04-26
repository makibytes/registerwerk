import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { Observable, interval, switchMap, takeWhile, tap, finalize, Subject, takeUntil } from 'rxjs';
import { environment } from '../../../environments/environment';

export interface TxRecord {
  id: string;
  txHash: string | null;
  status: 'PENDING' | 'SUCCESS' | 'FAILED' | 'TIMEOUT';
  methodName: string;
  chain: string;
  network: string;
  contractAddress: string;
  deploymentId: string;
  assetId: string;
  actorName: string;
  actorRole: string;
  params: Record<string, unknown>;
  gasUsed: number | null;
  blockNumber: number | null;
  errorMessage: string | null;
  createdAt: string;
  completedAt: string | null;
}

export interface TxPage {
  content: TxRecord[];
  totalElements: number;
  totalPages: number;
  number: number;
}

export interface TxSubmissionResponse {
  txId: string;
}

@Injectable({ providedIn: 'root' })
export class TransactionService {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly base = `${environment.apiUrl}/transactions`;

  getTransaction(id: string): Observable<TxRecord> {
    return this.http.get<TxRecord>(`${this.base}/${id}`);
  }

  listTransactions(deploymentId?: string, assetId?: string, page = 0, size = 20): Observable<TxPage> {
    let url = `${this.base}?page=${page}&size=${size}`;
    if (deploymentId) url += `&deploymentId=${deploymentId}`;
    if (assetId) url += `&assetId=${assetId}`;
    return this.http.get<TxPage>(url);
  }

  track(txId: string, label: string): void {
    const pendingRef: MatSnackBarRef<TextOnlySnackBar> = this.snackBar.open(
      `⏳ ${label}: submitting…`, '', { duration: 0 }
    );
    const stop$ = new Subject<void>();

    interval(4_000).pipe(
      switchMap(() => this.getTransaction(txId)),
      tap(tx => {
        if (tx.status !== 'PENDING') {
          stop$.next();
          pendingRef.dismiss();

          if (tx.status === 'SUCCESS') {
            this.snackBar.open(`✅ ${label} confirmed (block ${tx.blockNumber})`, 'OK', { duration: 8000 });
          } else {
            const msg = tx.errorMessage ?? tx.status;
            this.snackBar.open(`❌ ${label} failed: ${msg}`, 'Close', { duration: 0 });
          }
        }
      }),
      takeUntil(stop$),
      finalize(() => stop$.complete())
    ).subscribe({ error: () => { stop$.next(); pendingRef.dismiss(); } });
  }
}
