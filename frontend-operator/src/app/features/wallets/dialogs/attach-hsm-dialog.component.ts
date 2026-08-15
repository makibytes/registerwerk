import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

@Component({
  selector: 'app-attach-hsm-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Attach HSM key</h2>
    <mat-dialog-content>
      <p style="color:var(--rw-text-muted);font-size:13px">Register an existing secp256k1 key from the configured PKCS#11 token. A signing challenge verifies the alias; the private key never leaves the HSM.</p>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>Wallet name</mat-label><input matInput [(ngModel)]="name"></mat-form-field>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>PKCS#11 key alias</mat-label><input matInput [(ngModel)]="keyAlias"></mat-form-field>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>Ethereum address</mat-label><input matInput [(ngModel)]="address" placeholder="0x…"></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-flat-button [disabled]="!valid" (click)="submit()">Verify & attach</button>
    </mat-dialog-actions>
  `,
})
export class AttachHsmDialogComponent {
  private readonly ref = inject(MatDialogRef<AttachHsmDialogComponent>);
  name = '';
  keyAlias = '';
  address = '';
  get valid(): boolean { return !!this.name.trim() && !!this.keyAlias.trim() && /^0x[0-9a-fA-F]{40}$/.test(this.address); }
  submit(): void { if (this.valid) this.ref.close({ name: this.name.trim(), keyAlias: this.keyAlias.trim(), address: this.address }); }
}
