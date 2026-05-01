import { Component, inject, Inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { IssuanceService } from '../../../core/api/issuance.service';
import { AssetHolder } from '../../../core/models';
import { AddressPickerDialogComponent, AddressPickerDialogData } from '../../../shared/components/address-picker-dialog.component';

interface DialogData {
  assetId: string;
}

@Component({
  selector: 'app-add-holder-dialog',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  template: `
    <h2 mat-dialog-title>Add Holder</h2>

    <mat-dialog-content>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Wallet Address</mat-label>
        <input matInput [(ngModel)]="walletAddress" placeholder="0x..." />
        <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                (click)="pickWallet()">
          <mat-icon style="font-size:18px">contacts</mat-icon>
        </button>
        <mat-hint>Ethereum or equivalent wallet address (including smart wallets)</mat-hint>
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Nominal Amount</mat-label>
        <input matInput type="number" [(ngModel)]="nominalAmount" min="1" />
        <mat-hint>Face value in the instrument's currency</mat-hint>
      </mat-form-field>

      @if (error) {
        <p class="error-message">{{ error }}</p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button
        mat-raised-button
        color="primary"
        [disabled]="saving || !walletAddress || !nominalAmount"
        (click)="save()"
      >
        @if (saving) { <mat-spinner diameter="18"></mat-spinner> }
        @else { Add }
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 12px; }
    .error-message { color: #c62828; font-size: 13px; }
    mat-dialog-content { display: flex; flex-direction: column; gap: 4px; padding-top: 16px !important; }
  `]
})
export class AddHolderDialogComponent {
  private readonly issuanceService = inject(IssuanceService);
  private readonly dialogRef = inject<MatDialogRef<AddHolderDialogComponent, AssetHolder>>(MatDialogRef);
  private readonly dialog = inject(MatDialog);

  walletAddress = '';
  nominalAmount: number | null = null;
  saving = false;
  error = '';

  constructor(@Inject(MAT_DIALOG_DATA) public data: DialogData) {}

  pickWallet(): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select holder wallet' }, width: '560px' }
    ).afterClosed().subscribe(addr => { if (addr) this.walletAddress = addr; });
  }

  save(): void {
    if (!this.walletAddress || !this.nominalAmount) return;
    this.saving = true;
    this.error = '';

    this.issuanceService
      .addHolder(this.data.assetId, {
        walletAddress: this.walletAddress,
        nominalAmount: this.nominalAmount,
      })
      .subscribe({
        next: (holder) => this.dialogRef.close(holder),
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message ?? 'Failed to add holder.';
        },
      });
  }
}
