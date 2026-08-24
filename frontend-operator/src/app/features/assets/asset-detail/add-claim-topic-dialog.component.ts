import { Component, inject } from '@angular/core';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';

export interface AddClaimTopicData {
  topic: number;
  label?: string;
}

@Component({
  selector: 'app-add-claim-topic-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Add Required Claim Topic</h2>
    <mat-dialog-content>
      <form [formGroup]="form" style="display:flex;flex-direction:column;gap:12px;min-width:320px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Topic Number</mat-label>
          <input matInput type="number" formControlName="topic" placeholder="1" />
          <mat-hint>1=KYC, 2=AML, 3=Accreditation</mat-hint>
          <mat-error>Required numeric value</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Label (optional)</mat-label>
          <input matInput formControlName="label" placeholder="KYC" />
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button" mat-raised-button color="primary" [disabled]="form.invalid" (click)="submit()">Add</button>
    </mat-dialog-actions>
  `,
})
export class AddClaimTopicDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<AddClaimTopicDialogComponent>);
  private readonly fb = inject(FormBuilder);

  form = this.fb.group({
    topic: [null as number | null, [Validators.required, Validators.min(1)]],
    label: [''],
  });

  submit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    this.dialogRef.close({
      topic: v.topic!,
      label: v.label || undefined,
    } as AddClaimTopicData);
  }
}
