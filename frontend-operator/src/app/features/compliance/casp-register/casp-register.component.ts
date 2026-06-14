import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import {
  CaspAuthorization,
  CaspAuthorizationStatus,
  CaspImportResult,
  CaspRegisterService,
} from '../../../core/api/casp-register.service';
import { AsyncSectionStatus } from '../../../core/async/async-section';

const MICA_ENFORCEMENT_DATE = new Date('2026-07-01T00:00:00');

/**
 * Counterparty CASP authorization register (MiCA Reg (EU) 2023/1114).
 *
 * The EU-wide transitional period ends on 1 July 2026 (ESMA, 17 Apr 2026).
 * Compliance officers mirror the ESMA / NCA register status of Travel Rule
 * counterparties here; the backend rejects outbound transfers to counterparties
 * that are not authorized once the transitional period has ended.
 */
@Component({
  selector: 'app-casp-register',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatDialogModule,
    MatFormFieldModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`
    .deadline-banner {
      display: flex;
      align-items: center;
      gap: 14px;
      margin-bottom: 20px;
      padding: 14px 18px;
      border-radius: var(--rw-radius-md);
      border: 1px solid var(--rw-border);
      background: var(--rw-surface);
      box-shadow: var(--rw-shadow-xs);
      border-left: 4px solid var(--rw-pending-fg);
    }
    .deadline-banner.past { border-left-color: var(--rw-rejected-fg); }
    .deadline-banner mat-icon { color: var(--rw-pending-fg); flex-shrink: 0; }
    .deadline-banner.past mat-icon { color: var(--rw-rejected-fg); }
    .deadline-text { flex: 1; min-width: 0; }
    .deadline-title { font-weight: 700; font-size: 14px; margin: 0 0 2px; }
    .deadline-detail { color: var(--rw-text-secondary); font-size: 13px; margin: 0; }
    .deadline-count {
      font-variant-numeric: tabular-nums;
      font-weight: 700;
    }
  `],
  template: `
    <app-page-header
      title="CASP Authorization Register"
      subtitle="MiCA Reg (EU) 2023/1114 — counterparty authorization status for Travel Rule transfers">
      <button mat-stroked-button (click)="csvInput.click()"
              matTooltip="Import a CSV export of the ESMA MiCA register (columns: legal_name, vasp_did or lei, status, …)">
        <mat-icon>upload_file</mat-icon>
        Import CSV
      </button>
      <button mat-raised-button color="primary" (click)="openUpsertDialog()">
        <mat-icon>add</mat-icon>
        Add Counterparty
      </button>
      <input #csvInput type="file" accept=".csv,text/csv" hidden
             (change)="onCsvSelected($event)" />
    </app-page-header>

    @if (daysUntilCutoff !== null && daysUntilCutoff > 0) {
      <div class="deadline-banner" role="status">
        <mat-icon>schedule</mat-icon>
        <div class="deadline-text">
          <p class="deadline-title">
            MiCA transitional period ends in
            <span class="deadline-count">{{ daysUntilCutoff }}</span>
            {{ daysUntilCutoff === 1 ? 'day' : 'days' }} (1 July 2026)
          </p>
          <p class="deadline-detail">
            @if (transitionalCount > 0) {
              {{ transitionalCount }}
              {{ transitionalCount === 1 ? 'counterparty is' : 'counterparties are' }}
              still on a transitional regime — transfers to them will be blocked from the
              cutoff unless their ESMA authorization is confirmed.
            } @else {
              No counterparties on a transitional regime. Verify new counterparties against
              the ESMA register before adding them.
            }
          </p>
        </div>
      </div>
    } @else if (daysUntilCutoff !== null) {
      <div class="deadline-banner past" role="status">
        <mat-icon>gavel</mat-icon>
        <div class="deadline-text">
          <p class="deadline-title">MiCA transitional period has ended (1 July 2026)</p>
          <p class="deadline-detail">
            Outbound transfers to counterparties without a confirmed CASP authorization
            are rejected. Transitional entries are treated as not authorized.
          </p>
        </div>
      </div>
    }

    <rw-data-table
      [columns]="columns"
      [rows]="entries"
      [state]="state"
      filterPlaceholder="Filter by name, DID, LEI, member state…"
      emptyMessage="No counterparties recorded yet. Add the CASPs you exchange Travel Rule messages with."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-entry>
      <button mat-icon-button color="primary"
              (click)="openUpsertDialog(entry)"
              matTooltip="Edit entry">
        <mat-icon>edit</mat-icon>
      </button>
      <button mat-icon-button color="warn"
              (click)="remove(entry)"
              matTooltip="Delete entry">
        <mat-icon>delete_outline</mat-icon>
      </button>
    </ng-template>

    <ng-template #importResultDialog>
      <h2 mat-dialog-title>CSV Import Result</h2>
      <mat-dialog-content>
        <p>
          <strong>{{ importResult?.created ?? 0 }}</strong> created,
          <strong>{{ importResult?.updated ?? 0 }}</strong> updated,
          <strong [style.color]="(importResult?.failed ?? 0) > 0 ? 'var(--rw-rejected-fg)' : null">
            {{ importResult?.failed ?? 0 }} failed</strong>.
        </p>
        @if (importResult && importResult.errors.length > 0) {
          <p style="margin-bottom:4px;font-weight:600">Errors:</p>
          <ul style="margin:0;padding-left:20px;max-height:240px;overflow:auto;
                     font-size:13px;color:var(--rw-text-secondary)">
            @for (err of importResult.errors; track err) {
              <li>{{ err }}</li>
            }
          </ul>
        }
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-raised-button color="primary" mat-dialog-close>Close</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #upsertDialog>
      <h2 mat-dialog-title>{{ form.id ? 'Edit Counterparty' : 'Add Counterparty' }}</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <mat-form-field appearance="outline">
          <mat-label>VASP DID / identifier</mat-label>
          <input matInput [(ngModel)]="form.vaspDid" [disabled]="!!form.id"
                 placeholder="did:example:casp or LEI-based ID" />
          <mat-hint>Identifier used in the Travel Rule VASP directory</mat-hint>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal name</mat-label>
          <input matInput [(ngModel)]="form.legalName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Status</mat-label>
          <mat-select [(ngModel)]="form.status">
            <mat-option value="AUTHORIZED">Authorized (ESMA register)</mat-option>
            <mat-option value="TRANSITIONAL">Transitional (grandfathering)</mat-option>
            <mat-option value="NOT_AUTHORIZED">Not authorized</mat-option>
            <mat-option value="REVOKED">Authorization revoked</mat-option>
          </mat-select>
        </mat-form-field>
        <div style="display:flex;gap:12px">
          <mat-form-field appearance="outline" style="flex:1">
            <mat-label>LEI (optional)</mat-label>
            <input matInput [(ngModel)]="form.lei" maxlength="20" />
          </mat-form-field>
          <mat-form-field appearance="outline" style="width:140px">
            <mat-label>Member state</mat-label>
            <input matInput [(ngModel)]="form.homeMemberState" maxlength="2"
                   placeholder="DE" style="text-transform:uppercase" />
          </mat-form-field>
        </div>
        <div style="display:flex;gap:12px">
          <mat-form-field appearance="outline" style="flex:1">
            <mat-label>Valid from</mat-label>
            <input matInput type="date" [(ngModel)]="form.validFrom" />
          </mat-form-field>
          <mat-form-field appearance="outline" style="flex:1">
            <mat-label>Valid until</mat-label>
            <input matInput type="date" [(ngModel)]="form.validUntil" />
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Source</mat-label>
          <input matInput [(ngModel)]="form.source"
                 placeholder="e.g. ESMA register, checked 2026-06-10" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Notes (optional)</mat-label>
          <textarea matInput rows="2" [(ngModel)]="form.notes"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions align="end">
        <button mat-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!form.vaspDid.trim() || !form.legalName.trim() || saving"
                (click)="save()">
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class CaspRegisterComponent implements OnInit {
  private readonly service = inject(CaspRegisterService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  @ViewChild('upsertDialog') upsertDialogTpl!: TemplateRef<unknown>;
  @ViewChild('importResultDialog') importResultDialogTpl!: TemplateRef<unknown>;

  entries: CaspAuthorization[] = [];
  state: AsyncSectionStatus = 'pending';
  saving = false;
  importing = false;
  importResult: CaspImportResult | null = null;
  daysUntilCutoff: number | null = null;
  transitionalCount = 0;

  form: CaspAuthorization = this.emptyForm();
  private dialogRef?: MatDialogRef<unknown>;

  readonly columns: TableColumn[] = [
    { key: 'legalName', header: 'Legal Name', cell: (c: CaspAuthorization) => c.legalName },
    { key: 'vaspDid', header: 'VASP DID', cell: (c: CaspAuthorization) => c.vaspDid, type: 'mono' },
    { key: 'lei', header: 'LEI', cell: (c: CaspAuthorization) => c.lei ?? '—', type: 'mono' },
    { key: 'homeMemberState', header: 'State', cell: (c: CaspAuthorization) => c.homeMemberState ?? '—' },
    { key: 'status', header: 'MiCA Status', cell: (c: CaspAuthorization) => c.status, type: 'badge' },
    { key: 'validUntil', header: 'Valid Until', cell: (c: CaspAuthorization) => c.validUntil ?? null, type: 'date' },
    { key: 'source', header: 'Source', cell: (c: CaspAuthorization) => c.source ?? '—' },
  ];

  ngOnInit(): void {
    const msPerDay = 86_400_000;
    this.daysUntilCutoff = Math.ceil(
      (MICA_ENFORCEMENT_DATE.getTime() - Date.now()) / msPerDay,
    );
    this.reload();
  }

  reload(): void {
    this.state = this.entries.length ? 'updating' : 'pending';
    this.service.list().subscribe({
      next: (entries) => {
        this.entries = entries;
        this.transitionalCount = entries.filter(e => e.status === 'TRANSITIONAL').length;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  onCsvSelected(event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = ''; // allow re-selecting the same file
    if (!file) return;
    if (file.size > 5 * 1024 * 1024) {
      this.snackBar.open('File too large (max 5 MB).', 'OK', { duration: 5000 });
      return;
    }
    this.importing = true;
    this.cdr.markForCheck();
    const reader = new FileReader();
    reader.onload = () => {
      this.service.importCsv(String(reader.result ?? '')).subscribe({
        next: (result) => {
          this.importing = false;
          this.importResult = result;
          this.dialog.open(this.importResultDialogTpl, { width: '480px' });
          this.cdr.markForCheck();
          this.reload();
        },
        error: (err) => {
          this.importing = false;
          this.snackBar.open(err?.error?.message ?? 'Import failed.', 'OK', { duration: 5000 });
          this.cdr.markForCheck();
        },
      });
    };
    reader.onerror = () => {
      this.importing = false;
      this.snackBar.open('Could not read the file.', 'OK', { duration: 5000 });
      this.cdr.markForCheck();
    };
    reader.readAsText(file);
  }

  openUpsertDialog(entry?: CaspAuthorization): void {
    this.form = entry ? { ...entry } : this.emptyForm();
    this.dialogRef = this.dialog.open(this.upsertDialogTpl, { width: '520px' });
  }

  save(): void {
    this.saving = true;
    this.service.upsert({
      ...this.form,
      homeMemberState: this.form.homeMemberState?.toUpperCase() || null,
    }).subscribe({
      next: () => {
        this.saving = false;
        this.dialogRef?.close();
        this.snackBar.open('Counterparty saved.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: (err) => {
        this.saving = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to save counterparty.', 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  remove(entry: CaspAuthorization): void {
    if (!entry.id) return;
    if (!confirm(`Delete register entry for ${entry.legalName}? Transfers to this counterparty will then pass with a warning only.`)) {
      return;
    }
    this.service.delete(entry.id).subscribe({
      next: () => {
        this.snackBar.open('Entry deleted.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: () => {
        this.snackBar.open('Failed to delete entry.', 'OK', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  private emptyForm(): CaspAuthorization {
    return {
      vaspDid: '',
      legalName: '',
      lei: null,
      homeMemberState: null,
      status: 'AUTHORIZED' as CaspAuthorizationStatus,
      authorizationId: null,
      validFrom: null,
      validUntil: null,
      source: null,
      notes: null,
    };
  }
}
