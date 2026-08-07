import { ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatTabsModule } from '@angular/material/tabs';
import { SolanaAdminService } from '../../../../core/api/solana-admin.service';
import { TransactionService } from '../../../../core/api/transaction.service';
import { StepUpDialogComponent } from '../../../../shared/components/step-up/step-up-dialog.component';

/**
 * Operator admin controls for SPL Token-2022 mints (SPL, SPL_2022, SPL_2022_BOND,
 * SPL_2022_CONFIDENTIAL), via the Token-2022 Permanent Delegate + freeze-authority
 * extensions. Wraps `SolanaTokenAdminController`, previously unreachable from any UI.
 */
@Component({
  selector: 'app-solana-admin',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatTabsModule],
  template: `
    <div class="sa-shell">
      <p class="dimmed small" style="margin:0 0 1.25rem">
        Exercises eWpG §24/§26 authority via the Token-2022 Permanent Delegate extension —
        bypasses holder consent, same legal basis as the EVM forced-transfer/force-burn surface.
      </p>

      <mat-tab-group animationDuration="150ms">
        <mat-tab label="Forced Transfer">
          <div class="sa-form">
            <mat-form-field appearance="outline">
              <mat-label>From token account</mat-label>
              <input matInput [(ngModel)]="transferForm.fromTokenAccount" placeholder="base58 account" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>To token account</mat-label>
              <input matInput [(ngModel)]="transferForm.toTokenAccount" placeholder="base58 account" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Amount (smallest unit)</mat-label>
              <input matInput [(ngModel)]="transferForm.amount" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Decimals</mat-label>
              <input matInput type="number" [(ngModel)]="transferForm.decimals" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Legal basis</mat-label>
              <input matInput [(ngModel)]="transferForm.legalBasis" placeholder="e.g. BaFin Bescheid Az. 2025-001" />
            </mat-form-field>
            <button mat-raised-button color="warn"
                    [disabled]="!transferForm.fromTokenAccount || !transferForm.toTokenAccount || !transferForm.amount || !transferForm.legalBasis"
                    (click)="submitForcedTransfer()">
              <mat-icon>gavel</mat-icon>
              Forced Transfer (step-up + 4-eyes)
            </button>
          </div>
        </mat-tab>

        <mat-tab label="Force Burn">
          <div class="sa-form">
            <mat-form-field appearance="outline">
              <mat-label>Token account</mat-label>
              <input matInput [(ngModel)]="burnForm.tokenAccount" placeholder="base58 account" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Amount (smallest unit)</mat-label>
              <input matInput [(ngModel)]="burnForm.amount" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Decimals</mat-label>
              <input matInput type="number" [(ngModel)]="burnForm.decimals" />
            </mat-form-field>
            <mat-form-field appearance="outline">
              <mat-label>Legal basis</mat-label>
              <input matInput [(ngModel)]="burnForm.legalBasis" placeholder="e.g. BaFin Einziehungsverfügung Az. 2025-002" />
            </mat-form-field>
            <button mat-raised-button color="warn"
                    [disabled]="!burnForm.tokenAccount || !burnForm.amount || !burnForm.legalBasis"
                    (click)="submitForceBurn()">
              <mat-icon>local_fire_department</mat-icon>
              Force Burn (step-up + 4-eyes)
            </button>
          </div>
        </mat-tab>

        <mat-tab label="Freeze / Thaw">
          <div class="sa-form">
            <mat-form-field appearance="outline">
              <mat-label>Token account</mat-label>
              <input matInput [(ngModel)]="freezeAccount" placeholder="base58 account" />
            </mat-form-field>
            <div class="sa-row-actions">
              <button mat-stroked-button color="warn" [disabled]="!freezeAccount" (click)="submitFreeze()">
                <mat-icon>lock</mat-icon>
                Freeze
              </button>
              <button mat-stroked-button [disabled]="!freezeAccount" (click)="submitThaw()">
                <mat-icon>lock_open</mat-icon>
                Thaw
              </button>
            </div>
          </div>
        </mat-tab>
      </mat-tab-group>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .sa-shell { padding: 1.5rem 0; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .8125rem; }
    .sa-form { display: flex; flex-direction: column; gap: 4px; max-width: 480px; padding: 1.25rem 0; }
    .sa-row-actions { display: flex; gap: 12px; margin-top: 8px; }
  `],
})
export class SolanaAdminComponent {
  @Input() assetId!: string;
  @Input() deploymentId!: string;

  private readonly service = inject(SolanaAdminService);
  private readonly txService = inject(TransactionService);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  transferForm = { fromTokenAccount: '', toTokenAccount: '', amount: '', decimals: 6, legalBasis: '' };
  burnForm = { tokenAccount: '', amount: '', decimals: 6, legalBasis: '' };
  freezeAccount = '';

  submitForcedTransfer(): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Forced transfer of SPL tokens from ${this.transferForm.fromTokenAccount} to ${this.transferForm.toTokenAccount}`,
        action: 'FORCED_TRANSFER_EWG24',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.forcedTransfer(this.assetId, this.deploymentId, this.transferForm, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (r) => {
          this.txService.track(r.txId, 'SPL forced transfer');
          this.transferForm = { fromTokenAccount: '', toTokenAccount: '', amount: '', decimals: 6, legalBasis: '' };
          this.cdr.markForCheck();
        },
      });
    });
  }

  submitForceBurn(): void {
    const ref = this.dialog.open(StepUpDialogComponent, {
      data: {
        requireDualControl: true,
        reason: `Forced burn of SPL tokens on account ${this.burnForm.tokenAccount}`,
        action: 'FORCE_BURN_EWG26',
      },
      width: '500px',
      disableClose: true,
    });

    ref.afterClosed().subscribe((result) => {
      if (!result) return;
      this.service.forceBurn(this.assetId, this.deploymentId, this.burnForm, result.stepUpToken, result.dualControlToken!).subscribe({
        next: (r) => {
          this.txService.track(r.txId, 'SPL force burn');
          this.burnForm = { tokenAccount: '', amount: '', decimals: 6, legalBasis: '' };
          this.cdr.markForCheck();
        },
      });
    });
  }

  submitFreeze(): void {
    this.service.freeze(this.assetId, this.deploymentId, this.freezeAccount).subscribe({
      next: (r) => {
        this.txService.track(r.txId, `Freeze ${this.freezeAccount.slice(0, 8)}…`);
        this.freezeAccount = '';
        this.cdr.markForCheck();
      },
    });
  }

  submitThaw(): void {
    this.service.thaw(this.assetId, this.deploymentId, this.freezeAccount).subscribe({
      next: (r) => {
        this.txService.track(r.txId, `Thaw ${this.freezeAccount.slice(0, 8)}…`);
        this.freezeAccount = '';
        this.cdr.markForCheck();
      },
    });
  }
}
