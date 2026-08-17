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
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { ScrollingModule } from '@angular/cdk/scrolling';

import { PageEvent } from '@angular/material/paginator';
import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { OrgIdentityService } from '../../../core/api/org-identity.service';
import { EntityService } from '../../../core/api/entity.service';
import { ChainService } from '../../../core/api/chain.service';
import { ChainHealth, LegalEntity, OrgRegistrationView } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

@Component({
  selector: 'app-organization-list',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatSelectModule,
    MatTooltipModule,
    ScrollingModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  styles: [``],
  template: `
    <app-page-header
      title="Organizations"
      subtitle="Onchain organizations — every participant wallet belongs to exactly one org per chain">
      <button mat-raised-button color="primary" (click)="openRegisterDialog()">
        <mat-icon>domain_add</mat-icon>
        Register organization
      </button>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="orgs"
      [state]="state"
      (retry)="loadOrgs()"
      [showFilter]="false"
      emptyMessage="No organizations registered yet."
      [actionsTemplate]="rowActions"
      paginationMode="server"
      [totalItems]="totalElements"
      [pageIndex]="pageIndex"
      [pageSize]="pageSize"
      (page)="onPage($event)">
    </rw-data-table>

    <ng-template #rowActions let-org>
      <button mat-icon-button color="primary" (click)="openDetail(org)" matTooltip="Open organization">
        <mat-icon>chevron_right</mat-icon>
      </button>
    </ng-template>

    <!-- Register organization dialog -->
    <ng-template #registerDialog>
      <h2 mat-dialog-title>Register organization</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:440px">
        <p style="margin:0;font-size:13px;color:var(--rw-text-secondary)">
          Deploys the entity's ONCHAINID if needed and registers it in the OrgRegistry.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Legal entity</mat-label>
          <!-- CDK virtual scroll, not @for: this operator serves many customer legal entities
               (see CLAUDE.md's multi-tenancy note) and the dropdown is fetched unpaginated
               (size: 200) for selection purposes — virtualizing keeps the panel smooth as that
               list grows instead of rendering every <mat-option> up front. -->
          <mat-select [(ngModel)]="selectedEntityId">
            <cdk-virtual-scroll-viewport itemSize="48" style="height: 256px;">
              <mat-option *cdkVirtualFor="let entity of entities" [value]="entity.id">
                {{ entity.currentName }} ({{ entity.entityNumber }})
              </mat-option>
            </cdk-virtual-scroll-viewport>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Chain</mat-label>
          <mat-select [(ngModel)]="selectedChainId">
            @for (chain of chains; track chain.id) {
              <mat-option [value]="chain.id">{{ chain.displayName }} ({{ chain.identifier }})</mat-option>
            }
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Country code (ISO-3166 numeric, optional)</mat-label>
          <input matInput type="number" [(ngModel)]="countryCode" placeholder="e.g. 276 for Germany" />
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!selectedEntityId || !selectedChainId || submitting"
                (click)="submitRegister()">
          <mat-icon>domain_add</mat-icon>
          Register
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class OrganizationListComponent implements OnInit {
  @ViewChild('registerDialog') registerDialogTpl!: TemplateRef<unknown>;

  private readonly orgIdentityService = inject(OrgIdentityService);
  private readonly entityService = inject(EntityService);
  private readonly chainService = inject(ChainService);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  orgs: OrgRegistrationView[] = [];
  state: AsyncSectionStatus = 'pending';
  pageIndex = 0;
  pageSize = 20;
  totalElements = 0;

  entities: LegalEntity[] = [];
  chains: ChainHealth[] = [];
  selectedEntityId: string | null = null;
  selectedChainId: string | null = null;
  countryCode: number | null = null;
  submitting = false;

  readonly columns: TableColumn[] = [
    { key: 'entityName', header: 'Entity', cell: (r: OrgRegistrationView) => r.entityName ?? r.legalEntityId },
    { key: 'orgAddress', header: 'Org ONCHAINID', cell: (r: OrgRegistrationView) => r.orgAddress, type: 'mono' },
    { key: 'chainIdentifier', header: 'Chain', cell: (r: OrgRegistrationView) => r.chainIdentifier ?? '—' },
    { key: 'status', header: 'Status', cell: (r: OrgRegistrationView) => r.status, type: 'badge' },
    { key: 'activeMemberCount', header: 'Members', cell: (r: OrgRegistrationView) => `${r.activeMemberCount}`, type: 'number' },
    { key: 'createdAt', header: 'Registered', cell: (r: OrgRegistrationView) => r.createdAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.loadOrgs();
  }

  loadOrgs(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.orgIdentityService.list({ page: this.pageIndex, size: this.pageSize }).subscribe({
      next: (page) => {
        this.orgs = page.content;
        this.totalElements = page.totalElements;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  onPage(event: PageEvent): void {
    this.pageIndex = event.pageIndex;
    this.pageSize = event.pageSize;
    this.loadOrgs();
  }

  openRegisterDialog(): void {
    this.selectedEntityId = null;
    this.selectedChainId = null;
    this.countryCode = null;

    this.entityService.getEntities({ size: 200 }).subscribe({
      next: (page) => {
        this.entities = page.content;
        this.cdr.markForCheck();
      },
    });
    this.chainService.getHealth().subscribe({
      next: (chains) => {
        this.chains = chains.filter((chain) => chain.chainType === 'EVM' && chain.enabled);
        this.cdr.markForCheck();
      },
    });

    this.dialog.open(this.registerDialogTpl, { width: '500px' });
  }

  submitRegister(): void {
    if (!this.selectedEntityId || !this.selectedChainId) return;
    this.submitting = true;

    this.orgIdentityService
      .register({
        legalEntityId: this.selectedEntityId,
        chainConfigId: this.selectedChainId,
        countryCode: this.countryCode,
      })
      .subscribe({
        next: (org) => {
          this.submitting = false;
          this.dialog.closeAll();
          this.snackBar.open('Organization registration submitted.', 'Dismiss', { duration: 5000 });
          this.loadOrgs();
          this.openDetail(org);
        },
        error: (err) => {
          this.submitting = false;
          this.cdr.markForCheck();
          this.snackBar.open(err?.error?.message ?? 'Failed to register organization.', 'Dismiss', {
            duration: 6000,
          });
        },
      });
  }

  openDetail(org: OrgRegistrationView): void {
    this.router.navigate(['/organizations', org.id]);
  }
}
