import { Component, OnInit, Input, inject } from '@angular/core';
import { Router } from '@angular/router';
import { ReactiveFormsModule, FormBuilder, Validators } from '@angular/forms';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatDividerModule } from '@angular/material/divider';
import { AssetService } from '../../../core/api/asset.service';
import { Asset } from '../../../core/models';

@Component({
  selector: 'app-asset-edit',
  standalone: true,
  imports: [
    ReactiveFormsModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatButtonModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatDividerModule,
  ],
  styles: [`
    .back-row { margin-bottom: 12px; }

    .form-card { max-width: 720px; }

    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 20px;
      @media (max-width: 600px) { grid-template-columns: 1fr; }
    }

    .full-span { grid-column: 1 / -1; }

    mat-form-field { width: 100%; }

    .form-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 8px;
    }

    .spinner-wrap { display: flex; justify-content: center; padding: 40px; }
  `],
  template: `
    <div class="back-row">
      <button mat-button (click)="cancel()">
        <mat-icon>arrow_back</mat-icon>
        Back to Asset
      </button>
    </div>

    @if (loading) {
      <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
    } @else {
      <mat-card class="form-card">
        <mat-card-header>
          <mat-icon mat-card-avatar>edit</mat-icon>
          <mat-card-title>Edit Asset</mat-card-title>
          <mat-card-subtitle>Operator — all fields editable</mat-card-subtitle>
        </mat-card-header>

        <mat-card-content>
          <form [formGroup]="form" (ngSubmit)="submit()">
            <div class="form-grid">
              <mat-form-field appearance="outline" class="full-span">
                <mat-label>Asset Name</mat-label>
                <input matInput formControlName="name" />
                @if (form.controls['name'].hasError('required')) {
                  <mat-error>Name is required</mat-error>
                }
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>ISIN</mat-label>
                <input matInput formControlName="isin" placeholder="CH0012221716" />
                <mat-hint>12-character ISIN (optional)</mat-hint>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Token Standard</mat-label>
                <mat-select formControlName="tokenStandard">
                  <mat-option value="ERC20">ERC-20</mat-option>
                  <mat-option value="ERC721">ERC-721</mat-option>
                  <mat-option value="ERC1155">ERC-1155</mat-option>
                  <mat-option value="ERC3643">ERC-3643</mat-option>
                  <mat-option value="CONF_ERC20">Confidential ERC-20</mat-option>
                  <mat-option value="CONF_ERC3643">Confidential ERC-3643</mat-option>
                  <mat-option value="SPL">SPL</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>On-chain Level</mat-label>
                <mat-select formControlName="onchainLevel">
                  <mat-option value="NONE">None</mat-option>
                  <mat-option value="SIMPLE">Simple</mat-option>
                  <mat-option value="CONTROL">Control</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Status</mat-label>
                <mat-select formControlName="status">
                  <mat-option value="DRAFT">Draft</mat-option>
                  <mat-option value="PENDING_APPROVAL">Pending Approval</mat-option>
                  <mat-option value="APPROVED">Approved</mat-option>
                  <mat-option value="ISSUED">Issued</mat-option>
                  <mat-option value="SUSPENDED">Suspended</mat-option>
                  <mat-option value="REDEEMED">Redeemed</mat-option>
                </mat-select>
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Total Supply</mat-label>
                <input matInput type="number" formControlName="totalSupply" />
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Decimals</mat-label>
                <input matInput type="number" formControlName="decimals" />
              </mat-form-field>

              <mat-form-field appearance="outline" class="full-span">
                <mat-label>Issuer ID</mat-label>
                <input matInput formControlName="issuerId" />
                <mat-hint>UUID of the issuing entity</mat-hint>
              </mat-form-field>
            </div>

            <mat-divider style="margin: 16px 0;" />

            <div class="form-actions">
              <button type="button" mat-stroked-button (click)="cancel()">Cancel</button>
              <button
                type="submit"
                mat-raised-button
                color="primary"
                [disabled]="form.invalid || submitting"
              >
                @if (submitting) {
                  <mat-spinner diameter="18" style="display:inline-block;vertical-align:middle;margin-right:6px" />
                }
                Save Changes
              </button>
            </div>
          </form>
        </mat-card-content>
      </mat-card>
    }
  `,
})
export class AssetEditComponent implements OnInit {
  @Input() id!: string;

  private readonly fb = inject(FormBuilder);
  private readonly assetService = inject(AssetService);
  private readonly router = inject(Router);

  loading = true;
  submitting = false;

  readonly form = this.fb.group({
    name: ['', Validators.required],
    isin: [''],
    tokenStandard: ['', Validators.required],
    onchainLevel: ['', Validators.required],
    status: ['', Validators.required],
    totalSupply: [null as number | null],
    decimals: [null as number | null],
    issuerId: ['', Validators.required],
  });

  ngOnInit(): void {
    this.assetService.getAsset(this.id).subscribe({
      next: (asset: Asset) => {
        this.form.patchValue({
          name: asset.name,
          isin: asset.isin ?? '',
          tokenStandard: asset.tokenStandard,
          onchainLevel: asset.onchainLevel,
          status: asset.status,
          totalSupply: asset.totalSupply ?? null,
          decimals: asset.decimals ?? null,
          issuerId: asset.issuerId,
        });
        this.loading = false;
      },
      error: () => { this.loading = false; },
    });
  }

  submit(): void {
    if (this.form.invalid) return;
    this.submitting = true;
    this.assetService.updateAsset(this.id, this.form.value as Partial<Asset>).subscribe({
      next: () => {
        this.submitting = false;
        this.router.navigate(['/assets', this.id]);
      },
      error: () => { this.submitting = false; },
    });
  }

  cancel(): void {
    this.router.navigate(['/assets', this.id]);
  }
}
