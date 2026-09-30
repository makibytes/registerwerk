import { Injectable, inject } from '@angular/core';
import { HttpClient } from '@angular/common/http';
import { Observable } from 'rxjs';
import { environment } from '../../../environments/environment';
import { DualControlTokens, dualControlHeaders } from './dual-control-headers';

/** `@RequiresStepUp` reasons of the outbox recovery actions (P4B-4). */
export const EVM_OUTBOX_CANCEL = 'EVM_OUTBOX_CANCEL';
export const EVM_OUTBOX_REPRICE = 'EVM_OUTBOX_REPRICE';

/** One stuck durable-outbox payload (backend `OutboxRecoveryService.OutboxEntry`). */
export interface OutboxEntry {
  id: string;
  chainConfigId: string;
  senderAddress: string;
  nonce: number | string;
  txHash: string | null;
  methodName: string | null;
  contractAddress: string | null;
  status: 'PREPARED' | 'BROADCAST' | 'SUPERSEDED' | 'ABANDONED' | string;
  kind: 'OPERATION' | 'REPRICE' | 'CANCEL' | string;
  attemptCount: number;
  lastError: string | null;
  lastErrorClass: string | null;
  createdAt: string;
  firstFailedAt: string | null;
  nextAttemptAt: string | null;
  broadcastAt: string | null;
  ageSeconds: number;
  replacesTxHash: string | null;
  supersededByTxHash: string | null;
  transactionStatus: string | null;
  /** Regulatory operation (forced transfer, freeze, burn, ...): never auto-replaced. */
  regulatory: boolean;
  autoRepriceAllowed: boolean;
  maxFeePerGasWei: number | string | null;
  maxPriorityFeePerGasWei: number | string | null;
  rebroadcastCount: number;
}

@Injectable({ providedIn: 'root' })
export class OutboxService {
  private readonly http = inject(HttpClient);
  private readonly base = `${environment.apiUrl}/admin/chains`;

  listStuck(chainConfigId: string): Observable<OutboxEntry[]> {
    return this.http.get<OutboxEntry[]>(`${this.base}/${chainConfigId}/outbox/stuck`);
  }

  /** Replaces the payload with a 0-value self-send at the same nonce: the operation will NOT execute. */
  cancel(chainConfigId: string, id: string, reason: string, tokens: DualControlTokens): Observable<OutboxEntry> {
    return this.http.post<OutboxEntry>(`${this.base}/${chainConfigId}/outbox/${id}/cancel`, { reason },
      { headers: dualControlHeaders(tokens) });
  }

  /** Re-signs the same operation at the same nonce with a higher fee. */
  reprice(chainConfigId: string, id: string, reason: string, tokens: DualControlTokens): Observable<OutboxEntry> {
    return this.http.post<OutboxEntry>(`${this.base}/${chainConfigId}/outbox/${id}/reprice`, { reason },
      { headers: dualControlHeaders(tokens) });
  }
}
