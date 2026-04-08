import { Component, inject } from '@angular/core';
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
import { EntityService } from '../../../core/api/entity.service';

@Component({
  selector: 'app-create-entity',
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

    .form-card {
      max-width: 680px;
    }

    mat-card-header {
      margin-bottom: 8px;
    }

    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 20px;

      @media (max-width: 600px) { grid-template-columns: 1fr; }
    }

    .full-span { grid-column: 1 / -1; }

    mat-form-field {
      width: 100%;
    }

    .form-actions {
      display: flex;
      justify-content: flex-end;
      gap: 12px;
      margin-top: 8px;
    }

    .optional-label {
      font-size: 12px;
      color: rgba(0,0,0,0.38);
    }
  `],
  template: `
    <div class="back-row">
      <button mat-button (click)="cancel()">
        <mat-icon>arrow_back</mat-icon>
        Back
      </button>
    </div>

    <mat-card class="form-card">
      <mat-card-header>
        <mat-icon mat-card-avatar>person_add</mat-icon>
        <mat-card-title>Register New Legal Entity</mat-card-title>
        <mat-card-subtitle>Creates an entity record ready for onboarding</mat-card-subtitle>
      </mat-card-header>

      <mat-card-content>
        <form [formGroup]="form" (ngSubmit)="submit()">
          <div class="form-grid">
            <mat-form-field appearance="outline">
              <mat-label>Entity Type</mat-label>
              <mat-select formControlName="type">
                <mat-option value="ISSUER">Issuer</mat-option>
                <mat-option value="INVESTOR">Investor</mat-option>
                <mat-option value="AUDITOR">Auditor</mat-option>
              </mat-select>
              @if (form.controls['type'].hasError('required')) {
                <mat-error>Type is required</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Legal Name</mat-label>
              <input matInput formControlName="currentName" placeholder="Acme AG" />
              @if (form.controls['currentName'].hasError('required')) {
                <mat-error>Legal name is required</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Registration Number</mat-label>
              <input matInput formControlName="registrationNumber" placeholder="CHE-123.456.789" />
            </mat-form-field>

            <mat-form-field appearance="outline">
              <mat-label>Registration Country</mat-label>
              <input matInput formControlName="registrationCountry" placeholder="CH" />
              @if (form.controls['registrationCountry'].hasError('required')) {
                <mat-error>Country is required</mat-error>
              }
            </mat-form-field>

            <mat-form-field appearance="outline" class="full-span">
              <mat-label>
                LEI Code
                <span class="optional-label">(optional)</span>
              </mat-label>
              <input matInput formControlName="leiCode" placeholder="213800WSGIIZCXF1P572" />
              <mat-hint>20-character Legal Entity Identifier</mat-hint>
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
              Create Entity
            </button>
          </div>
        </form>
      </mat-card-content>
    </mat-card>
  `,
})
export class CreateEntityComponent {
  private readonly fb = inject(FormBuilder);
  private readonly entityService = inject(EntityService);
  private readonly router = inject(Router);

  submitting = false;

  readonly form = this.fb.group({
    type: ['', Validators.required],
    currentName: ['', Validators.required],
    registrationNumber: [''],
    registrationCountry: ['', Validators.required],
    leiCode: [''],
  });

  submit(): void {
    if (this.form.invalid) return;
    this.submitting = true;
    this.entityService.createEntity(this.form.value as Record<string, string>).subscribe({
      next: (entity) => {
        this.submitting = false;
        this.router.navigate(['/onboarding/token', entity.id]);
      },
      error: () => {
        this.submitting = false;
      },
    });
  }

  cancel(): void {
    this.router.navigate(['/customers']);
  }
}
