import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { downloadBlob } from '../../../../core/utils/download.util';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { DatePipe, SlicePipe } from '@angular/common';
import { RegisterTransferService } from '../../../../core/api/register-transfer.service';
import { AssetDeployment, RegisterTransfer } from '../../../../core/models';
import { AssetService } from '../../../../core/api/asset.service';
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
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatCheckboxModule, MatTooltipModule, DatePipe, SlicePipe],
  template: `
    <div class="rt-shell">
      <div class="rt-header">
        <h3 class="rt-title">Register transfer to successor operator (§§21/22 eWpG)</h3>
        <button type="button" mat-raised-button color="primary" (click)="openInitiateDialog()">
          <mat-icon>swap_horiz</mat-icon>
          Initiate Transfer
        </button>
      </div>

      @if (frozenTransfer; as ft) {
        <div class="rt-freeze" role="status">
          <mat-icon>lock</mat-icon>
          <div>
            <strong>Register frozen (TRANSFER_PENDING)</strong> for the handover to {{ ft.successorName }}.
            Trading, mint/burn/forced operations, corporate-action processing and register edits are refused
            until the transfer is completed or cancelled. If the register changes anyway (holder sync),
            completion is refused and you must re-export.
          </div>
        </div>
      }

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
            <div class="rt-entry">
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
            @if (t.registerContentHash || (t.onchainHandovers?.length ?? 0) > 0) {
              <div class="rt-details">
                @if (t.registerContentHash) {
                  <span class="dimmed small" matTooltip="Hash over the register content only (no export timestamp); re-checked at completion">
                    Register content hash <span class="mono">{{ t.registerContentHash | slice:0:14 }}…</span>
                  </span>
                }
                @for (h of t.onchainHandovers ?? []; track h.deploymentId) {
                  <span class="rt-handover" [class.verified]="h.verified">
                    <mat-icon [matTooltip]="h.verified ? 'Verified on-chain (' + h.method + ')' : 'Operator-attested, not chain-verified'">{{ h.verified ? 'verified' : 'assignment_turned_in' }}</mat-icon>
                    {{ h.chain }} <span class="mono">{{ h.txHash | slice:0:10 }}…</span>
                    <span class="dimmed small">{{ h.verified ? 'verified' : 'attested' }}</span>
                  </span>
                }
              </div>
            }
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
          <mat-label>Successor on-chain address (EVM registry/owner)</mat-label>
          <input matInput [(ngModel)]="initiateForm.successorOnchainAddress" placeholder="0x… (needed to verify EVM deployments)" />
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
          Record the on-chain control handover of one deployment. EVM deployments are verified against the
          chain (registry()/owner() must equal the successor address); other chains need your explicit
          attestation. The transfer becomes HANDED_OVER once every deployment is recorded. This step
          requires step-up authentication and a second approver.
        </p>
        @if (deployments.length > 0) {
          <mat-form-field appearance="outline">
            <mat-label>Deployment</mat-label>
            <mat-select [(ngModel)]="handoverDeploymentId">
              @for (d of deployments; track d.id) {
                <mat-option [value]="d.id">{{ d.chain }} {{ d.network }} - {{ d.contractAddress ?? 'n/a' }}{{ isHandedOver(d.id) ? ' (recorded)' : '' }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        }
        @if (selectedDeploymentIsNonEvm) {
          <mat-checkbox [(ngModel)]="handoverAttested">
            I attest that control of this deployment has been handed to the successor (cannot be verified automatically yet).
          </mat-checkbox>
        }
        <mat-form-field appearance="outline">
          <mat-label>Transaction hash</mat-label>
          <input matInput [(ngModel)]="handoverTxHash" placeholder="0x…" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn" [disabled]="!handoverTxHash.trim() || (selectedDeploymentIsNonEvm && !handoverAttested)" (click)="submitHandover()">
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
    .status-badge.initiated   { background: var(--rw-neutral-bg); color: var(--rw-neutral-fg); }
    .status-badge.exported    { background: var(--rw-draft-bg); color: var(--rw-draft-fg); }
    .status-badge.handed_over { background: var(--rw-pending-bg); color: var(--rw-pending-fg); }
    .status-badge.completed   { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
    .status-badge.cancelled   { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }

    .rt-entry { border-bottom: 1px solid var(--rw-border); }
    .rt-entry .rt-row { border-bottom: none; }
    .rt-details { display: flex; flex-wrap: wrap; gap: .75rem 1.25rem; align-items: center; padding: 0 .5rem .625rem; font-size: .75rem; }
    .rt-handover { display: inline-flex; align-items: center; gap: 4px; color: var(--rw-text-secondary); }
    .rt-handover mat-icon { font-size: 16px; width: 16px; height: 16px; }
    .rt-handover.verified mat-icon { color: var(--rw-approved-fg); }
    .rt-freeze { display: flex; gap: .75rem; align-items: flex-start; padding: .75rem 1rem; margin-bottom: 1rem; border-radius: 6px;
      background: var(--rw-pending-bg); color: var(--rw-pending-fg); font-size: .8125rem; }
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
  private readonly assetService = inject(AssetService);

  transfers: RegisterTransfer[] = [];
  loading = false;
  exporting = new Set<string>();

  activeTransfer: RegisterTransfer | null = null;
  handoverTxHash = '';
  handoverDeploymentId = '';
  handoverAttested = false;
  deployments: AssetDeployment[] = [];
  cancelReason = '';
  initiateForm = { successorName: '', successorIdentifier: '', successorOnchainAddress: '', reason: '' };

  ngOnInit(): void {
    this.load();
    this.assetService.getDeployments(this.assetId).subscribe({
      next: (d) => { this.deployments = d.filter((x) => x.deploymentStatus === 'CONFIRMED'); this.cdr.markForCheck(); },
      error: () => { /* the handover dialog then falls back to the single-deployment form */ },
    });
  }

  /** The transfer currently freezing the register (exported, not yet completed/cancelled). */
  get frozenTransfer(): RegisterTransfer | undefined {
    return this.transfers.find((t) => t.status === 'EXPORTED' || t.status === 'HANDED_OVER');
  }

  private static readonly NON_EVM = ['SOLANA', 'STARKNET', 'STELLAR', 'CANTON'];

  get selectedDeploymentIsNonEvm(): boolean {
    const d = this.deployments.find((x) => x.id === this.handoverDeploymentId);
    return !!d && RegisterTransferComponent.NON_EVM.includes(d.chain);
  }

  isHandedOver(deploymentId: string): boolean {
    return !!this.activeTransfer?.onchainHandovers?.some((h) => h.deploymentId === deploymentId);
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
    this.initiateForm = { successorName: '', successorIdentifier: '', successorOnchainAddress: '', reason: '' };
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
      this.initiateForm.successorOnchainAddress.trim() || undefined,
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
        downloadBlob(json, `register-transfer-${transfer.id}.json`);
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
    this.handoverAttested = false;
    const pending = this.deployments.find((d) => !this.isHandedOver(d.id));
    this.handoverDeploymentId = (pending ?? this.deployments[0])?.id ?? '';
    this.dialog.open(this.handoverDialogTpl, { width: '460px' });
  }

  submitHandover(): void {
    const transfer = this.activeTransfer;
    const txHash = this.handoverTxHash.trim();
    const deploymentId = this.handoverDeploymentId;
    const attested = this.handoverAttested;
    if (!transfer || !txHash) return;
    this.dialog.closeAll();

    const handoverBody = { txHash, deploymentId: deploymentId || undefined, attested: attested || undefined };
    const stepUpRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Record on-chain control handover for register transfer to ${transfer.successorName}`,
        action: 'REGISTER_TRANSFER_ONCHAIN_HANDOVER',
        target: `POST /api/v1/register-transfers/${transfer.id}/onchain-handover`,
        targetBody: handoverBody,
      },
      width: '500px',
      disableClose: true,
    });

    stepUpRef.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.recordOnchainHandover(transfer.id, txHash, result.stepUpToken, result.dualControlToken!, {
        deploymentId: deploymentId || undefined,
        attested: attested || undefined,
      }).subscribe({
        next: () => {
          this.snackBar.open('On-chain handover recorded.', 'Dismiss', { duration: 5000 });
          this.load();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record handover.', 'Dismiss', { duration: 10000 }),
      });
    });
  }

  complete(transfer: RegisterTransfer): void {
    const stepUpRef = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Complete register transfer to ${transfer.successorName} (§§21/22 eWpG)`,
        action: 'REGISTER_TRANSFER_COMPLETE',
        target: `POST /api/v1/register-transfers/${transfer.id}/complete`,
        targetBody: {},
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
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to complete transfer.', 'Dismiss', { duration: 12000 }),
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
