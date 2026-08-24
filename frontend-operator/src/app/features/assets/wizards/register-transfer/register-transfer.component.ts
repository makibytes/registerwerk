import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, SlicePipe } from '@angular/common';
import { RegisterTransferService } from '../../../../core/api/register-transfer.service';
import { RegisterTransfer } from '../../../../core/models';
import { AuthService } from '../../../../core/auth/auth.service';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Operator view of the §§21/22 eWpG registry-operator handover lifecycle: initiate → export
 * (§20 eWpRV data package) → record on-chain control handover → complete. Previously curl-only
 * despite being the first thing a bank's legal team asks about — how does an exit work.
 */
@Component({
  selector: 'app-register-transfer',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTooltipModule, DatePipe, SlicePipe],
  template: `
    <div class="rt-shell">
      <div class="rt-header">
        <h3 class="rt-title">Register transfer to successor operator (§§21/22 eWpG)</h3>
        <button type="button" mat-raised-button color="primary" (click)="openInitiateDialog()">
          <mat-icon>swap_horiz</mat-icon>
          Initiate Transfer
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else if (transfers.length === 0) {
        <div class="empty-state">
          <mat-icon class="empty-icon">swap_horiz</mat-icon>
          <p>No register transfer has been initiated for this asset.</p>
        </div>
      } @else {
        <div class="rt-table">
          <div class="rt-row header">
            <span>Successor</span>
            <span>Reason</span>
            <span>Status</span>
            <span>Initiated</span>
            <span></span>
          </div>

          @for (t of transfers; track t.id) {
            <div class="rt-row">
              <span>
                {{ t.successorName }}
                @if (t.successorIdentifier) { <span class="dimmed small"><br />{{ t.successorIdentifier }}</span> }
              </span>
              <span class="dimmed small">{{ t.reason }}</span>
              <span class="status-badge" [class]="t.status.toLowerCase()">{{ t.status.replace('_', ' ') }}</span>
              <span class="dimmed">{{ t.initiatedAt | date:'dd MMM yyyy' }}</span>
              <div class="row-actions">
                @if (t.status === 'INITIATED') {
                  <button type="button" mat-stroked-button [disabled]="exporting.has(t.id)" (click)="exportPackage(t)">
                    <mat-icon>download</mat-icon>
                    {{ exporting.has(t.id) ? 'Exporting…' : 'Export' }}
                  </button>
                }
                @if (t.status === 'EXPORTED') {
                  <button type="button" mat-stroked-button color="warn" matTooltip="Requires step-up + a second approver"
                          (click)="openHandoverDialog(t)">
                    <mat-icon>link</mat-icon>
                    Record Handover
                  </button>
                }
                @if (t.status === 'HANDED_OVER') {
                  <button type="button" mat-stroked-button color="primary" matTooltip="Requires step-up + a second approver"
                          (click)="complete(t)">
                    <mat-icon>task_alt</mat-icon>
                    Complete
                  </button>
                }
                @if (t.status !== 'COMPLETED' && t.status !== 'CANCELLED') {
                  <button type="button" mat-icon-button color="warn" matTooltip="Cancel" (click)="openCancelDialog(t)">
                    <mat-icon>block</mat-icon>
                  </button>
                }
                @if (t.onchainTxHash) {
                  <span class="mono small dimmed" [matTooltip]="t.onchainTxHash">{{ t.onchainTxHash | slice:0:10 }}…</span>
                }
              </div>
            </div>
          }
        </div>
      }
    </div>

    <ng-template #initiateDialogTpl>
      <h2 mat-dialog-title>Initiate Register Transfer</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Successor operator name</mat-label>
          <input matInput [(ngModel)]="initiateForm.successorName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Successor identifier (LEI, registration no.)</mat-label>
          <input matInput [(ngModel)]="initiateForm.successorIdentifier" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="initiateForm.reason"
            placeholder="e.g. §22 eWpG — operator can no longer meet statutory requirements"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
                [disabled]="!initiateForm.successorName.trim() || !initiateForm.reason.trim()"
                (click)="submitInitiate()">
          Initiate
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #handoverDialogTpl>
      <h2 mat-dialog-title>Record On-Chain Handover</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Enter the transaction hash of the on-chain control-handover transaction. This step
          requires step-up authentication and a second approver.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Transaction hash</mat-label>
          <input matInput [(ngModel)]="handoverTxHash" placeholder="0x…" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!handoverTxHash.trim()" (click)="submitHandover()">
          Continue to step-up
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #cancelDialogTpl>
      <h2 mat-dialog-title>Cancel Register Transfer</h2>
      <mat-dialog-content style="min-width:400px">
        <mat-form-field appearance="outline" style="width:100%">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" [(ngModel)]="cancelReason"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Back</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!cancelReason.trim()" (click)="submitCancel()">
          Cancel Transfer
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .rt-shell { padding: 1.5rem 0; }
    .rt-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .rt-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .mono { font-family: 'IBM Plex Mono', monospace; }

    .rt-table { display: flex; flex-direction: column; }
    .rt-row {
      display: grid;
      grid-template-columns: 1fr 1fr 130px 120px 260px;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .rt-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
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
    .status-badge.initiated   { background: rgba(148,163,184,.15); color: #94a3b8; }
    .status-badge.exported    { background: rgba(96,165,250,.15); color: #60a5fa; }
    .status-badge.handed_over { background: rgba(245,158,11,.15); color: #f59e0b; }
    .status-badge.completed   { background: rgba(74,222,128,.15); color: #4ade80; }
    .status-badge.cancelled   { background: rgba(248,113,113,.15); color: #f87171; }

    .row-actions { display: flex; justify-content: flex-end; align-items: center; gap: 4px; flex-wrap: wrap; }
  `],
})
export class RegisterTransferComponent implements OnInit {
  @Input() assetId!: string;
  @ViewChild('initiateDialogTpl') initiateDialogTpl!: TemplateRef<unknown>;
  @ViewChild('handoverDialogTpl') handoverDialogTpl!: TemplateRef<unknown>;
  @ViewChild('cancelDialogTpl') cancelDialogTpl!: TemplateRef<unknown>;

