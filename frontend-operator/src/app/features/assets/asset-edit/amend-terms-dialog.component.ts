import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { Asset, AssetBondTerms, TermsAmendment } from '../../../core/models';

export interface AmendTermsDialogData {
  asset: Asset;
  bondTerms: AssetBondTerms | null;
}

/**
 * Collects a post-approval economic-terms amendment. Only fields whose value differs from the
 * current term are sent; the caller then asks for step-up + a second approver (4-eyes) and posts
 * to `/assets/{id}/terms-amendments`, which audits before/after values and the legal basis.
 */
@Component({
  selector: 'app-amend-terms-dialog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [ReactiveFormsModule, MatDialogModule, MatButtonModule, MatFormFieldModule, MatInputModule],
  template: `
    <h2 mat-dialog-title>Amend terms</h2>
    <mat-dialog-content>
      <p class="intro">
        The economic terms of an approved asset are locked. An amendment needs a legal basis, step-up
        authentication and a second operator's approval; before/after values are written to the audit log.
        @if (data.bondTerms) { Future, not yet raised coupons are regenerated from the amended terms. }
      </p>
      <form [formGroup]="form" class="grid">
        <mat-form-field appearance="outline"><mat-label>ISIN</mat-label><input matInput formControlName="isin" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Currency</mat-label><input matInput formControlName="currency" maxlength="3" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Issue size</mat-label><input matInput type="number" formControlName="issueSize" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Denomination</mat-label><input matInput type="number" formControlName="denomination" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Issue date</mat-label><input matInput type="date" formControlName="issueDate" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Maturity date</mat-label><input matInput type="date" formControlName="maturityDate" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Min. investment</mat-label><input matInput type="number" formControlName="minInvestmentAmount" /></mat-form-field>
        <mat-form-field appearance="outline"><mat-label>Max. holding</mat-label><input matInput type="number" formControlName="maxHoldingAmount" /></mat-form-field>
        @if (data.bondTerms) {
          <mat-form-field appearance="outline"><mat-label>Face value</mat-label><input matInput type="number" formControlName="faceValue" /></mat-form-field>
          <mat-form-field appearance="outline"><mat-label>Coupon rate (%)</mat-label><input matInput type="number" step="0.0001" formControlName="couponRatePct" /></mat-form-field>
        }
        <mat-form-field appearance="outline" class="full">
          <mat-label>Legal basis</mat-label>
          <textarea matInput rows="2" formControlName="legalReference"
                    placeholder="e.g. SchVG §5 creditor resolution of 2026-05-04"></textarea>
          @if (form.controls.legalReference.hasError('required')) { <mat-error>A legal basis is required</mat-error> }
        </mat-form-field>
      </form>
      @if (noChange) { <p class="warn" role="alert">No term differs from its current value.</p> }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button" mat-flat-button color="primary" [disabled]="form.invalid" (click)="submit()">Continue to approval</button>
    </mat-dialog-actions>
  `,
  styles: [`
    .intro { font-size: .8125rem; color: var(--rw-text-secondary); margin: 0 0 12px; }
    .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 0 12px; }
    .full { grid-column: 1 / -1; }
    mat-form-field { width: 100%; }
    .warn { color: var(--rw-text-warning); font-size: .8125rem; }
    @media (max-width: 600px) { .grid { grid-template-columns: 1fr; } }
  `],
})
export class AmendTermsDialogComponent {
  readonly data = inject<AmendTermsDialogData>(MAT_DIALOG_DATA);
  private readonly dialogRef = inject(MatDialogRef<AmendTermsDialogComponent, TermsAmendment>);
  private readonly fb = inject(FormBuilder);

  noChange = false;

  readonly form = this.fb.group({
    isin: [this.data.asset.isin ?? ''],
    currency: [this.data.asset.currency ?? ''],
    issueSize: [this.data.asset.issueSize ?? null as number | null],
    denomination: [this.data.asset.denomination ?? null as number | null],
    issueDate: [this.data.asset.issueDate ?? ''],
    maturityDate: [this.data.asset.maturityDate ?? this.data.bondTerms?.maturityDate ?? ''],
    minInvestmentAmount: [this.data.asset.minInvestmentAmount ?? null as number | null],
    maxHoldingAmount: [this.data.asset.maxHoldingAmount ?? null as number | null],
    faceValue: [this.data.bondTerms?.faceValue ?? null as number | null],
    couponRatePct: [this.data.bondTerms?.couponRate != null ? +(this.data.bondTerms.couponRate * 100).toFixed(8) : null as number | null],
    legalReference: ['', [Validators.required, Validators.maxLength(500)]],
  });

  submit(): void {
    const v = this.form.getRawValue();
    const a = this.data.asset;
    const t = this.data.bondTerms;
    const body: TermsAmendment = { legalReference: (v.legalReference ?? '').trim() };
    const str = (x: string | null | undefined) => (x ?? '').trim();
    if (str(v.isin).toUpperCase() !== str(a.isin).toUpperCase()) body.isin = str(v.isin).toUpperCase();
    if (str(v.currency).toUpperCase() !== str(a.currency).toUpperCase() && str(v.currency)) body.currency = str(v.currency).toUpperCase();
    if (v.issueSize != null && v.issueSize !== a.issueSize) body.issueSize = v.issueSize;
    if (v.denomination != null && v.denomination !== a.denomination) body.denomination = v.denomination;
    if (v.issueDate && v.issueDate !== a.issueDate) body.issueDate = v.issueDate;
    if (v.maturityDate && v.maturityDate !== (a.maturityDate ?? t?.maturityDate)) body.maturityDate = v.maturityDate;
    if (v.minInvestmentAmount != null && v.minInvestmentAmount !== a.minInvestmentAmount) body.minInvestmentAmount = v.minInvestmentAmount;
    if (v.maxHoldingAmount != null && v.maxHoldingAmount !== a.maxHoldingAmount) body.maxHoldingAmount = v.maxHoldingAmount;
    if (t) {
      if (v.faceValue != null && v.faceValue !== t.faceValue) body.faceValue = v.faceValue;
      const current = t.couponRate != null ? +(t.couponRate * 100).toFixed(8) : null;
      if (v.couponRatePct != null && v.couponRatePct !== current) body.couponRate = +(v.couponRatePct / 100).toFixed(10);
    }
    this.noChange = Object.keys(body).length === 1;
    if (this.noChange) return;
    this.dialogRef.close(body);
  }
}
