import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { PageHeaderComponent } from '@registerwerk/ui';
import { ChainService } from '../../core/api/chain.service';
import {
  EVM_OUTBOX_CANCEL, EVM_OUTBOX_REPRICE, OutboxEntry, OutboxService,
} from '../../core/api/outbox.service';
import { ChainConfig } from '../../core/models';
import { StepUpDialogComponent, StepUpDialogResult } from '../../shared/components/step-up/step-up-dialog.component';

type OutboxAction = 'cancel' | 'reprice';

/**
 * Stuck durable-outbox queue (P4B-4): payloads the registry signed but no node has mined. Cancel
 * (0-value self-send at the same nonce, the operation will NOT execute) and re-price (same
 * operation, higher fee) each need a reason of at least 10 characters, step-up and a second
 * approver. A PREPARED row was never seen by a node, a BROADCAST row may still be mined.
 */
@Component({
  selector: 'app-outbox-stuck',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe, RouterLink, FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule,
    MatIconModule, MatInputModule, MatSelectModule, MatTooltipModule, PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Stuck outbox"
      subtitle="Signed EVM transactions that no node has mined. A payload that is only broadcast may still execute.">
      <a mat-stroked-button routerLink="/transactions"><mat-icon>receipt_long</mat-icon> Transactions</a>
    </app-page-header>

    <mat-form-field appearance="outline" class="chain-select">
      <mat-label>Chain</mat-label>
      <mat-select [ngModel]="chainId" (ngModelChange)="onChain($event)" data-testid="outbox-chain">
        @for (c of chains; track c.id) {
          <mat-option [value]="c.id">{{ c.displayName }} ({{ c.identifier }})</mat-option>
        }
      </mat-select>
    </mat-form-field>

    @if (loadError) {
      <p class="error" role="alert">Could not load the outbox: {{ loadError }}</p>
    } @else if (loading) {
      <p class="hint">Loading…</p>
    } @else if (chainId && entries.length === 0) {
      <p class="hint" data-testid="outbox-empty">No stuck payloads on this chain.</p>
    }

    @if (entries.length > 0) {
      <div class="table-wrap">
        <table class="outbox-table">
          <thead>
            <tr>
              <th>State</th><th>Operation</th><th>Signer / nonce</th><th>Age</th><th>Last error</th><th>Fees</th><th></th>
            </tr>
          </thead>
          <tbody>
            @for (e of entries; track e.id) {
              <tr [attr.data-testid]="'outbox-row-' + e.id">
                <td>
                  <span class="chip" [class.chip-warn]="e.status === 'PREPARED'">
                    {{ e.status === 'PREPARED' ? 'Not broadcast' : 'Broadcast, unmined' }}
                  </span>
                  @if (e.kind !== 'OPERATION') { <span class="chip">{{ e.kind === 'CANCEL' ? 'Cancel' : 'Re-price' }}</span> }
                  @if (e.regulatory) {
                    <span class="chip chip-reg" matTooltip="Regulatory operation: never replaced automatically">Regulatory</span>
                  }
                  @if (e.transactionStatus === 'TIMEOUT') {
                    <div class="sub">Awaiting chain — may still execute</div>
                  }
                </td>
                <td>
                  <div>{{ e.methodName ?? '—' }}</div>
                  <div class="mono sub">{{ e.contractAddress ?? '' }}</div>
                  @if (e.txHash) { <div class="mono sub">{{ e.txHash }}</div> }
                </td>
                <td><div class="mono">{{ e.senderAddress }}</div><div class="sub">nonce {{ e.nonce }}</div></td>
                <td>{{ formatAge(e.ageSeconds) }}<div class="sub">{{ e.attemptCount }} attempts · {{ e.rebroadcastCount }} re-broadcasts</div></td>
                <td>
                  <div>{{ e.lastErrorClass ?? '—' }}</div>
                  <div class="sub err">{{ e.lastError }}</div>
                  @if (e.firstFailedAt) { <div class="sub">since {{ e.firstFailedAt | date:'short' }}</div> }
                </td>
                <td class="mono">{{ e.maxFeePerGasWei ?? '—' }}<div class="sub">tip {{ e.maxPriorityFeePerGasWei ?? '—' }}</div></td>
                <td class="actions">
                  <button type="button" mat-stroked-button (click)="open(e, 'reprice')"
                          [disabled]="e.status !== 'PREPARED' && e.status !== 'BROADCAST'"
                          matTooltip="Re-sign the same operation at the same nonce with a higher fee">
                    <mat-icon>bolt</mat-icon> Re-price
                  </button>
                  <button type="button" mat-stroked-button color="warn" (click)="open(e, 'cancel')"
                          [disabled]="e.status !== 'PREPARED' && e.status !== 'BROADCAST'"
                          matTooltip="Replace with a 0-value self-send: the operation will not execute">
                    <mat-icon>cancel</mat-icon> Cancel
                  </button>
                </td>
              </tr>
            }
          </tbody>
        </table>
      </div>
    }

    <ng-template #reasonTpl>
      <h2 mat-dialog-title>{{ action === 'cancel' ? 'Cancel stuck transaction' : 'Re-price stuck transaction' }}</h2>
      <mat-dialog-content class="reason-content">
        @if (selected) {
          <p class="sub">
            {{ selected.methodName ?? 'transaction' }}, nonce {{ selected.nonce }} of
            <span class="mono">{{ selected.senderAddress }}</span>.
            @if (action === 'cancel') {
              The original operation will NOT execute (unless it is mined before the replacement).
              @if (selected.regulatory) { This is a regulatory operation; cancelling it means the correction is not carried out. }
            } @else {
              The same operation is re-signed with a fee at least 15% higher; the fee ceiling of the chain still applies.
            }
          </p>
        }
        <mat-form-field appearance="outline">
          <mat-label>Reason (min. 10 characters, recorded in the audit trail)</mat-label>
          <textarea matInput rows="3" maxlength="500" [(ngModel)]="reason" data-testid="outbox-reason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Back</button>
        <button type="button" mat-raised-button color="primary" [disabled]="reason.trim().length < 10"
                (click)="confirm()" data-testid="outbox-continue">
          Continue to approval
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .chain-select { min-width: 320px; max-width: 100%; }
    .hint { color: var(--rw-text-secondary); font-size: 13px; }
    .error { color: var(--rw-text-danger); font-size: 13px; }
    .table-wrap { overflow-x: auto; }
    .outbox-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .outbox-table th { text-align: left; font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px;
      color: var(--rw-text-secondary); padding: 8px; border-bottom: 1px solid var(--rw-border); }
    .outbox-table td { vertical-align: top; padding: 10px 8px; border-bottom: 1px solid var(--rw-border-subtle); }
    .mono { font-family: 'IBM Plex Mono', monospace; font-size: 12px; overflow-wrap: anywhere; }
    .sub { font-size: 12px; color: var(--rw-text-secondary); overflow-wrap: anywhere; }
    .err { color: var(--rw-text-danger); }
    .chip { display: inline-block; padding: 2px 8px; margin: 0 4px 4px 0; border-radius: 12px; font-size: 11px;
      font-weight: 600; background: var(--rw-border-subtle); color: var(--rw-text-secondary); }
    .chip-warn { background: var(--rw-pending-bg); color: var(--rw-pending-fg); }
    .chip-reg { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
    .actions { white-space: nowrap; display: flex; gap: 6px; }
    .reason-content { display: flex; flex-direction: column; gap: 8px; width: min(480px, 80vw); }
  `],
})
export class OutboxStuckComponent implements OnInit {
  @ViewChild('reasonTpl') reasonTpl!: TemplateRef<unknown>;

  private readonly chainService = inject(ChainService);
  private readonly outbox = inject(OutboxService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  chains: ChainConfig[] = [];
  chainId = '';
  entries: OutboxEntry[] = [];
  loading = false;
  loadError = '';
  selected: OutboxEntry | null = null;
  action: OutboxAction = 'cancel';
  reason = '';

  ngOnInit(): void {
    this.chainService.listChains().subscribe({
      next: (chains) => {
        this.chains = chains.filter(c => c.chainType === 'EVM');
        if (this.chains.length > 0) this.onChain(this.chains[0].id);
        this.cdr.markForCheck();
      },
      error: () => { this.loadError = 'chains unavailable'; this.cdr.markForCheck(); },
    });
  }

  onChain(id: string): void {
    this.chainId = id;
    this.load();
  }

  load(): void {
    if (!this.chainId) return;
    this.loading = true;
    this.loadError = '';
    this.cdr.markForCheck();
    this.outbox.listStuck(this.chainId).subscribe({
      next: (entries) => { this.entries = entries; this.loading = false; this.cdr.markForCheck(); },
      error: (err) => {
        this.entries = [];
        this.loading = false;
        this.loadError = err?.error?.message ?? 'request failed';
        this.cdr.markForCheck();
      },
    });
  }

  formatAge(seconds: number): string {
    if (seconds < 90) return `${seconds}s`;
    if (seconds < 5400) return `${Math.round(seconds / 60)} min`;
    if (seconds < 172800) return `${Math.round(seconds / 3600)} h`;
    return `${Math.round(seconds / 86400)} d`;
  }

  open(entry: OutboxEntry, action: OutboxAction): void {
    this.selected = entry;
    this.action = action;
    this.reason = '';
    this.dialog.open(this.reasonTpl, { width: '540px' });
  }

  confirm(): void {
    const entry = this.selected;
    const reason = this.reason.trim();
    if (!entry || reason.length < 10) return;
    const action = this.action;
    this.dialog.closeAll();
    const stepUpAction = action === 'cancel' ? EVM_OUTBOX_CANCEL : EVM_OUTBOX_REPRICE;
    this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `${action === 'cancel' ? 'Cancel' : 'Re-price'} outbox transaction (nonce ${entry.nonce}, ${entry.methodName ?? 'transaction'})`,
        action: stepUpAction,
        target: `POST /api/v1/admin/chains/${entry.chainConfigId}/outbox/${entry.id}/${action === 'cancel' ? 'cancel' : 'reprice'}`,
        targetBody: { reason },
      },
      width: '500px',
      disableClose: true,
    }).afterClosed().subscribe((result: StepUpDialogResult | undefined) => {
      if (!result?.stepUpToken || !result.dualControlToken) return;
      const tokens = { stepUpToken: result.stepUpToken, dualControlToken: result.dualControlToken };
      const call = action === 'cancel'
        ? this.outbox.cancel(entry.chainConfigId, entry.id, reason, tokens)
        : this.outbox.reprice(entry.chainConfigId, entry.id, reason, tokens);
      call.subscribe({
        next: () => {
          this.snackBar.open(action === 'cancel' ? 'Cancel transaction submitted' : 'Re-priced transaction submitted', 'OK', { duration: 4000 });
          this.load();
        },
        error: (err) => {
          this.snackBar.open(err?.error?.message ?? 'The action was refused.', 'Dismiss', { duration: 7000 });
          this.cdr.markForCheck();
        },
      });
    });
  }
}
