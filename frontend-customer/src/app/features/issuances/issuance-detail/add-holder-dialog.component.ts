import { ChangeDetectorRef, Component, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { HolderChangeRequest, InstructingParty, IssuanceService } from '../../../core/api/issuance.service';
import { MatSelectModule } from '@angular/material/select';
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
    MatSelectModule,
  ],
  template: `
    <h2 mat-dialog-title>Request Holder Entry</h2>

    <mat-dialog-content>
      <p class="hint">
        Register entries are made by the registry operator. Send the entry you need and the instruction
        it rests on; the operator checks the instruction and executes or rejects the request.
      </p>
      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Investor legal entity ID</mat-label>
        <input matInput [(ngModel)]="investorId" placeholder="UUID" autocomplete="off" />
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Wallet Address</mat-label>
        <input matInput [(ngModel)]="walletAddress" placeholder="0x..." autocomplete="off" />
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

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Instruction from</mat-label>
        <mat-select [(ngModel)]="instructingParty">
          <mat-option value="HOLDER">Holder</mat-option>
          <mat-option value="BENEFICIARY">Beneficiary</mat-option>
          <mat-option value="COURT">Court</mat-option>
          <mat-option value="INSOLVENCY_ADMINISTRATOR">Insolvency administrator</mat-option>
          <mat-option value="ISSUER_TERMS_CHANGE">Issuer (change of terms)</mat-option>
        </mat-select>
      </mat-form-field>

      <mat-form-field appearance="outline" class="full-width">
        <mat-label>Instruction reference</mat-label>
        <input matInput [(ngModel)]="instructionReference" placeholder="e.g. court file number, holder letter of…" autocomplete="off" />
      </mat-form-field>

      @if (error) {
        <p class="error-message">{{ error }}</p>
      }
    </mat-dialog-content>

    <mat-dialog-actions align="end">
      <button mat-button type="button" mat-dialog-close>Cancel</button>
      <button
        mat-raised-button
        color="primary"
        type="button"
        [disabled]="saving || !canSave()"
        (click)="save()"
      >
        @if (saving) { <mat-spinner diameter="18"></mat-spinner> }
        @else { Send request }
      </button>
    </mat-dialog-actions>
  `,
  styles: [`
    .full-width { width: 100%; margin-bottom: 12px; }
    .hint { font-size: 13px; color: var(--rw-text-secondary); margin: 0 0 12px; }
    .error-message { color: var(--rw-text-danger); font-size: 13px; }
    mat-dialog-content { display: flex; flex-direction: column; gap: 4px; padding-top: 16px !important; }
  `]
})
export class AddHolderDialogComponent {
  private readonly issuanceService = inject(IssuanceService);
  private readonly dialogRef = inject<MatDialogRef<AddHolderDialogComponent, HolderChangeRequest>>(MatDialogRef);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  investorId = '';
  instructingParty: InstructingParty | '' = '';
  instructionReference = '';
  walletAddress = '';
  nominalAmount: number | null = null;
  saving = false;
  error = '';

  readonly data = inject<DialogData>(MAT_DIALOG_DATA);

  pickWallet(): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select holder wallet' }, width: '560px', maxWidth: '95vw' }
    ).afterClosed().subscribe(addr => {
      if (addr) {
        this.walletAddress = addr;
        this.cdr.markForCheck(); // zoneless: picked address must re-render
      }
    });
  }

  save(): void {
    if (this.saving || !this.canSave()) return;
    this.saving = true;
    this.error = '';

    this.issuanceService
      .requestHolderEntry(this.data.assetId, {
        investorId: this.investorId.trim(),
        walletAddress: this.walletAddress.trim(),
        nominalAmount: this.nominalAmount!,
        instructingParty: this.instructingParty as InstructingParty,
        instructionReference: this.instructionReference.trim(),
      })
      .subscribe({
        next: (request) => this.dialogRef.close(request),
        error: (err) => {
          this.saving = false;
          this.error = err?.error?.message ?? 'Failed to send the request.';
          this.cdr.markForCheck(); // zoneless: error state must re-render
        },
      });
  }

  canSave(): boolean {
    return !!this.investorId.trim() && !!this.walletAddress.trim() && this.isValidAmount()
      && !!this.instructingParty && !!this.instructionReference.trim();
  }

  isValidAmount(): boolean {
    return this.nominalAmount !== null
      && Number.isFinite(this.nominalAmount)
      && this.nominalAmount > 0;
  }
}
