import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  TemplateRef,
  ViewChild,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog, MatDialogModule } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatTableModule } from '@angular/material/table';
import { MatTooltipModule } from '@angular/material/tooltip';

import { MarketplaceService } from '../../../core/api/marketplace.service';
import { OrgIdentityService } from '../../../core/api/org-identity.service';
import { DappListingView, OrgRegistrationView } from '../../../core/models';

@Component({
  selector: 'app-my-dapps',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatTableModule,
    MatTooltipModule,
  ],
  styles: [`
    .status-chip {
      display: inline-block;
      padding: 2px 10px;
      border-radius: 999px;
      font-size: 12px;
      font-weight: 600;
      background: var(--rw-pending-bg);
      color: var(--rw-pending-fg);

      &.PUBLISHED { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
      &.APPROVED  { background: var(--rw-approved-bg); color: var(--rw-approved-fg); }
      &.REJECTED, &.DELISTED { background: var(--rw-rejected-bg); color: var(--rw-rejected-fg); }
      &.DEPRECATED { background: var(--rw-revoked-bg); color: var(--rw-revoked-fg); }
    }

    .empty-state {
      text-align: center;
      padding: 60px 16px;
      color: var(--rw-text-secondary);

      mat-icon { font-size: 44px; width: 44px; height: 44px; opacity: 0.35; }
      p { margin: 12px 0 0; font-size: 14px; }
    }

    .error-message { color: var(--rw-text-danger); font-size: 13px; margin: 8px 0 0; }
    .dialog-hint { font-size: 12px; color: var(--rw-text-muted); margin: 0; }
    .table-wrap { overflow-x: auto; }
  `],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>My dApps</h1>
        <button mat-raised-button color="primary" type="button" (click)="openCreateDialog()">
          <mat-icon>add</mat-icon>
          New dApp
        </button>
      </div>
      <p style="margin:0 0 20px;color:var(--rw-text-secondary);font-size:14px">
        Publish tokenization dApps to the Registerwerk marketplace: describe your dApp in a
        signed manifest, submit it for operator review, and it goes live anchored onchain.
      </p>

      @if (loading) {
        <div class="empty-state"><mat-spinner diameter="36" style="margin:0 auto"></mat-spinner></div>
      } @else if (error) {
        <mat-card>
          <mat-card-content>
            <div class="empty-state" role="alert">
              <mat-icon>cloud_off</mat-icon>
              <p>{{ error }}</p>
              <button mat-stroked-button type="button" (click)="load()">Retry</button>
            </div>
          </mat-card-content>
        </mat-card>
      } @else if (listings.length === 0) {
        <mat-card>
          <mat-card-content>
            <div class="empty-state">
              <mat-icon>widgets</mat-icon>
              <p>You haven't published any dApps yet.</p>
            </div>
          </mat-card-content>
        </mat-card>
      } @else {
        <mat-card>
          <mat-card-content>
            <div class="table-wrap">
            <table mat-table [dataSource]="listings" style="width:100%">
              <ng-container matColumnDef="name">
                <th mat-header-cell *matHeaderCellDef>dApp</th>
                <td mat-cell *matCellDef="let l">
                  <strong>{{ l.name }}</strong>
                  <span style="color:var(--rw-text-muted);font-size:12px"> · {{ l.slug }}</span>
                </td>
              </ng-container>
              <ng-container matColumnDef="chain">
                <th mat-header-cell *matHeaderCellDef>Chain</th>
                <td mat-cell *matCellDef="let l">{{ l.chainIdentifier ?? '—' }}</td>
              </ng-container>
              <ng-container matColumnDef="status">
                <th mat-header-cell *matHeaderCellDef>Status</th>
                <td mat-cell *matCellDef="let l"><span class="status-chip {{ l.status }}">{{ l.status }}</span></td>
              </ng-container>
              <ng-container matColumnDef="updatedAt">
                <th mat-header-cell *matHeaderCellDef>Updated</th>
                <td mat-cell *matCellDef="let l">{{ l.updatedAt | date: 'mediumDate' }}</td>
              </ng-container>
              <ng-container matColumnDef="actions">
                <th mat-header-cell *matHeaderCellDef></th>
                <td mat-cell *matCellDef="let l" style="text-align:right">
                  <button mat-stroked-button color="primary" type="button" (click)="openWizard(l)">
                    <mat-icon>edit_note</mat-icon>
                    {{ l.status === 'DRAFT' || l.status === 'REJECTED' ? 'Continue' : 'New version' }}
                  </button>
                </td>
              </ng-container>
              <tr mat-header-row *matHeaderRowDef="columns"></tr>
              <tr mat-row *matRowDef="let row; columns: columns"></tr>
            </table>
            </div>
          </mat-card-content>
        </mat-card>
      }
    </div>

    <!-- Create listing dialog -->
    <ng-template #createDialog>
      <h2 mat-dialog-title>New dApp listing</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <p class="dialog-hint">
          The slug becomes your dApp's permanent marketplace identifier
          (onchain dappId = keccak256(slug)) and your permission namespace
          ("&lt;slug&gt;.&lt;action&gt;").
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Slug</mat-label>
          <input matInput [(ngModel)]="newSlug" placeholder="e.g. loandesk" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Name</mat-label>
          <input matInput [(ngModel)]="newName" placeholder="e.g. Loan Desk" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Anchor chain</mat-label>
          <mat-select [(ngModel)]="newChainId">
            @for (org of orgs; track org.id) {
              <mat-option [value]="org.chainConfigId">{{ org.chainIdentifier ?? org.chainConfigId }}</mat-option>
            }
          </mat-select>
          <mat-hint>Your org must be registered onchain on this chain</mat-hint>
        </mat-form-field>
        @if (orgsLoading) {
          <mat-spinner diameter="24" aria-label="Loading organization chains"></mat-spinner>
        }
        @if (dialogError) {
          <p class="error-message">{{ dialogError }}</p>
        }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" type="button"
                [disabled]="orgsLoading || !isValidSlug(newSlug) || !newName.trim() || !newChainId || creating"
                (click)="createListing()">
          <mat-icon>add</mat-icon>
          Create
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class MyDappsComponent implements OnInit {
  @ViewChild('createDialog') createDialogTpl!: TemplateRef<unknown>;

  private readonly marketplaceService = inject(MarketplaceService);
  private readonly orgIdentityService = inject(OrgIdentityService);
  private readonly dialog = inject(MatDialog);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  readonly columns = ['name', 'chain', 'status', 'updatedAt', 'actions'];

  listings: DappListingView[] = [];
  orgs: OrgRegistrationView[] = [];
  loading = true;
  error = '';

  newSlug = '';
  newName = '';
  newChainId: string | null = null;
  creating = false;
  dialogError = '';
  orgsLoading = false;

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.marketplaceService.myListings().subscribe({
      next: (listings) => {
        this.listings = listings;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.error = err?.error?.message ?? 'Failed to load your dApps.';
        this.loading = false;
        this.cdr.markForCheck();
      },
    });
  }

  isValidSlug(slug: string): boolean {
    return /^[a-z0-9][a-z0-9-]{1,58}[a-z0-9]$/.test(slug.trim());
  }

  openCreateDialog(): void {
    this.newSlug = '';
    this.newName = '';
    this.newChainId = null;
    this.dialogError = '';
    this.orgs = [];
    this.orgsLoading = true;
    this.orgIdentityService.myOrgs().subscribe({
      next: (orgs) => {
        this.orgs = orgs.filter((org) => org.status === 'ACTIVE');
        this.newChainId = this.orgs[0]?.chainConfigId ?? null;
        this.orgsLoading = false;
        if (this.orgs.length === 0) {
          this.dialogError = 'No active onchain organization is available. Complete organization setup first.';
        }
        this.cdr.markForCheck();
      },
      error: () => {
        this.orgsLoading = false;
        this.dialogError = 'Could not load your organization chains. Close this dialog and try again.';
        this.cdr.markForCheck();
      },
    });
    this.dialog.open(this.createDialogTpl, { width: '520px', maxWidth: '95vw' });
  }

  createListing(): void {
    const slug = this.newSlug.trim().toLowerCase();
    const name = this.newName.trim();
    const chainConfigId = this.newChainId;
    if (this.creating || !this.isValidSlug(slug) || !name || !chainConfigId) return;
    this.creating = true;
    this.dialogError = '';

    this.marketplaceService
      .createListing({
        slug,
        name,
        chainConfigId,
      })
      .subscribe({
        next: (listing) => {
          this.creating = false;
          this.dialog.closeAll();
          this.router.navigate(['/publisher', listing.id, 'publish']);
        },
        error: (err) => {
          this.creating = false;
          this.dialogError = err?.error?.message ?? 'Failed to create listing.';
          this.cdr.markForCheck();
        },
      });
  }

  openWizard(listing: DappListingView): void {
    this.router.navigate(['/publisher', listing.id, 'publish']);
  }
}
