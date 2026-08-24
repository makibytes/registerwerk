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
import { DoraService, ProviderRequest } from '../../../core/api/dora.service';
import { IctIncident, ResilienceTest, ThirdPartyProvider } from '../../../core/models';
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
  styles: [`
    .tab-content { padding-top: 20px; }
    .hint-card {
      display: flex;
      gap: 10px;
      align-items: flex-start;
      padding: 12px 16px;
      margin-bottom: 16px;
      border-left: 3px solid var(--rw-accent);
      background: var(--rw-surface-subtle, rgba(245, 158, 11, 0.06));
      border-radius: 4px;
      font-size: 13px;
      color: var(--rw-text-secondary);

      mat-icon { color: var(--rw-accent); font-size: 20px; height: 20px; width: 20px; }
    }
  `],
  template: `
    <app-page-header
      title="DORA ICT Resilience"
      subtitle="Digital Operational Resilience Act — Art. 17 Incidents · Art. 28 Third-Party Providers">
      <button type="button" mat-raised-button color="primary" (click)="openReportDialog()">
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
            (retry)="load()"
            filterPlaceholder="Filter incidents…"
            emptyMessage="No open incidents. All systems operational."
            [actionsTemplate]="incidentActions">
          </rw-data-table>

          <ng-template #incidentActions let-inc>
            <button type="button" mat-icon-button (click)="openUpdateStatusDialog(inc)"
                    matTooltip="Update status / add root cause">
              <mat-icon>edit</mat-icon>
            </button>
            @if (inc.status !== 'REPORTED_TO_AUTHORITY' && inc.status !== 'CLOSED') {
              <button type="button" mat-icon-button (click)="openReportToAuthorityDialog(inc)"
                      matTooltip="Report to BaFin / authority">
                <mat-icon>send</mat-icon>
              </button>
            }
            <button type="button" mat-icon-button (click)="exportIncidentAuthorityReport(inc)"
                    matTooltip="Download Art. 19 authority-report export (CSV)">
              <mat-icon>description</mat-icon>
            </button>
          </ng-template>
        </div>
      </mat-tab>

      <!-- Third-Party Providers -->
      <mat-tab label="Third-Party Providers">
        <div class="tab-content">
          <div style="display:flex;justify-content:flex-end;gap:8px;margin-bottom:12px">
            <button type="button" mat-stroked-button (click)="exportProviderRegister()"
                    matTooltip="Download Art. 28 Register of Information export (CSV)">
              <mat-icon>download</mat-icon>
              Export Register
            </button>
            <button type="button" mat-raised-button color="primary" (click)="openProviderDialog()">
              <mat-icon>add_business</mat-icon>
              Register Provider
            </button>
          </div>
          <rw-data-table
            [columns]="providerColumns"
            [rows]="providers"
            [state]="providersState"
            (retry)="load()"
            filterPlaceholder="Filter providers…"
            emptyMessage="No ICT third-party providers registered."
            [actionsTemplate]="providerActions">
          </rw-data-table>

          <ng-template #providerActions let-p>
            <button type="button" mat-icon-button (click)="openProviderDialog(p)" matTooltip="Edit provider">
              <mat-icon>edit</mat-icon>
            </button>
          </ng-template>
        </div>
      </mat-tab>

      <!-- Resilience Testing -->
      <mat-tab label="Resilience Testing">
        <div class="tab-content">
          <div class="hint-card">
            <mat-icon>info</mat-icon>
            <div>
              Art. 24/25 requires regular digital operational resilience testing — vulnerability
              scans and scenario-based tests for every entity, and threat-led penetration testing
              (TLPT) at least every 3 years for functions/providers designated critical. Record
              each test here as it completes so overdue re-testing shows up automatically below.
            </div>
          </div>
          <div style="display:flex;justify-content:flex-end;margin-bottom:12px">
            <button type="button" mat-raised-button color="primary" (click)="openRecordTestDialog()">
              <mat-icon>fact_check</mat-icon>
              Record Test Result
            </button>
          </div>
          <rw-data-table
            [columns]="resilienceTestColumns"
            [rows]="resilienceTests"
            [state]="resilienceTestsState"
            (retry)="load()"
            filterPlaceholder="Filter tests…"
            emptyMessage="No resilience tests recorded yet."
            [actionsTemplate]="testActions">
          </rw-data-table>

          <ng-template #testActions let-t>
            @if (t.result === 'FINDINGS_OPEN') {
              <button type="button" mat-icon-button (click)="openUpdateTestDialog(t)" matTooltip="Close out findings">
                <mat-icon>fact_check</mat-icon>
              </button>
            }
          </ng-template>
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
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="warn"
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
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
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
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
                (click)="submitReportToAuthority()"
                [disabled]="!authorityForm.authorityRef">
          <mat-icon>send</mat-icon>
          Submit Report
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Record Resilience Test Dialog -->
    <ng-template #recordTestDialog>
      <h2 mat-dialog-title>Record Resilience Test Result</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:460px;padding-top:8px">
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>Test type *</mat-label>
            <mat-select [(ngModel)]="testForm.testType">
              @for (t of testTypes; track t) {
                <mat-option [value]="t">{{ t.replace('_',' ') }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Result *</mat-label>
            <mat-select [(ngModel)]="testForm.result">
              @for (r of testResults; track r) {
                <mat-option [value]="r">{{ r.replace('_',' ') }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Scope *</mat-label>
          <input matInput [(ngModel)]="testForm.scope" placeholder="e.g. T-REX identity registry, EwpgPaymaster" />
        </mat-form-field>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>Performed on *</mat-label>
            <input matInput type="date" [(ngModel)]="testForm.performedAt" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Next due date</mat-label>
            <input matInput type="date" [(ngModel)]="testForm.nextDueDate" />
          </mat-form-field>
        </div>
        <div style="display:flex;align-items:center;gap:8px">
          <input type="checkbox" [(ngModel)]="testForm.tlptRequired" id="tlptRequired" />
          <label for="tlptRequired" style="font-size:13px;cursor:pointer">
            Scope is a designated-critical function/provider (TLPT-in-scope, Art. 26)
          </label>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Tester / firm name</mat-label>
          <input matInput [(ngModel)]="testForm.testerName" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Report reference</mat-label>
          <input matInput [(ngModel)]="testForm.reportRef" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Findings</mat-label>
          <textarea matInput [(ngModel)]="testForm.findings" rows="2"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary"
                (click)="submitRecordTest()"
                [disabled]="!testForm.testType || !testForm.result || !testForm.scope || !testForm.performedAt">
          <mat-icon>save</mat-icon>
          Save
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Register / Edit Provider Dialog -->
    <ng-template #providerDialog>
      <h2 mat-dialog-title>{{ providerForm.id ? 'Edit' : 'Register' }} ICT Third-Party Provider</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:460px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Name *</mat-label>
          <input matInput [(ngModel)]="providerForm.name" />
        </mat-form-field>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>Category</mat-label>
            <input matInput [(ngModel)]="providerForm.category" placeholder="e.g. Cloud infrastructure" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Criticality</mat-label>
            <mat-select [(ngModel)]="providerForm.criticality">
              <mat-option value="STANDARD">Standard</mat-option>
              <mat-option value="IMPORTANT">Important</mat-option>
              <mat-option value="CRITICAL">Critical</mat-option>
            </mat-select>
          </mat-form-field>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>LEI</mat-label>
            <input matInput [(ngModel)]="providerForm.lei" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Country</mat-label>
            <input matInput [(ngModel)]="providerForm.country" placeholder="DE" />
          </mat-form-field>
        </div>
        <div style="display:grid;grid-template-columns:1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>Contract start</mat-label>
            <input matInput type="date" [(ngModel)]="providerForm.contractStart" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>Contract end</mat-label>
            <input matInput type="date" [(ngModel)]="providerForm.contractEnd" />
          </mat-form-field>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Primary contact</mat-label>
          <input matInput [(ngModel)]="providerForm.primaryContact" />
        </mat-form-field>
        <div style="display:grid;grid-template-columns:1fr 1fr 1fr;gap:12px">
          <mat-form-field appearance="outline">
            <mat-label>SLA availability %</mat-label>
            <input matInput type="number" step="0.01" [(ngModel)]="providerForm.slaAvailabilityPct" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>RTO (hours)</mat-label>
            <input matInput type="number" [(ngModel)]="providerForm.rtoHours" />
          </mat-form-field>
          <mat-form-field appearance="outline">
            <mat-label>RPO (hours)</mat-label>
            <input matInput type="number" [(ngModel)]="providerForm.rpoHours" />
          </mat-form-field>
        </div>
        <div style="display:flex;align-items:center;gap:8px">
          <input type="checkbox" [(ngModel)]="providerForm.subOutsourcing" id="subOutsourcing" />
          <label for="subOutsourcing" style="font-size:13px;cursor:pointer">Uses sub-outsourcing</label>
        </div>
        @if (providerForm.subOutsourcing) {
          <mat-form-field appearance="outline">
            <mat-label>Sub-outsourcing details</mat-label>
            <textarea matInput rows="2" [(ngModel)]="providerForm.subOutsourcingDetails"></textarea>
          </mat-form-field>
        }
        <div style="display:flex;align-items:center;gap:8px">
          <input type="checkbox" [(ngModel)]="providerForm.notifiedAuthority" id="notifiedAuthority" />
          <label for="notifiedAuthority" style="font-size:13px;cursor:pointer">
            Competent authority notified (Art. 28(3))
          </label>
        </div>
        <mat-form-field appearance="outline">
          <mat-label>Notes</mat-label>
          <textarea matInput rows="2" [(ngModel)]="providerForm.notes"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" (click)="submitProvider()" [disabled]="!providerForm.name.trim()">
          <mat-icon>save</mat-icon>
          Save
        </button>
      </mat-dialog-actions>
    </ng-template>

    <!-- Update Resilience Test Result Dialog -->
    <ng-template #updateTestDialog>
      <h2 mat-dialog-title>Update Resilience Test Result</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;min-width:420px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Result *</mat-label>
          <mat-select [(ngModel)]="updateTestForm.result">
            @for (r of testResults; track r) {
              <mat-option [value]="r">{{ r.replace('_',' ') }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Findings</mat-label>
          <textarea matInput rows="3" [(ngModel)]="updateTestForm.findings"></textarea>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Report reference</mat-label>
          <input matInput [(ngModel)]="updateTestForm.reportRef" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button type="button" mat-stroked-button mat-dialog-close>Cancel</button>
        <button type="button" mat-raised-button color="primary" (click)="submitUpdateTest()" [disabled]="!updateTestForm.result">
          <mat-icon>save</mat-icon>
          Save
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class DoraDashboardComponent implements OnInit {
  @ViewChild('reportDialog') reportDialogTpl!: TemplateRef<unknown>;
  @ViewChild('updateStatusDialog') updateStatusDialogTpl!: TemplateRef<unknown>;
  @ViewChild('reportToAuthorityDialog') reportToAuthorityDialogTpl!: TemplateRef<unknown>;
  @ViewChild('recordTestDialog') recordTestDialogTpl!: TemplateRef<unknown>;
  @ViewChild('providerDialog') providerDialogTpl!: TemplateRef<unknown>;
  @ViewChild('updateTestDialog') updateTestDialogTpl!: TemplateRef<unknown>;

  private readonly doraService = inject(DoraService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  incidents: IctIncident[] = [];
  providers: ThirdPartyProvider[] = [];
  resilienceTests: ResilienceTest[] = [];
  incidentsState: AsyncSectionStatus = 'pending';
  providersState: AsyncSectionStatus = 'pending';
  resilienceTestsState: AsyncSectionStatus = 'pending';

  selectedIncidentId: string | null = null;

  readonly categories = ['DATA_BREACH', 'SYSTEM_OUTAGE', 'RANSOMWARE', 'THIRD_PARTY_FAILURE', 'OTHER'];
  readonly severities = ['LOW', 'MEDIUM', 'HIGH', 'MAJOR'];
  readonly statuses = ['INVESTIGATING', 'CONTAINED', 'RESOLVED', 'REPORTED_TO_AUTHORITY', 'CLOSED'];
  readonly testTypes = ['VULNERABILITY_SCAN', 'SCENARIO_BASED', 'TLPT'];
  readonly testResults = ['PASSED', 'FINDINGS_OPEN', 'FAILED'];

  incidentForm = { title: '', description: '', category: '', severity: '' };
  statusForm = { status: '', rootCause: '', remediationSteps: '' };
  authorityForm = { authorityRef: '', isFinalReport: false };
  testForm: {
    testType: string; result: string; scope: string; performedAt: string; nextDueDate: string;
    tlptRequired: boolean; testerName: string; reportRef: string; findings: string;
  } = { testType: '', result: '', scope: '', performedAt: '', nextDueDate: '',
        tlptRequired: false, testerName: '', reportRef: '', findings: '' };

  providerForm: {
    id: string | null; name: string; category: string; criticality: string; lei: string;
    country: string; contractStart: string; contractEnd: string; subOutsourcing: boolean;
    subOutsourcingDetails: string; primaryContact: string; slaAvailabilityPct: number | null;
    rtoHours: number | null; rpoHours: number | null; notifiedAuthority: boolean; notes: string;
  } = this.emptyProviderForm();

  updateTestForm: { id: string | null; result: string; findings: string; reportRef: string } =
    { id: null, result: '', findings: '', reportRef: '' };

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

  readonly resilienceTestColumns: TableColumn[] = [
    {
      key: 'result',
      header: 'Result',
      cell: (t: ResilienceTest) => t.result.replace('_', ' '),
      type: 'badge',
    },
    {
      key: 'testType',
      header: 'Test Type',
      cell: (t: ResilienceTest) => t.testType.replace('_', ' '),
    },
    {
      key: 'scope',
      header: 'Scope',
      cell: (t: ResilienceTest) => t.scope,
    },
    {
      key: 'tlptRequired',
      header: 'TLPT-in-scope',
      cell: (t: ResilienceTest) => t.tlptRequired ? 'YES' : 'NO',
    },
    {
      key: 'performedAt',
      header: 'Performed',
      cell: (t: ResilienceTest) => t.performedAt,
      type: 'date',
    },
    {
      key: 'nextDueDate',
      header: 'Next Due',
      cell: (t: ResilienceTest) => t.nextDueDate,
      type: 'date',
    },
  ];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.incidentsState = 'pending';
    this.providersState = 'pending';
    this.resilienceTestsState = 'pending';
    this.cdr.markForCheck();

    forkJoin({
      incidents: this.doraService.listOpenIncidents(),
      providers: this.doraService.listProviders(),
      resilienceTests: this.doraService.listResilienceTests(),
    }).subscribe({
      next: ({ incidents, providers, resilienceTests }) => {
        this.incidents = incidents;
        this.providers = providers;
        this.resilienceTests = resilienceTests;
        this.incidentsState = 'ready';
        this.providersState = 'ready';
        this.resilienceTestsState = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.incidentsState = 'error';
        this.providersState = 'error';
        this.resilienceTestsState = 'error';
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

  exportIncidentAuthorityReport(incident: IctIncident): void {
    this.doraService.exportIncidentAuthorityReport(incident.id).subscribe({
      next: (csv) => this.triggerDownload(csv, `dora-incident-report-${incident.id}.csv`),
      error: () => this.snackBar.open('Failed to generate the authority-report export.', 'Dismiss', { duration: 4000 }),
    });
  }

  exportProviderRegister(): void {
    this.doraService.exportProviderRegister().subscribe({
      next: (csv) => this.triggerDownload(csv, 'dora-register-of-information.csv'),
      error: () => this.snackBar.open('Failed to generate the register export.', 'Dismiss', { duration: 4000 }),
    });
  }

  private triggerDownload(blob: Blob, filename: string): void {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = filename;
    link.click();
    URL.revokeObjectURL(url);
  }

  openRecordTestDialog(): void {
    this.testForm = { testType: 'VULNERABILITY_SCAN', result: 'PASSED', scope: '', performedAt: '',
                       nextDueDate: '', tlptRequired: false, testerName: '', reportRef: '', findings: '' };
    this.dialog.open(this.recordTestDialogTpl, { width: '520px' });
  }

  submitRecordTest(): void {
    this.dialog.closeAll();
    this.doraService.recordResilienceTest({
      testType: this.testForm.testType,
      scope: this.testForm.scope,
      tlptRequired: this.testForm.tlptRequired,
      performedAt: this.testForm.performedAt,
      nextDueDate: this.testForm.nextDueDate || undefined,
      result: this.testForm.result,
      findings: this.testForm.findings || undefined,
      testerName: this.testForm.testerName || undefined,
      reportRef: this.testForm.reportRef || undefined,
    }).subscribe({
      next: (test) => {
        this.resilienceTests = [test, ...this.resilienceTests];
        this.cdr.markForCheck();
        this.snackBar.open(`Resilience test recorded (${test.result}).`, 'Dismiss', { duration: 5000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to record test.', 'Dismiss', { duration: 6000 }),
    });
  }

  private emptyProviderForm() {
    return {
      id: null as string | null, name: '', category: '', criticality: 'STANDARD', lei: '',
      country: '', contractStart: '', contractEnd: '', subOutsourcing: false,
      subOutsourcingDetails: '', primaryContact: '', slaAvailabilityPct: null as number | null,
      rtoHours: null as number | null, rpoHours: null as number | null,
      notifiedAuthority: false, notes: '',
    };
  }

  openProviderDialog(provider?: ThirdPartyProvider): void {
    this.providerForm = provider ? {
      id: provider.id, name: provider.name, category: provider.category ?? '',
      criticality: provider.criticality, lei: provider.lei ?? '', country: provider.country ?? '',
      contractStart: provider.contractStart ?? '', contractEnd: provider.contractEnd ?? '',
      subOutsourcing: provider.subOutsourcing, subOutsourcingDetails: provider.subOutsourcingDetails ?? '',
      primaryContact: provider.primaryContact ?? '', slaAvailabilityPct: provider.slaAvailabilityPct,
      rtoHours: provider.rtoHours, rpoHours: provider.rpoHours,
      notifiedAuthority: provider.notifiedAuthority, notes: provider.notes ?? '',
    } : this.emptyProviderForm();
    this.dialog.open(this.providerDialogTpl, { width: '520px' });
  }

  submitProvider(): void {
    if (!this.providerForm.name.trim()) return;
    this.dialog.closeAll();

    const body: ProviderRequest = {
      name: this.providerForm.name.trim(),
      category: this.providerForm.category || undefined,
      criticality: this.providerForm.criticality || undefined,
      lei: this.providerForm.lei || undefined,
      country: this.providerForm.country || undefined,
      contractStart: this.providerForm.contractStart || undefined,
      contractEnd: this.providerForm.contractEnd || undefined,
      subOutsourcing: this.providerForm.subOutsourcing,
      subOutsourcingDetails: this.providerForm.subOutsourcingDetails || undefined,
      primaryContact: this.providerForm.primaryContact || undefined,
      slaAvailabilityPct: this.providerForm.slaAvailabilityPct ?? undefined,
      rtoHours: this.providerForm.rtoHours ?? undefined,
      rpoHours: this.providerForm.rpoHours ?? undefined,
      notifiedAuthority: this.providerForm.notifiedAuthority,
      notes: this.providerForm.notes || undefined,
    };

    const request$ = this.providerForm.id
      ? this.doraService.updateProvider(this.providerForm.id, body)
      : this.doraService.createProvider(body);

    request$.subscribe({
      next: (provider) => {
        this.providers = this.providerForm.id
          ? this.providers.map(p => p.id === provider.id ? provider : p)
          : [provider, ...this.providers];
        this.cdr.markForCheck();
        this.snackBar.open(`Provider "${provider.name}" saved.`, 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to save provider.', 'Dismiss', { duration: 6000 }),
    });
  }

  openUpdateTestDialog(test: ResilienceTest): void {
    this.updateTestForm = { id: test.id, result: test.result, findings: test.findings ?? '', reportRef: test.reportRef ?? '' };
    this.dialog.open(this.updateTestDialogTpl, { width: '480px' });
  }

  submitUpdateTest(): void {
    if (!this.updateTestForm.id || !this.updateTestForm.result) return;
    this.dialog.closeAll();

    this.doraService.updateResilienceTest(this.updateTestForm.id, {
      result: this.updateTestForm.result,
      findings: this.updateTestForm.findings || undefined,
      reportRef: this.updateTestForm.reportRef || undefined,
    }).subscribe({
      next: (updated) => {
        this.resilienceTests = this.resilienceTests.map(t => t.id === updated.id ? updated : t);
        this.cdr.markForCheck();
        this.snackBar.open(`Test result updated (${updated.result}).`, 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to update test.', 'Dismiss', { duration: 6000 }),
    });
  }
}
