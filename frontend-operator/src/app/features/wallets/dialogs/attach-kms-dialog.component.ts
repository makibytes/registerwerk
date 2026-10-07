import { Component, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

/** `projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>/cryptoKeyVersions/<n>` (Cloud KMS key version). */
export const KMS_KEY_VERSION_PATTERN = /^projects\/[^/]+\/locations\/[^/]+\/keyRings\/[^/]+\/cryptoKeys\/[^/]+\/cryptoKeyVersions\/[^/]+$/;

@Component({
  selector: 'app-attach-kms-dialog',
  standalone: true,
  imports: [FormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Attach Cloud KMS key</h2>
    <mat-dialog-content>
      <p style="color:var(--rw-text-muted);font-size:13px">Register an existing secp256k1 key version from the configured cloud KMS (HSM protection level). The address is derived from the KMS public key and one signing challenge proves the sign permission; the private key never leaves the KMS.</p>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>Wallet name</mat-label><input matInput [(ngModel)]="name"></mat-form-field>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>KMS key version</mat-label><input matInput [(ngModel)]="keyVersion" placeholder="projects/…/cryptoKeyVersions/1"></mat-form-field>
      <mat-form-field appearance="outline" style="width:100%"><mat-label>Expected address (optional)</mat-label><input matInput [(ngModel)]="address" placeholder="0x…">
        <mat-hint>If given, it must match the address derived from the key.</mat-hint></mat-form-field>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button" mat-flat-button [disabled]="!valid" (click)="submit()">Verify & attach</button>
    </mat-dialog-actions>
  `,
})
export class AttachKmsDialogComponent {
  private readonly ref = inject(MatDialogRef<AttachKmsDialogComponent>);
  name = '';
  keyVersion = '';
  address = '';
  get valid(): boolean {
    return !!this.name.trim() && KMS_KEY_VERSION_PATTERN.test(this.keyVersion.trim())
      && (this.address.trim() === '' || /^0x[0-9a-fA-F]{40}$/.test(this.address.trim()));
  }
  submit(): void {
    if (!this.valid) return;
    const body: { name: string; keyVersion: string; address?: string } = { name: this.name.trim(), keyVersion: this.keyVersion.trim() };
    if (this.address.trim()) body.address = this.address.trim();
    this.ref.close(body);
  }
}
