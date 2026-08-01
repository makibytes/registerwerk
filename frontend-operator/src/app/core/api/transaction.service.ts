import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { MatSnackBar, MatSnackBarRef, TextOnlySnackBar } from '@angular/material/snack-bar';
import { Observable, interval, switchMap, take, tap, finalize, Subject, takeUntil } from 'rxjs';
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

const POLL_INTERVAL_MS = 4_000;
/** 4s x 150 = 10 minutes. Past that a transaction is not "pending", it is stuck. */
const MAX_POLL_ATTEMPTS = 150;

@Injectable({ providedIn: 'root' })
export class TransactionService {
  private readonly http = inject(HttpClient);
  private readonly snackBar = inject(MatSnackBar);
  private readonly base = `${environment.apiUrl}/transactions`;

  /** Transaction IDs currently being polled, so repeat track() calls cannot stack up pollers. */
  private readonly activePolls = new Set<string>();

  getTransaction(id: string): Observable<TxRecord> {
    return this.http.get<TxRecord>(`${this.base}/${id}`);
  }

  listTransactions(deploymentId?: string, assetId?: string, page = 0, size = 20): Observable<TxPage> {
    let url = `${this.base}?page=${page}&size=${size}`;
    if (deploymentId) url += `&deploymentId=${deploymentId}`;
    if (assetId) url += `&assetId=${assetId}`;
    return this.http.get<TxPage>(url);
  }

  /**
   * Tracks a submitted transaction by polling every 4 seconds until it completes.
   * Shows a persistent "pending" snackbar and replaces it with success/failure on completion.
   *
   * <p>Deliberately not bound to the calling component's lifecycle: the user is expected to submit
   * a transaction and navigate away, and the confirmation toast should still arrive. What it IS
   * bounded by is {@link MAX_POLL_ATTEMPTS} and the {@link activePolls} de-duplication — without
   * those, a transaction that never left PENDING (stalled chain, indexer down) polled every 4s for
   * the life of the tab, and each of the call sites could start another poller for the same
   * transaction, so they accumulated without limit.
   */
  track(txId: string, label: string): void {
    if (this.activePolls.has(txId)) return;

    const pendingRef: MatSnackBarRef<TextOnlySnackBar> = this.snackBar.open(
      `⏳ ${label}: submitting…`, '', { duration: 0, panelClass: 'tx-pending' }
    );
    const stop$ = new Subject<void>();
    this.activePolls.add(txId);

    interval(POLL_INTERVAL_MS).pipe(
      take(MAX_POLL_ATTEMPTS),
      switchMap(() => this.getTransaction(txId)),
      tap(tx => {
        if (tx.status !== 'PENDING') {
          stop$.next();
          pendingRef.dismiss();

          if (tx.status === 'SUCCESS') {
            this.snackBar.open(`✅ ${label} confirmed (block ${tx.blockNumber})`, 'OK', {
              duration: 8000, panelClass: 'tx-success'
            });
          } else {
            const msg = tx.errorMessage ?? tx.status;
            this.snackBar.open(`❌ ${label} failed: ${msg}`, 'Close', {
              duration: 0, panelClass: 'tx-error'
            });
          }
        }
      }),
      takeUntil(stop$),
      finalize(() => {
        stop$.complete();
        this.activePolls.delete(txId);
      })
    ).subscribe({
      // take() completing without the status ever leaving PENDING means we gave up watching, not
      // that the transaction failed — say so rather than leaving a spinner up forever.
      complete: () => {
        if (pendingRef.instance) {
          pendingRef.dismiss();
          this.snackBar.open(
            `⏱️ ${label}: still pending after ${(POLL_INTERVAL_MS * MAX_POLL_ATTEMPTS) / 60_000} minutes — check the transaction list for the final status.`,
            'OK', { duration: 10000, panelClass: 'tx-pending' }
          );
        }
      },
      error: () => { stop$.next(); pendingRef.dismiss(); },
    });
  }
}
