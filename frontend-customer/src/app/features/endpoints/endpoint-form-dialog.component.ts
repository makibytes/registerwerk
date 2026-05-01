import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatRadioModule } from '@angular/material/radio';
import { Endpoint, EndpointAddressType, EndpointCreateRequest, EndpointRiskLevel, EndpointUpdateRequest } from '../../core/models';

export interface EndpointFormDialogData { mode: 'create' | 'edit'; endpoint?: Endpoint; }
export type EndpointFormDialogResult = EndpointCreateRequest | EndpointUpdateRequest;

@Component({
  selector: 'app-endpoint-form-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule, MatSelectModule, MatRadioModule],
  styles: [`
    .dialog-body { display: flex; flex-direction: column; gap: 12px; padding: 8px 0 4px; min-width: 400px; }
    .field-label { font-size: 11px; font-weight: 600; letter-spacing: 0.5px; text-transform: uppercase; color: var(--rw-text-muted); margin-bottom: 4px; }
    mat-form-field { width: 100%; }
  `],
  template: `
    <h2 mat-dialog-title>{{ data.mode === 'edit' ? 'Edit endpoint' : 'New endpoint' }}</h2>
    <mat-dialog-content>
      <div class="dialog-body">
        <div>
          <div class="field-label">Type</div>
          <mat-radio-group [(ngModel)]="form.addressType" [disabled]="data.mode === 'edit'" style="display:flex;gap:16px">
            <mat-radio-button value="WALLET">Wallet</mat-radio-button>
            <mat-radio-button value="CONTRACT">Contract</mat-radio-button>
          </mat-radio-group>
          @if (form.addressType === 'WALLET' && data.mode !== 'edit') {
            <p style="font-size:12px;color:var(--rw-text-muted);margin:6px 0 0;line-height:1.5">
              A wallet can also be a <strong>smart wallet</strong> — a smart contract used as a wallet (e.g. multisig, account abstraction). Use type <em>Wallet</em> for these too.
            </p>
          }
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Address</mat-label>
          <input matInput [(ngModel)]="form.address" [disabled]="data.mode === 'edit'" placeholder="0x…" maxlength="66" autocomplete="off"
                 style="font-family:'IBM Plex Mono',monospace;font-size:13px">
          @if (data.mode === 'edit') { <mat-hint>Address cannot be changed</mat-hint> }
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="form.name" maxlength="200" required autocomplete="off">
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Risk level</mat-label>
          <mat-select [(ngModel)]="form.riskLevel">
            <mat-option [value]="undefined">— None —</mat-option>
            <mat-option value="LOW">Low</mat-option>
            <mat-option value="MEDIUM">Medium</mat-option>
            <mat-option value="HIGH">High</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Notes</mat-label>
          <textarea matInput [(ngModel)]="form.notes" rows="3" maxlength="500" placeholder="Optional notes…"></textarea>
        </mat-form-field>
      </div>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button color="primary" (click)="save()" [disabled]="!form.name.trim() || (data.mode !== 'edit' && !form.address.trim())">
        {{ data.mode === 'edit' ? 'Save changes' : 'Create endpoint' }}
      </button>
    </mat-dialog-actions>
  `,
})
export class EndpointFormDialogComponent {
  private readonly dialogRef = inject<MatDialogRef<EndpointFormDialogComponent, EndpointFormDialogResult>>(MatDialogRef);
  readonly data: EndpointFormDialogData = inject(MAT_DIALOG_DATA);

  form = {
    addressType: (this.data.endpoint?.addressType ?? 'WALLET') as EndpointAddressType,
    address:    this.data.endpoint?.address  ?? '',
    name:       this.data.endpoint?.name     ?? '',
    notes:      this.data.endpoint?.notes    ?? '',
    riskLevel:  this.data.endpoint?.riskLevel as EndpointRiskLevel | undefined,
  };

  save(): void {
    if (this.data.mode === 'edit') {
      this.dialogRef.close({ name: this.form.name.trim(), notes: this.form.notes.trim() || undefined, riskLevel: this.form.riskLevel } as EndpointUpdateRequest);
    } else {
      this.dialogRef.close({ address: this.form.address.trim(), addressType: this.form.addressType, name: this.form.name.trim(), notes: this.form.notes.trim() || undefined, riskLevel: this.form.riskLevel } as EndpointCreateRequest);
    }
  }
}
