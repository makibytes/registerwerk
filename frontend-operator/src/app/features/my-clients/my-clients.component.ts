import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { MatTableModule } from '@angular/material/table';
import { MatIconModule } from '@angular/material/icon';
import { MatButtonModule } from '@angular/material/button';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { StatusBadgeComponent } from '@registerwerk/ui';
import { EntityService } from '../../core/api/entity.service';
import { LegalEntity } from '../../core/models';

/**
 * Read-only "my clients" view for RELATIONSHIP_MANAGER staff (F-BLOCKER-15) — previously the
 * only "act on behalf of a client" mechanism was full-mutation-rights impersonation, and no
 * staff role saw anything narrower than the entire, unfiltered customer book.
 */
@Component({
  selector: 'app-my-clients',
  standalone: true,
  imports: [MatTableModule, MatIconModule, MatButtonModule, MatProgressSpinnerModule, StatusBadgeComponent],
  template: `
    <div class="page-header">
      <h1>My Clients</h1>
      <button mat-stroked-button (click)="load()">
        <mat-icon>refresh</mat-icon> Refresh
      </button>
    </div>

    <div class="content-card">
      @if (loading) {
        <div class="spinner-container"><mat-spinner diameter="40" /></div>
      } @else if (clients.length === 0) {
        <div class="no-data">No clients are currently assigned to you.</div>
      } @else {
        <table mat-table [dataSource]="clients" class="full-width-table">
          <ng-container matColumnDef="entityNumber">
            <th mat-header-cell *matHeaderCellDef>Entity Number</th>
            <td mat-cell *matCellDef="let row"><code>{{ row.entityNumber }}</code></td>
          </ng-container>
          <ng-container matColumnDef="currentName">
            <th mat-header-cell *matHeaderCellDef>Name</th>
            <td mat-cell *matCellDef="let row">{{ row.currentName }}</td>
          </ng-container>
          <ng-container matColumnDef="type">
            <th mat-header-cell *matHeaderCellDef>Type</th>
            <td mat-cell *matCellDef="let row">{{ row.type }}</td>
          </ng-container>
          <ng-container matColumnDef="status">
            <th mat-header-cell *matHeaderCellDef>Status</th>
            <td mat-cell *matCellDef="let row"><app-status-badge [status]="row.status" /></td>
          </ng-container>
          <ng-container matColumnDef="kycStatus">
            <th mat-header-cell *matHeaderCellDef>KYC</th>
            <td mat-cell *matCellDef="let row"><app-status-badge [status]="row.kycStatus" /></td>
          </ng-container>
          <ng-container matColumnDef="clientCategory">
            <th mat-header-cell *matHeaderCellDef>MiFID category</th>
            <td mat-cell *matCellDef="let row">{{ row.clientCategory ?? 'Not classified' }}</td>
          </ng-container>
          <ng-container matColumnDef="actions">
            <th mat-header-cell *matHeaderCellDef></th>
            <td mat-cell *matCellDef="let row">
              <button mat-icon-button color="primary" (click)="viewEntity(row)">
                <mat-icon>open_in_new</mat-icon>
              </button>
            </td>
          </ng-container>

          <tr mat-header-row *matHeaderRowDef="displayedColumns"></tr>
          <tr mat-row *matRowDef="let row; columns: displayedColumns;" style="cursor:pointer" (click)="viewEntity(row)"></tr>
        </table>
      }
    </div>
  `,
  styles: [`
    .spinner-container { display: flex; justify-content: center; padding: 48px; }
    .no-data { text-align: center; padding: 48px; color: var(--rw-text-muted); }
  `],
})
export class MyClientsComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly entityService = inject(EntityService);
  private readonly router = inject(Router);

  clients: LegalEntity[] = [];
  loading = false;

  readonly displayedColumns = ['entityNumber', 'currentName', 'type', 'status', 'kycStatus', 'clientCategory', 'actions'];

  ngOnInit(): void {
    this.load();
  }

  load(): void {
    this.loading = true;
    this.entityService.myClients().subscribe({
      next: (clients) => {
        this.clients = clients;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.cdr.detectChanges(); },
    });
  }

  viewEntity(entity: LegalEntity): void {
    this.router.navigate(['/customers', entity.id]);
  }
}
