import { CommonModule } from '@angular/common';
import {
  ChangeDetectorRef,
  Component,
  EventEmitter,
  Input,
  OnChanges,
  Output,
  SimpleChanges,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatTooltipModule } from '@angular/material/tooltip';
import { CompanyService } from '../../core/api/company.service';
import { ExternalReferenceSubjectType } from '../../core/models';

@Component({
  selector: 'app-external-id-editor',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatTooltipModule,
  ],
  template: `
    <div class="editor-row">
      <mat-form-field appearance="outline" class="editor-field" subscriptSizing="dynamic">
        <mat-label>{{ label }}</mat-label>
        <input
          matInput
          [(ngModel)]="draftValue"
          [placeholder]="placeholder"
          [disabled]="saving"
          maxlength="255"
        />
      </mat-form-field>

      <button
        mat-icon-button
        type="button"
        color="primary"
        [disabled]="saving || !subjectId"
        matTooltip="Save external ID"
        (click)="save()"
      >
        @if (saving) {
          <mat-spinner diameter="18"></mat-spinner>
        } @else {
          <mat-icon>save</mat-icon>
        }
      </button>

      <button
        mat-icon-button
        type="button"
        [disabled]="saving || !hasValue"
        matTooltip="Clear external ID"
        (click)="clear()"
      >
        <mat-icon>close</mat-icon>
      </button>
    </div>

    @if (errorMessage) {
      <div class="error-text" role="alert">{{ errorMessage }}</div>
    }
  `,
  styles: [`
    .editor-row {
      display: flex;
      align-items: flex-start;
      gap: 8px;
    }

    .editor-field {
      flex: 1;
      min-width: 220px;
    }

    .error-text {
      margin-top: 4px;
      color: #b91c1c;
      font-size: 12px;
    }
  `],
})
export class ExternalIdEditorComponent implements OnChanges {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly companyService = inject(CompanyService);

  @Input({ required: true }) subjectType!: ExternalReferenceSubjectType;
  @Input({ required: true }) subjectId!: string;
  @Input() value: string | null = null;
  @Input() label = 'External ID';
  @Input() placeholder = 'Enter external ID';

  @Output() valueChange = new EventEmitter<string | null>();

  draftValue = '';
  saving = false;
  errorMessage = '';

  get hasValue(): boolean {
    return this.draftValue.trim().length > 0 || !!this.value;
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['value']) {
      this.draftValue = this.value ?? '';
      this.errorMessage = '';
    }
  }

  save(): void {
    if (this.saving || !this.subjectId) return;
    const externalId = this.draftValue.trim();
    if (!externalId) {
      this.clear();
      return;
    }

    this.saving = true;
    this.errorMessage = '';
    this.companyService.saveExternalId(this.subjectType, this.subjectId, externalId).subscribe({
      next: (response) => {
        this.value = response.externalId;
        this.draftValue = response.externalId;
        this.saving = false;
        this.valueChange.emit(response.externalId);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.message ?? 'Failed to save external ID.';
        this.cdr.markForCheck();
      },
    });
  }

  clear(): void {
    if (this.saving || !this.subjectId) return;
    if (!this.value && !this.draftValue.trim()) {
      this.draftValue = '';
      return;
    }

    this.saving = true;
    this.errorMessage = '';
    this.companyService.deleteExternalId(this.subjectType, this.subjectId).subscribe({
      next: () => {
        this.value = null;
        this.draftValue = '';
        this.saving = false;
        this.valueChange.emit(null);
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.saving = false;
        this.errorMessage = err?.error?.message ?? 'Failed to clear external ID.';
        this.cdr.markForCheck();
      },
    });
  }
}
