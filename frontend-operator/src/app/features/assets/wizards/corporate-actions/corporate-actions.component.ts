import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, inject
} from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, DecimalPipe } from '@angular/common';
import { CorporateActionsService } from '../../../../core/api/corporate-actions.service';
import { CorporateAction } from '../../../../core/models';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Operator view of the corporate-action lifecycle for one asset (coupon, dividend, split,
 * redemption, call, …). COUPON/REDEMPTION are still announced automatically by the backend's
 * scheduled jobs; DIVIDEND/SPLIT/CALL arrive here PROPOSED by the issuer and need an operator's
 * approve/reject before they join the same pipeline.
 *
 * Settlement approval is now a two-party, cross-org control (issuer attestation, then one
 * operator confirmation) instead of the old same-org 2x-operator dual control — this screen shows
 * both halves' progress and, if an issuer never attests, an audited operator override. Only
 * `mark-settled` (the manual fallback for standards/types with no automated on-chain adapter,
 * e.g. SPLIT) and `cancel` still require a second approver — they are the actions where a
 * free-text claim becomes register truth with no counterparty on the other side.
 */
@Component({
  selector: 'app-corporate-actions',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [MatButtonModule, MatIconModule, MatDialogModule, MatTooltipModule, DatePipe, DecimalPipe],
  template: `
    <div class="ca-shell">
      <div class="ca-header">
        <h3 class="ca-title">Corporate actions</h3>
        <button type="button" mat-stroked-button (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (actions.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">event_available</mat-icon>
          <p>No corporate actions for this asset yet.</p>
        </div>
      } @else {
        <div class="ca-table">
          <div class="ca-row header">
            <span>Type</span>
            <span>Status</span>
            <span>Payment date</span>
            <span class="right">Amount / unit</span>
            <span>Settlement progress</span>
            <span></span>
          </div>

          @for (a of actions; track a.id) {
            <div class="ca-row">
              <span class="type-badge">{{ a.actionType.replace('_', ' ') }}</span>
              <span class="status-badge" [class]="a.status.toLowerCase()">{{ a.status.replace('_', ' ') }}</span>
              <span class="dimmed">{{ a.paymentDate ? (a.paymentDate | date:'dd MMM yyyy') : '—' }}</span>
              <span class="right mono">
                {{ a.amountPerUnit != null ? (a.amountPerUnit | number:'1.0-8') + ' ' + (a.currency ?? '') : '—' }}
              </span>
              <span class="dimmed">{{ progressLabel(a) }}</span>
              <div class="row-actions">
                @if (a.status === 'PROPOSED') {
                  <button type="button" mat-stroked-button color="primary" [disabled]="busy.has(a.id)"
                          matTooltip="Approve — joins the register pipeline as ANNOUNCED"
                          (click)="approveProposal(a)">
                    <mat-icon>check_circle</mat-icon> Approve
                  </button>
                  <button type="button" mat-stroked-button color="warn" [disabled]="busy.has(a.id)"
                          matTooltip="Reject — terminal, the issuer must submit a fresh proposal"
                          (click)="rejectProposal(a)">
                    <mat-icon>cancel</mat-icon> Reject
                  </button>
                }
                @if (isPreSettlement(a)) {
                  @if (!a.issuerAttestedAt) {
                    <button type="button" mat-stroked-button [disabled]="busy.has(a.id)"
                            matTooltip="Audited escape hatch for an issuer who never logs in to attest"
                            (click)="overrideAttestation(a)">
                      <mat-icon>person_off</mat-icon> Override attestation
                    </button>
                  } @else if (!a.dualControlApproverId) {
                    <button type="button" mat-stroked-button color="warn" [disabled]="busy.has(a.id)"
                            matTooltip="Requires step-up authentication"
                            (click)="confirmSettlement(a)">
                      <mat-icon>gavel</mat-icon> Confirm settlement
                    </button>
                  }
                  <button type="button" mat-icon-button matTooltip="Cancel this corporate action" [disabled]="busy.has(a.id)"
                          (click)="cancel(a)">
                    <mat-icon>block</mat-icon>
                  </button>
                }
                @if (a.status === 'AWAITING_SETTLEMENT') {
                  <button type="button" mat-stroked-button color="warn" [disabled]="busy.has(a.id)"
                          matTooltip="Manual settlement fallback — requires step-up auth + a second approver (Vieraugenprinzip)"
                          (click)="markSettled(a)">
                    <mat-icon>edit_note</mat-icon> Mark settled
                  </button>
                }
                @if (a.status === 'SETTLED' || a.status === 'CLOSED') {
                  <button type="button" mat-icon-button matTooltip="Download confirmation (PDF)" (click)="downloadConfirmation(a)">
                    <mat-icon>picture_as_pdf</mat-icon>
                  </button>
                  <button type="button" mat-icon-button matTooltip="Download ISO 20022-shaped confirmation (XML)"
                          (click)="downloadIso20022Confirmation(a)">
                    <mat-icon>code</mat-icon>
                  </button>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>
  `,
  styles: [`
    :host { display: block; }
    .ca-shell { padding: 1.5rem 0; }
    .ca-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .ca-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', monospace; }
    .right { text-align: right; }

    .ca-table { display: flex; flex-direction: column; }
    .ca-row {
      display: grid;
      grid-template-columns: 130px 150px 120px 140px 1fr 220px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .ca-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }

    .type-badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .6875rem;
      font-weight: 700;
      letter-spacing: .04em;
    }

    .status-badge {
      display: inline-flex;
      align-items: center;
      padding: .125rem .5rem;
      border-radius: 3px;
      font-size: .6875rem;
      font-weight: 700;
      width: fit-content;
    }
    .status-badge.proposed { background: rgba(59,130,246,.15); color: #3b82f6; }
    .status-badge.announced, .status-badge.record_date_set, .status-badge.computed {
      background: rgba(148,163,184,.15); color: #94a3b8;
    }
    .status-badge.awaiting_settlement { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.settled, .status-badge.closed { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.cancelled, .status-badge.rejected { background: rgba(248,113,113,.15); color: #f87171; }

    .row-actions { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: .375rem; }
  `],
})
export class CorporateActionsComponent implements OnInit {
  @Input() assetId!: string;

