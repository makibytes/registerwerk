import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MAT_DIALOG_DATA } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { OperatorWallet } from '../../../core/models';

@Component({
  selector: 'app-export-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  styles: [`
    h2 { margin: 0 0 4px; font-size: 17px; font-weight: 700; }
    .subtitle { font-size: 13px; color: var(--rw-text-muted); margin: 0 0 20px; }
    mat-form-field { width: 100%; }
    .actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 8px; }
  `],
  template: `
    <div mat-dialog-title style="padding: 20px 24px 0">
      <h2>Export Keystore</h2>
      <p class="subtitle">{{ data.wallet.name }} — choose a password to encrypt the exported file.</p>
    </div>
    <mat-dialog-content style="padding: 0 24px 8px">
      <mat-form-field appearance="outline">
        <mat-label>Export password</mat-label>
        <input matInput [(ngModel)]="password" type="password" autocomplete="new-password" />
        <mat-hint>This password will be required to import the keystore elsewhere.</mat-hint>
      </mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions style="padding: 0 24px 20px">
      <div class="actions">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-flat-button color="primary" [disabled]="!password" (click)="submit()">Export</button>
      </div>
    </mat-dialog-actions>
  `,
})
export class ExportDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<ExportDialogComponent>);
  readonly data: { wallet: OperatorWallet } = inject(MAT_DIALOG_DATA);
  password = '';
  submit() {
    if (!this.password) return;
    this.dialogRef.close({ password: this.password });
  }
}
