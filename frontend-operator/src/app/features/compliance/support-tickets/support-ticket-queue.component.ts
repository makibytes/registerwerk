import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';

import { DataTableComponent, TableColumn, PageHeaderComponent } from '@registerwerk/ui';
import { SupportService } from '../../../core/api/support.service';
import { SupportTicket } from '../../../core/models';
import { AsyncSectionStatus } from '../../../core/async/async-section';

/**
 * Staff work queue for `support.web.SupportTicketAdminController`. Previously the only real
 * assignment/ownership model in the system — `assign`/`resolve`/`close`/`reopen` — had no
 * frontend caller at all.
 */
@Component({
  selector: 'app-support-ticket-queue',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    FormsModule,
    MatButtonModule,
    MatIconModule,
    MatFormFieldModule,
    MatSelectModule,
    MatTooltipModule,
    DataTableComponent,
    PageHeaderComponent,
  ],
  template: `
    <app-page-header
      title="Support Tickets"
      subtitle="Customer-raised tickets — technical, compliance, billing, asset, trading, onboarding.">
      <mat-form-field appearance="outline" subscriptSizing="dynamic" style="width:180px">
        <mat-label>Status</mat-label>
        <mat-select [(ngModel)]="statusFilter" (selectionChange)="loadQueue()">
          <mat-option [value]="undefined">All statuses</mat-option>
          <mat-option value="OPEN">Open</mat-option>
          <mat-option value="IN_PROGRESS">In progress</mat-option>
          <mat-option value="RESOLVED">Resolved</mat-option>
          <mat-option value="CLOSED">Closed</mat-option>
        </mat-select>
      </mat-form-field>
    </app-page-header>

    <rw-data-table
      [columns]="columns"
      [rows]="tickets"
      [state]="state"
      filterPlaceholder="Filter by subject, entity…"
      emptyMessage="No tickets match this filter."
      [actionsTemplate]="rowActions">
    </rw-data-table>

    <ng-template #rowActions let-t>
      <button mat-icon-button color="primary" (click)="open(t)" matTooltip="Open ticket">
        <mat-icon>open_in_new</mat-icon>
      </button>
    </ng-template>
  `,
})
export class SupportTicketQueueComponent implements OnInit {
  private readonly supportService = inject(SupportService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  tickets: SupportTicket[] = [];
  state: AsyncSectionStatus = 'pending';
  statusFilter: SupportTicket['status'] | undefined = undefined;

  readonly columns: TableColumn[] = [
    { key: 'subject', header: 'Subject', cell: (t: SupportTicket) => t.subject },
    { key: 'entityId', header: 'Entity', cell: (t: SupportTicket) => t.entityId, type: 'mono' },
    { key: 'category', header: 'Category', cell: (t: SupportTicket) => t.category.replace(/_/g, ' '), type: 'badge' },
    { key: 'priority', header: 'Priority', cell: (t: SupportTicket) => t.priority, type: 'badge' },
    { key: 'status', header: 'Status', cell: (t: SupportTicket) => t.status.replace(/_/g, ' '), type: 'badge' },
    { key: 'assignedTo', header: 'Assigned To', cell: (t: SupportTicket) => t.assignedTo ?? 'Unassigned', type: 'mono' },
    { key: 'createdAt', header: 'Created', cell: (t: SupportTicket) => t.createdAt, type: 'date' },
    { key: 'updatedAt', header: 'Updated', cell: (t: SupportTicket) => t.updatedAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.loadQueue();
  }

  loadQueue(): void {
    this.state = 'pending';
    this.cdr.markForCheck();

    this.supportService.list(this.statusFilter).subscribe({
      next: (page) => {
        this.tickets = page.content;
        this.state = 'ready';
        this.cdr.markForCheck();
      },
      error: () => {
        this.state = 'error';
        this.cdr.markForCheck();
      },
    });
  }

  open(ticket: SupportTicket): void {
    this.router.navigate(['/compliance/support-tickets', ticket.id]);
  }
}
