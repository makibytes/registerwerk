import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, Input, OnInit, TemplateRef, ViewChild, inject
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatSnackBar } from '@angular/material/snack-bar';
import { DatePipe } from '@angular/common';
import { EntityService } from '../../../core/api/entity.service';
import {
  ClientCategory, KnowledgeExperienceLevel, LegalEntity, RiskTolerance, SuitabilityAssessment
} from '../../../core/models';

/**
 * MiFID II client classification + suitability history (F-BLOCKER-11) — previously no
 * retail/professional/ECP flag existed anywhere and there was no way to record or view a
 * suitability assessment.
 */
@Component({
  selector: 'app-mifid-classification',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule, MatButtonModule, MatIconModule, MatDialogModule, MatFormFieldModule,
            MatInputModule, MatSelectModule, MatCheckboxModule, DatePipe],
  template: `
    <div class="mc-shell">
      <div class="mc-header">
        <h3 class="mc-title">MiFID II classification</h3>
        <button mat-stroked-button (click)="load()">
          <mat-icon>refresh</mat-icon> Refresh
        </button>
      </div>

      @if (loading) {
        <p class="dimmed" style="text-align:center;padding:24px">Loading…</p>
      } @else {
        <div class="classification-card">
          <div>
            <span class="dimmed small">Client category</span>
            <div class="category-value">
              @if (entity?.clientCategory) {
                <span class="category-badge" [class]="entity!.clientCategory!.toLowerCase()">{{ entity!.clientCategory }}</span>
                <span class="dimmed small">classified {{ entity!.clientCategoryClassifiedAt | date:'dd MMM yyyy' }}</span>
              } @else {
                <span class="category-badge unclassified">NOT CLASSIFIED</span>
              }
            </div>
          </div>
          <button mat-raised-button color="primary" (click)="openClassifyDialog()">
            {{ entity?.clientCategory ? 'Reclassify' : 'Classify client' }}
          </button>
        </div>

        <div class="mc-header" style="margin-top:2rem">
          <h3 class="mc-title">Suitability assessment history</h3>
          <button mat-stroked-button color="primary" (click)="openAssessDialog()">
            <mat-icon>add</mat-icon> New assessment
          </button>
        </div>

        @if (assessments.length === 0) {
          <div class="empty-state">
            <mat-icon class="empty-icon">assignment_late</mat-icon>
            <p>No suitability assessment on file yet.</p>
          </div>
        } @else {
          <div class="mc-table">
            <div class="mc-row header">
              <span>Assessed</span>
              <span>Knowledge/experience</span>
              <span>Risk tolerance</span>
              <span>Horizon</span>
              <span>Financial situation</span>
              <span>Notes</span>
            </div>
            @for (a of assessments; track a.id) {
              <div class="mc-row">
                <span class="dimmed">{{ a.assessedAt | date:'dd MMM yyyy' }}</span>
                <span>{{ a.knowledgeExperience }}</span>
                <span>{{ a.riskTolerance }}</span>
                <span>{{ a.investmentHorizonYears !== null ? a.investmentHorizonYears + ' yrs' : '—' }}</span>
                <span>{{ a.financialSituationAdequate ? 'Adequate' : 'Not adequate' }}</span>
                <span class="dimmed small">{{ a.notes || '—' }}</span>
              </div>
            }
          </div>
        }
      }
    </div>

    <ng-template #classifyDialogTpl>
      <h2 mat-dialog-title>Classify Client</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:400px">
        <p class="dimmed small" style="margin:0">
          MiFID II Annex II categorisation — set by the firm, not self-declared by the client.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Client category</mat-label>
          <mat-select [(ngModel)]="selectedCategory">
            <mat-option value="RETAIL">Retail</mat-option>
            <mat-option value="PROFESSIONAL">Professional</mat-option>
            <mat-option value="ELIGIBLE_COUNTERPARTY">Eligible counterparty</mat-option>
          </mat-select>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!selectedCategory" (click)="submitClassify()">
          Save classification
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #assessDialogTpl>
      <h2 mat-dialog-title>New Suitability Assessment</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Knowledge / experience</mat-label>
          <mat-select [(ngModel)]="assessForm.knowledgeExperience">
            <mat-option value="NONE">None</mat-option>
            <mat-option value="BASIC">Basic</mat-option>
            <mat-option value="ADVANCED">Advanced</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Risk tolerance</mat-label>
          <mat-select [(ngModel)]="assessForm.riskTolerance">
            <mat-option value="LOW">Low</mat-option>
            <mat-option value="MEDIUM">Medium</mat-option>
            <mat-option value="HIGH">High</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Investment horizon (years)</mat-label>
          <input matInput type="number" min="0" [(ngModel)]="assessForm.investmentHorizonYears">
        </mat-form-field>
        <mat-checkbox [(ngModel)]="assessForm.financialSituationAdequate">
          Financial situation assessed as adequate for the products invested in
        </mat-checkbox>
        <mat-form-field appearance="outline">
          <mat-label>Notes</mat-label>
          <textarea matInput rows="3" [(ngModel)]="assessForm.notes"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" (click)="submitAssess()">
          Save assessment
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    :host { display: block; }
    .mc-shell { padding: 1.5rem 0; }
    .mc-header { display: flex; align-items: center; justify-content: space-between; margin-bottom: 1.25rem; }
    .mc-title { font-size: 1rem; font-weight: 700; margin: 0; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: .75rem; }
    .empty-state { display: flex; flex-direction: column; align-items: center; padding: 3rem 0; color: var(--rw-text-secondary); }
    .empty-icon { font-size: 2.5rem; height: 2.5rem; width: 2.5rem; margin-bottom: .75rem; opacity: .6; }

    .classification-card {
      display: flex; align-items: center; justify-content: space-between;
      padding: 1rem 1.25rem; border: 1px solid var(--rw-border); border-radius: 8px;
    }
    .category-value { display: flex; align-items: center; gap: 10px; margin-top: 4px; }
    .category-badge {
      display: inline-flex; align-items: center; padding: .25rem .625rem; border-radius: 4px;
      font-size: .75rem; font-weight: 700; letter-spacing: .02em;
    }
    .category-badge.retail { background: rgba(96,165,250,.15); color: #60a5fa; }
    .category-badge.professional { background: rgba(74,222,128,.15); color: #4ade80; }
    .category-badge.eligible_counterparty { background: rgba(167,139,250,.15); color: #a78bfa; }
    .category-badge.unclassified { background: rgba(245,158,11,.15); color: #f59e0b; }

    .mc-table { display: flex; flex-direction: column; }
    .mc-row {
      display: grid;
      grid-template-columns: 110px 150px 120px 90px 130px 1fr;
      gap: .5rem;
      align-items: center;
      padding: .625rem .5rem;
      border-bottom: 1px solid var(--rw-border);
      font-size: .8125rem;
    }
    .mc-row.header {
      font-size: .6875rem;
      letter-spacing: .06em;
      text-transform: uppercase;
      color: var(--rw-text-muted);
    }
  `],
})
export class MifidClassificationComponent implements OnInit {
  @Input() entityId!: string;
  @ViewChild('classifyDialogTpl') classifyDialogTpl!: TemplateRef<unknown>;
  @ViewChild('assessDialogTpl') assessDialogTpl!: TemplateRef<unknown>;

