import { Component, inject } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface RegisterInvestorData {
  walletAddress: string;
  legalEntityId: string;
  chainConfigId: string;
  countryCode?: number;
}

@Component({
  selector: 'app-register-investor-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Register Investor</h2>
    <mat-dialog-content>
      <form [formGroup]="form" style="display:flex;flex-direction:column;gap:12px;min-width:380px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Wallet Address</mat-label>
          <input matInput formControlName="walletAddress" placeholder="0x..." />
          <mat-error>Required, must start with 0x</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal Entity UUID</mat-label>
          <input matInput formControlName="legalEntityId" placeholder="xxxxxxxx-xxxx-..." />
          <mat-error>Required UUID</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Chain Config UUID</mat-label>
          <input matInput formControlName="chainConfigId" placeholder="xxxxxxxx-xxxx-..." />
          <mat-error>Required UUID</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Country Code (optional, ISO 3166 numeric)</mat-label>
          <input matInput type="number" formControlName="countryCode" placeholder="276" />
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-raised-button color="primary" [disabled]="form.invalid" (click)="submit()">Register</button>
    </mat-dialog-actions>
  `,
})
export class RegisterInvestorDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<RegisterInvestorDialogComponent>);
  private readonly fb = inject(FormBuilder);

  form = this.fb.group({
    walletAddress: ['', [Validators.required, Validators.pattern(/^0x[0-9a-fA-F]{40}$/)]],
    legalEntityId: ['', Validators.required],
    chainConfigId: ['', Validators.required],
    countryCode: [null as number | null],
  });

  submit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const result: RegisterInvestorData = {
      walletAddress: v.walletAddress!,
      legalEntityId: v.legalEntityId!,
      chainConfigId: v.chainConfigId!,
    };
    if (v.countryCode) result.countryCode = v.countryCode;
    this.dialogRef.close(result);
  }
}