  private readonly corporateActionsService = inject(CorporateActionsService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  actions: CorporateAction[] = [];
  loading = false;
  busy = new Set<string>();

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.corporateActionsService.listForAsset(this.assetId).subscribe({
      next: (actions) => {
        this.actions = actions;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  /** Non-terminal, past the proposal stage — where the two-party settlement control applies. */
  isPreSettlement(a: CorporateAction): boolean {
    return a.status === 'ANNOUNCED' || a.status === 'RECORD_DATE_SET' || a.status === 'COMPUTED';
  }

  progressLabel(a: CorporateAction): string {
    switch (a.status) {
      case 'PROPOSED': return 'Awaiting operator review';
      case 'REJECTED': return 'Rejected — issuer must resubmit';
      case 'CANCELLED': return 'Cancelled';
      case 'SETTLED':
      case 'CLOSED':
        return a.settlementTxHash ? `${a.settlementTxHash.slice(0, 10)}…` : 'off-chain';
      default:
        if (!a.issuerAttestedAt) return 'Awaiting issuer attestation';
        if (!a.dualControlApproverId) return 'Issuer attested — awaiting operator confirmation';
        return 'Both parties signed off — awaiting settlement dispatch';
    }
  }

  private withStepUp(reason: string, action: string, onToken: (stepUpToken: string) => void): void {
    const dialogRef = this.dialog.open(StepUpDialogComponent, {
      data: { requireDualControl: false, reason, action },
      width: '500px',
      disableClose: true,
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return; // cancelled
      onToken(result.stepUpToken);
    });
  }

  approveProposal(a: CorporateAction): void {
    this.withStepUp(`Approve issuer-proposed ${a.actionType} (${a.id})`, 'CORPORATE_ACTION_PROPOSAL_REVIEW', (token) => {
      this.busy.add(a.id);
      this.corporateActionsService.approveProposal(a.id, token).subscribe({
        next: () => { this.snackBar.open('Proposal approved.', 'Dismiss', { duration: 5000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to approve the proposal.'),
      });
    });
  }

  rejectProposal(a: CorporateAction): void {
    const reason = prompt('Rejection reason (required for audit trail):');
    if (!reason) return;
    this.withStepUp(`Reject issuer-proposed ${a.actionType} (${a.id})`, 'CORPORATE_ACTION_PROPOSAL_REVIEW', (token) => {
      this.busy.add(a.id);
      this.corporateActionsService.rejectProposal(a.id, reason, token).subscribe({
        next: () => { this.snackBar.open('Proposal rejected.', 'Dismiss', { duration: 5000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to reject the proposal.'),
      });
    });
  }

  confirmSettlement(a: CorporateAction): void {
    this.withStepUp(`Confirm settlement of ${a.actionType} corporate action (${a.id})`, 'CORPORATE_ACTION_SETTLEMENT_CONFIRMATION', (token) => {
      this.busy.add(a.id);
      this.corporateActionsService.confirmSettlement(a.id, token).subscribe({
        next: () => { this.snackBar.open('Settlement confirmed.', 'Dismiss', { duration: 6000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to confirm settlement. Check step-up authentication.'),
      });
    });
  }

  overrideAttestation(a: CorporateAction): void {
    const reason = prompt('Reason the issuer could not attest themselves (required for audit trail):');
    if (!reason) return;
    this.withStepUp(`Override issuer attestation for ${a.actionType} (${a.id})`, 'CORPORATE_ACTION_ATTESTATION_OVERRIDE', (token) => {
      this.busy.add(a.id);
      this.corporateActionsService.overrideAttestation(a.id, reason, token).subscribe({
        next: () => { this.snackBar.open('Attestation overridden.', 'Dismiss', { duration: 6000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to override the attestation.'),
      });
    });
  }

  markSettled(a: CorporateAction): void {
    const reference = prompt('Settlement reference (bank transfer id, manual tx hash, etc. — required for audit trail):');
    if (!reference) return;
    const dialogRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Manually mark ${a.actionType} corporate action (${a.id}) as settled`,
        action: 'CORPORATE_ACTION_MANUAL_SETTLEMENT',
      },
      width: '500px',
      disableClose: true,
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.busy.add(a.id);
      this.corporateActionsService.markSettled(a.id, reference, result.stepUpToken, result.dualControlToken).subscribe({
        next: () => { this.snackBar.open('Marked settled.', 'Dismiss', { duration: 6000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to mark settled. Check step-up authentication.'),
      });
    });
  }

  cancel(a: CorporateAction): void {
    const reason = prompt('Cancellation reason (required for audit trail):');
    if (!reason) return;
    const dialogRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Cancel ${a.actionType} corporate action (${a.id})`,
        action: 'CORPORATE_ACTION_CANCELLATION',
      },
      width: '500px',
      disableClose: true,
    });
    dialogRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.busy.add(a.id);
      this.corporateActionsService.cancel(a.id, reason, result.stepUpToken, result.dualControlToken).subscribe({
        next: () => { this.snackBar.open('Corporate action cancelled.', 'Dismiss', { duration: 6000 }); this.busy.delete(a.id); this.load(); },
        error: (err) => this.onActionError(a.id, err, 'Failed to cancel. Check step-up authentication.'),
      });
    });
  }

  downloadConfirmation(action: CorporateAction): void {
    this.corporateActionsService.downloadConfirmation(action.id).subscribe({
      next: (pdf) => this.triggerDownload(pdf, `confirmation-${action.id}.pdf`),
      error: () => this.snackBar.open('Failed to generate the confirmation.', 'Dismiss', { duration: 4000 }),
    });
  }

  downloadIso20022Confirmation(action: CorporateAction): void {
    this.corporateActionsService.downloadIso20022Confirmation(action.id).subscribe({
      next: (xml) => this.triggerDownload(xml, `confirmation-${action.id}.xml`),
      error: () => this.snackBar.open('Failed to generate the ISO 20022 confirmation.', 'Dismiss', { duration: 4000 }),
    });
  }

  private onActionError(id: string, err: unknown, fallback: string): void {
    const message = (err as { error?: { message?: string } })?.error?.message ?? fallback;
    this.snackBar.open(message, 'Dismiss', { duration: 8000 });
    this.busy.delete(id);
    this.cdr.markForCheck();
  }

  private triggerDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }
}