  private readonly entityService = inject(EntityService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  entity: LegalEntity | null = null;
  assessments: SuitabilityAssessment[] = [];
  loading = false;

  selectedCategory: ClientCategory | null = null;

  assessForm: {
    knowledgeExperience: KnowledgeExperienceLevel;
    riskTolerance: RiskTolerance;
    investmentHorizonYears: number | null;
    financialSituationAdequate: boolean;
    notes: string;
  } = { knowledgeExperience: 'NONE', riskTolerance: 'LOW', investmentHorizonYears: null, financialSituationAdequate: false, notes: '' };

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.entityService.getEntity(this.entityId).subscribe({
      next: (entity) => {
        this.entity = entity;
        this.entityService.listSuitabilityAssessments(this.entityId).subscribe({
          next: (assessments) => {
            this.assessments = assessments;
            this.loading = false;
            this.cdr.markForCheck();
          },
          error: () => { this.loading = false; this.cdr.markForCheck(); },
        });
      },
      error: () => { this.loading = false; this.cdr.markForCheck(); },
    });
  }

  openClassifyDialog(): void {
    this.selectedCategory = this.entity?.clientCategory ?? null;
    this.dialog.open(this.classifyDialogTpl, { width: '460px' });
  }

  submitClassify(): void {
    if (!this.selectedCategory) return;
    this.dialog.closeAll();
    this.entityService.classifyClient(this.entityId, this.selectedCategory).subscribe({
      next: () => {
        this.snackBar.open('Client classified.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to classify client.', 'Dismiss', { duration: 5000 }),
    });
  }

  openAssessDialog(): void {
    this.assessForm = { knowledgeExperience: 'NONE', riskTolerance: 'LOW', investmentHorizonYears: null, financialSituationAdequate: false, notes: '' };
    this.dialog.open(this.assessDialogTpl, { width: '480px' });
  }

  submitAssess(): void {
    this.dialog.closeAll();
    this.entityService.recordSuitabilityAssessment(this.entityId, {
      knowledgeExperience: this.assessForm.knowledgeExperience,
      riskTolerance: this.assessForm.riskTolerance,
      investmentHorizonYears: this.assessForm.investmentHorizonYears,
      financialSituationAdequate: this.assessForm.financialSituationAdequate,
      notes: this.assessForm.notes || null,
    }).subscribe({
      next: () => {
        this.snackBar.open('Suitability assessment recorded.', 'Dismiss', { duration: 4000 });
        this.load();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record assessment.', 'Dismiss', { duration: 5000 }),
    });
  }
}