  private readonly service = inject(RegisterTransferService);
  private readonly authService = inject(AuthService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  transfers: RegisterTransfer[] = [];
  loading = false;
  exporting = new Set<string>();

  activeTransfer: RegisterTransfer | null = null;
  handoverTxHash = '';
  cancelReason = '';
  initiateForm = { successorName: '', successorIdentifier: '', reason: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.service.listForAsset(this.assetId).subscribe({
      next: (transfers) => {
        this.transfers = transfers;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  openInitiateDialog(): void {
    this.initiateForm = { successorName: '', successorIdentifier: '', reason: '' };
    this.dialog.open(this.initiateDialogTpl, { width: '480px' });
  }

  submitInitiate(): void {
    this.dialog.closeAll();
    const actorId = this.authService.getUserId() ?? '';
    this.service.initiate(
      this.assetId,
      this.initiateForm.successorName.trim(),
      this.initiateForm.successorIdentifier.trim() || undefined,
      this.initiateForm.reason.trim(),
      actorId,
    ).subscribe({
      next: () => {
        this.snackBar.open('Register transfer initiated.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to initiate transfer.', 'Dismiss', { duration: 6000 }),
    });
  }

  exportPackage(transfer: RegisterTransfer): void {
    this.exporting.add(transfer.id);
    this.cdr.markForCheck();
    this.service.export(transfer.id).subscribe({
      next: (json) => {
        const url = URL.createObjectURL(json);
        const link = document.createElement('a');
        link.href = url;
        link.download = `register-transfer-${transfer.id}.json`;
        link.click();
        URL.revokeObjectURL(url);
        this.exporting.delete(transfer.id);
        this.snackBar.open('§20 eWpRV data package exported.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => {
        this.exporting.delete(transfer.id);
        this.cdr.markForCheck();
        this.snackBar.open(err?.error?.message ?? 'Failed to export data package.', 'Dismiss', { duration: 6000 });
      },
    });
  }

  openHandoverDialog(transfer: RegisterTransfer): void {
    this.activeTransfer = transfer;
    this.handoverTxHash = '';
    this.dialog.open(this.handoverDialogTpl, { width: '460px' });
  }

  submitHandover(): void {
    const transfer = this.activeTransfer;
    const txHash = this.handoverTxHash.trim();
    if (!transfer || !txHash) return;
    this.dialog.closeAll();

    const stepUpRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Record on-chain control handover for register transfer to ${transfer.successorName}`,
        action: 'REGISTER_TRANSFER_ONCHAIN_HANDOVER',
      },
      width: '500px',
      disableClose: true,
    });

    stepUpRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.recordOnchainHandover(transfer.id, txHash, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.snackBar.open('On-chain handover recorded.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record handover.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  complete(transfer: RegisterTransfer): void {
    const stepUpRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Complete register transfer to ${transfer.successorName} (§§21/22 eWpG)`,
        action: 'REGISTER_TRANSFER_COMPLETE',
      },
      width: '500px',
      disableClose: true,
    });

    stepUpRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.complete(transfer.id, result.stepUpToken, result.dualControlToken!).subscribe({
        next: () => {
          this.snackBar.open('Register transfer completed.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to complete transfer.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  openCancelDialog(transfer: RegisterTransfer): void {
    this.activeTransfer = transfer;
    this.cancelReason = '';
    this.dialog.open(this.cancelDialogTpl, { width: '460px' });
  }

  submitCancel(): void {
    const transfer = this.activeTransfer;
    const reason = this.cancelReason.trim();
    if (!transfer || !reason) return;
    this.dialog.closeAll();

    this.service.cancel(transfer.id, reason).subscribe({
      next: () => {
        this.snackBar.open('Register transfer cancelled.', 'Dismiss', { duration: 5000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to cancel transfer.', 'Dismiss', { duration: 6000 }),
    });
  }
}
