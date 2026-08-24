import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Erc3643Service } from '../../../../core/api/erc3643.service';
import { TransactionService } from '../../../../core/api/transaction.service';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Bulk T-REX/ERC-3643 operations via CSV paste — `erc3643.service.ts` already had
 * `batchForcedTransfer`/`batchMint`/`batchBurn` methods with zero callers anywhere in the
 * operator portal, so a bank onboarding thousands of holders had to do one-record-at-a-time
 * clicks through the single-item Compliance tab forms.
 */
@Component({
  selector: 'app-bulk-erc3643-ops',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTabsModule],
  template: `
    <div class="bulk-shell">
      <p class="dimmed small">
        Paste one row per line. Whitespace around commas is ignored; blank lines are skipped.
      </p>

      <mat-tab-group animationDuration="150ms">
        <mat-tab label="Batch Mint">
          <div class="bulk-form">
            <p class="dimmed small">Format: <code>address,amount</code> — one holder per line.</p>
            <mat-form-field appearance="outline">
              <mat-label>CSV rows</mat-label>
              <textarea matInput rows="8" [(ngModel)]="mintCsv" placeholder="0xabc...,1000&#10;0xdef...,2500"></textarea>
            </mat-form-field>
            <div class="preview">{{ parseAddressAmount(mintCsv).length }} row(s) parsed</div>
            <button type="button" mat-raised-button color="primary"
                    [disabled]="parseAddressAmount(mintCsv).length === 0"
                    (click)="submitBatchMint()">
              <mat-icon>add_circle</mat-icon>
              Batch Mint {{ parseAddressAmount(mintCsv).length }} holder(s)
            </button>
          </div>
        </mat-tab>

        <mat-tab label="Batch Forced Transfer">
          <div class="bulk-form">
            <p class="dimmed small">Format: <code>from,to,amount</code> — one transfer per line.</p>
            <mat-form-field appearance="outline">
              <mat-label>CSV rows</mat-label>
              <textarea matInput rows="8" [(ngModel)]="transferCsv" placeholder="0xfrom...,0xto...,1000"></textarea>
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Legal basis</mat-label>
              <input matInput [(ngModel)]="transferLegalBasis" placeholder="e.g. BaFin Bescheid Az. 2025-001" />
            </mat-form-field>
            <div class="preview">{{ parseFromToAmount(transferCsv).length }} row(s) parsed</div>
            <button type="button" mat-raised-button color="warn"
                    [disabled]="parseFromToAmount(transferCsv).length === 0"
                    (click)="submitBatchForcedTransfer()">
              <mat-icon>gavel</mat-icon>
              Batch Forced Transfer {{ parseFromToAmount(transferCsv).length }} row(s) (step-up + 4-eyes)
            </button>
          </div>
        </mat-tab>

        <mat-tab label="Batch Burn">
          <div class="bulk-form">
            <p class="dimmed small">Format: <code>address,amount</code> — one holder per line.</p>
            <mat-form-field appearance="outline">
              <mat-label>CSV rows</mat-label>
              <textarea matInput rows="8" [(ngModel)]="burnCsv" placeholder="0xabc...,1000"></textarea>
            </mat-form-field>
            <div class="preview">{{ parseAddressAmount(burnCsv).length }} row(s) parsed</div>
            <button type="button" mat-raised-button color="warn"
                    [disabled]="parseAddressAmount(burnCsv).length === 0"
                    (click)="submitBatchBurn()">
              <mat-icon>local_fire_department</mat-icon>
              Batch Burn {{ parseAddressAmount(burnCsv).length }} holder(s) (step-up + 4-eyes)
            </button>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .bulk-shell { padding: 1.5rem 0; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .8125rem; }
    .bulk-form { display: flex; flex-direction: column; gap: 4px; max-width: 560px; padding: 1.25rem 0; }
    .preview { font-size: .8125rem; color: var(--rw-text-muted); margin: 4px 0 12px; }
    textarea { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
  `],
})
export class BulkErc3643OpsComponent {
  @Input() assetId!: string;
  @Input() deploymentId!: string;

  private readonly erc3643Service = inject(Erc3643Service);
  private readonly txService = inject(TransactionService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  mintCsv = '';
  transferCsv = '';
  transferLegalBasis = '';
  burnCsv = '';

  parseAddressAmount(csv: string): { address: string; amount: string }[] {
    return csv.split('\n')
      .map(line => line.trim())
      .filter(line => line.length > 0)
      .map(line => {
        const [address, amount] = line.split(',').map(p => p.trim());
        return { address, amount };
      })
      .filter(row => row.address && row.amount);
  }

  parseFromToAmount(csv: string): { from: string; to: string; amount: string }[] {
    return csv.split('\n')
      .map(line => line.trim())
      .filter(line => line.length > 0)
      .map(line => {
        const [from, to, amount] = line.split(',').map(p => p.trim());
        return { from, to, amount };
      })
      .filter(row => row.from && row.to && row.amount);
  }

  submitBatchMint(): void {
    const rows = this.parseAddressAmount(this.mintCsv);
    if (rows.length === 0) return;
    if (!confirm(`Mint to ${rows.length} holder(s)?`)) return;

    this.erc3643Service.batchMint(this.assetId, this.deploymentId, {
      addresses: rows.map(r => r.address),
      amounts: rows.map(r => r.amount),
    }).subscribe({
      next: (r) => {
        this.txService.track(r.txId, `Batch mint (${rows.length})`);
        this.mintCsv = '';
        this.cdr.markForCheck();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Batch mint failed.', 'Dismiss', { duration: 6000 }),
    });
  }

  submitBatchForcedTransfer(): void {
    const rows = this.parseFromToAmount(this.transferCsv);
    if (rows.length === 0) return;

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Batch forced transfer of ${rows.length} row(s)${this.transferLegalBasis ? ' — ' + this.transferLegalBasis : ''}`,
        action: 'FORCED_TRANSFER_EWG24',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;
      this.erc3643Service.batchForcedTransfer(this.assetId, this.deploymentId, {
        froms: rows.map(r => r.from),
        tos: rows.map(r => r.to),
        amounts: rows.map(r => r.amount),
      }, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (r) => {
          this.txService.track(r.txId, `Batch forced transfer (${rows.length})`);
          this.transferCsv = '';
          this.transferLegalBasis = '';
          this.cdr.markForCheck();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Batch forced transfer failed.', 'Dismiss', { duration: 6000 }),
      });
    });
  }

  submitBatchBurn(): void {
    const rows = this.parseAddressAmount(this.burnCsv);
    if (rows.length === 0) return;

    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Batch force burn of ${rows.length} row(s)`,
        action: 'FORCE_BURN_EWG26',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;
      this.erc3643Service.batchBurn(this.assetId, this.deploymentId, {
        addresses: rows.map(r => r.address),
        amounts: rows.map(r => r.amount),
      }, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (r) => {
          this.txService.track(r.txId, `Batch burn (${rows.length})`);
          this.burnCsv = '';
          this.cdr.markForCheck();
        },
        error: (err) => this.snackBar.open(err?.error?.message ?? 'Batch burn failed.', 'Dismiss', { duration: 6000 }),
      });
    });
  }
}
