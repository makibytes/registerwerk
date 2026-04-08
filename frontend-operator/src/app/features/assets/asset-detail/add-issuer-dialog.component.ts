import { Component, inject } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface AddIssuerData {
  issuerAddress: string;
  claimTopics: number[];
  legalEntityId?: string;
}

@Component({
  selector: 'app-add-issuer-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Add Trusted Issuer</h2>
    <mat-dialog-content>
      <form [formGroup]="form" style="display:flex;flex-direction:column;gap:12px;min-width:380px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Issuer Address</mat-label>
          <input matInput formControlName="issuerAddress" placeholder="0x..." />
          <mat-error>Required, must start with 0x</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Claim Topics (comma-separated, e.g. 1,2)</mat-label>
          <input matInput formControlName="claimTopics" placeholder="1,2,3" />
          <mat-hint>Standard: 1=KYC, 2=AML, 3=Accreditation</mat-hint>
          <mat-error>Required — enter at least one topic number</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal Entity UUID (optional)</mat-label>
          <input matInput formControlName="legalEntityId" placeholder="xxxxxxxx-xxxx-..." />
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>Cancel</button>
      <button mat-raised-button color="primary" [disabled]="form.invalid" (click)="submit()">Add</button>
    </mat-dialog-actions>
  `,
})
export class AddIssuerDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<AddIssuerDialogComponent>);
  private readonly fb = inject(FormBuilder);

  form = this.fb.group({
    issuerAddress: ['', [Validators.required, Validators.pattern(/^0x[0-9a-fA-F]{40}$/)]],
    claimTopics: ['', Validators.required],
    legalEntityId: [''],
  });

  submit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const topics = (v.claimTopics ?? '')
      .split(',')
      .map(t => parseInt(t.trim(), 10))
      .filter(n => !isNaN(n));
    const result: AddIssuerData = {
      issuerAddress: v.issuerAddress!,
      claimTopics: topics,
    };
    if (v.legalEntityId) result.legalEntityId = v.legalEntityId;
    this.dialogRef.close(result);
  }
}
