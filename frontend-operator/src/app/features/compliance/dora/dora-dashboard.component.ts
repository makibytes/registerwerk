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
import { MatCardModule } from '@angular/material/card';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatTooltipModule } from '@angular/material/tooltip';
import { forkJoin } from 'rxjs';


import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { DoraService } from '../../../core/api/dora.service';
import { IctIncident, ThirdPartyProvider } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-dora-dashboard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTabsModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [`.tab-content { padding-top: 20px; }`],
  template: `
    <app-page-header
      title="DORA ICT Resilience"
      subtitle="Digital Operational Resilience Act — Art. 17 Incidents · Art. 28 Third-Party Providers">
      <button mat-raised-button color="primary" (click)="openReportDialog()">
        <mat-icon>warning_amber</mat-icon>
        Report Incident
      </button>
    </app-page-header>

    <mat-tab-group animationDuration="200ms">

      <!-- ICT Incidents -->
      <mat-tab label="ICT Incidents">
        <div class="tab-content">
          <rw-data-table
            [columns]="incidentColumns"
            [rows]="incidents"
            [state]="incidentsState"
            filterPlaceholder="Filter incidents…"
            emptyMessage="No open incidents. All systems operational."
            [actionsTemplate]="incidentActions">
          </rw-data-table>

          <ng-template #incidentActions let-inc>
            <button mat-icon-button (click)="openUpdateStatusDialog(inc)"
                    matTooltip="Update status / add root cause">
              <mat-icon>edit</mat-icon>
            </button>
            @if (inc.status !== 'REPORTED_TO_AUTHORITY' && inc.status !== 'CLOSED') {
              <button mat-icon-button (click)="openReportToAuthorityDialog(inc)"
                      matTooltip="Report to BaFin / authority">
                <mat-icon>send</mat-icon>
              </button>
            }
          </ng-template>
        </div>
      </mat-tab>

      <!-- Third-Party Providers -->
      <mat-tab label="Third-Party Providers">
        <div class="tab-content">
          <rw-data-table
            [columns]="providerColumns"
            [rows]="providers"
            [state]="providersState"
            filterPlaceholder="Filter providers…"
            emptyMessage="No ICT third-party providers registered.">
          </rw-data-table>
        </div>
      </mat-tab>

    </mat-tab-group>

    <!-- Report Incident Dialog -->
    <ng-template #reportDialog>
      <h2 mat-dialog-title>Report ICT Incident</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:440px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Title *</mat-label>
          <input matInput [(ngModel)]="incidentForm.title" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput [(ngModel)]="incidentForm.description" rows="3"></textarea>
        </mat-form-field>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>Category *</mat-label>
            <mat-select [(ngModel)]="incidentForm.category">
              @for (c of categories; track c) {
                <mat-option [value]="c">{{ c.replace('_',' ') }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Severity *</mat-label>
            <mat-select [(ngModel)]="incidentForm.severity">
              @for (s of severities; track s) {
                <mat-option [value]="s">{{ s }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn"
                (click)="submitReport()"
                [disabled]="!incidentForm.title || !incidentForm.category || !incidentForm.severity">
          <mat-icon>warning_amber</mat-icon>
          Report
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Update Status Dialog -->
    <ng-template #updateStatusDialog>
      <h2 mat-dialog-title>Update Incident Status</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:440px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>New status *</mat-label>
          <mat-select [(ngModel)]="statusForm.status">
            @for (s of statuses; track s) {
              <mat-option [value]="s">{{ s.replace('_',' ') }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Root cause</mat-label>
          <textarea matInput [(ngModel)]="statusForm.rootCause" rows="3"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Remediation steps</mat-label>
          <textarea matInput [(ngModel)]="statusForm.remediationSteps" rows="3"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                (click)="submitStatusUpdate()"
                [disabled]="!statusForm.status">
          <mat-icon>save</mat-icon>
          Update
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Report to Authority Dialog -->
    <ng-template #reportToAuthorityDialog>
      <h2 mat-dialog-title>Report to Competent Authority</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:400px;padding-top:8px">
        <div style="font-size:13px;color:var(--rw-text-secondary);margin-bottom:8px">
          DORA Art. 19 — Major ICT incidents must be reported to the competent authority
          (BaFin / ECB) within 24 hours (initial) and 72 hours (detailed).
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Authority reference / ticket number *</mat-label>
          <input matInput [(ngModel)]="authorityForm.authorityRef" />
        </mat-form-field>
        <div style="display:flex;align-items:center;gap:8px">
          <input type="checkbox" [(ngModel)]="authorityForm.isFinalReport" id="finalReport" />
          <label for="finalReport" style="font-size:13px;cursor:pointer">
            This is the final report (Art. 19 para. 4c)
          </label>
        </div>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                (click)="submitReportToAuthority()"
                [disabled]="!authorityForm.authorityRef">
          <mat-icon>send</mat-icon>
          Submit Report
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class DoraDashboardComponent implements OnInit {
  @ViewChild('reportDialog') reportDialogTpl!: TemplateRef<unknown>;
  @ViewChild('updateStatusDialog') updateStatusDialogTpl!: TemplateRef<unknown>;
  @ViewChild('reportToAuthorityDialog') reportToAuthorityDialogTpl!: TemplateRef<unknown>;

  private readonly doraService = inject(DoraService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  incidents: IctIncident[] = [];
  providers: ThirdPartyProvider[] = [];
  incidentsState: AsyncSectionStatus = 'pending';
  providersState: AsyncSectionStatus = 'pending';

  selectedIncidentId: string | null = null;

  readonly categories = ['DATA_BREACH', 'SYSTEM_OUTAGE', 'RANSOMWARE', 'THIRD_PARTY_FAILURE', 'OTHER'];
  readonly severities = ['LOW', 'MEDIUM', 'HIGH', 'MAJOR'];
  readonly statuses = ['INVESTIGATING', 'CONTAINED', 'RESOLVED', 'REPORTED_TO_AUTHORITY', 'CLOSED'];

  incidentForm = { title: '', description: '', category: '', severity: '' };
  statusForm = { status: '', rootCause: '', remediationSteps: '' };
  authorityForm = { authorityRef: '', isFinalReport: false };

  readonly incidentColumns: TableColumn[] = [
    {
      key: 'severity',
      header: 'Severity',
      cell: (i: IctIncident) => i.severity,
      type: 'badge',
    },
    {
      key: 'status',
      header: 'Status',
      cell: (i: IctIncident) => i.status,
      type: 'badge',
    },
    {
      key: 'title',
      header: 'Title',
      cell: (i: IctIncident) => i.title,
    },
    {
      key: 'category',
      header: 'Category',
      cell: (i: IctIncident) => i.category.replace('_', ' '),
    },
    {
      key: 'detectedAt',
      header: 'Detected',
      cell: (i: IctIncident) => i.detectedAt,
      type: 'date',
    },
    {
      key: 'initialReportDeadline',
      header: 'Initial Report Deadline',
      cell: (i: IctIncident) => i.initialReportDeadline,
      type: 'date',
    },
  ];

  readonly providerColumns: TableColumn[] = [
    {
      key: 'criticality',
      header: 'Criticality',
      cell: (p: ThirdPartyProvider) => p.criticality,
      type: 'badge',
    },
    {
      key: 'name',
      header: 'Provider',
      cell: (p: ThirdPartyProvider) => p.name,
    },
    {
      key: 'category',
      header: 'Category',
      cell: (p: ThirdPartyProvider) => p.category,
    },
    {
      key: 'country',
      header: 'Country',
      cell: (p: ThirdPartyProvider) => p.country ?? '—',
    },
    {
      key: 'contractEnd',
      header: 'Contract Ends',
      cell: (p: ThirdPartyProvider) => p.contractEnd,
      type: 'date',
    },
    {
      key: 'notifiedAuthority',
      header: 'Authority Notified',
      cell: (p: ThirdPartyProvider) => p.notifiedAuthority ? 'YES' : 'NO',
    },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.incidentsState = 'pending';
    this.providersState = 'pending';
    this.cdr.markForCheck();

    forkJoin({
      incidents: this.doraService.listOpenIncidents(),
      providers: this.doraService.listProviders(),
    }).subscribe({
      next: ({ incidents, providers }) => {
        this.incidents = incidents;
        this.providers = providers;
        this.incidentsState = 'ready';
        this.providersState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.incidentsState = 'error';
        this.providersState = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  openReportDialog(): void {
    this.incidentForm = { title: '', description: '', category: '', severity: 'HIGH' };
    this.dialog.open(this.reportDialogTpl, { width: '500px' });
  }

  openUpdateStatusDialog(incident: IctIncident): void {
    this.selectedIncidentId = incident.id;
    this.statusForm = { status: incident.status, rootCause: incident.rootCause ?? '', remediationSteps: incident.remediationSteps ?? '' };
    this.dialog.open(this.updateStatusDialogTpl, { width: '500px' });
  }

  openReportToAuthorityDialog(incident: IctIncident): void {
    this.selectedIncidentId = incident.id;
    this.authorityForm = { authorityRef: '', isFinalReport: false };
    this.dialog.open(this.reportToAuthorityDialogTpl, { width: '480px' });
  }

  submitReport(): void {
    this.dialog.closeAll();
    this.doraService.reportIncident({
      title: this.incidentForm.title,
      description: this.incidentForm.description || undefined,
      category: this.incidentForm.category,
      severity: this.incidentForm.severity,
    }).subscribe({
      next: (incident) => {
        this.incidents = [incident, ...this.incidents];
        this.cdr.markForCheck();
        this.snackBar.open(`Incident "${incident.title}" reported.`, 'Dismiss', { duration: 5000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to report incident.', 'Dismiss', { duration: 6000 }),
    });
  }

  submitStatusUpdate(): void {
    if (!this.selectedIncidentId) return;
    this.dialog.closeAll();
    this.doraService.updateStatus(this.selectedIncidentId, {
      status: this.statusForm.status,
      rootCause: this.statusForm.rootCause || undefined,
      remediationSteps: this.statusForm.remediationSteps || undefined,
    }).subscribe({
      next: (updated) => {
        this.incidents = this.incidents.map(i => i.id === updated.id ? updated : i);
        this.cdr.markForCheck();
        this.snackBar.open('Incident status updated.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to update status.', 'Dismiss', { duration: 6000 }),
    });
  }

  submitReportToAuthority(): void {
    if (!this.selectedIncidentId) return;
    this.dialog.closeAll();
    this.doraService.reportToAuthority(this.selectedIncidentId, {
      authorityRef: this.authorityForm.authorityRef,
      isFinalReport: this.authorityForm.isFinalReport,
    }).subscribe({
      next: (updated) => {
        this.incidents = this.incidents.map(i => i.id === updated.id ? updated : i);
        this.cdr.markForCheck();
        this.snackBar.open(
          `Reported to authority (ref: ${this.authorityForm.authorityRef}).`,
          'Dismiss',
          { duration: 5000 },
        );
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to report to authority.', 'Dismiss', { duration: 6000 }),
    });
  }
}
