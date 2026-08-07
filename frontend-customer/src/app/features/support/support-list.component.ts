import { ChangeDetectionStrategy, ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { Router } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatTooltipModule } from '@angular/material/tooltip';
import { MatSnackBar } from '@angular/material/snack-bar';

import { DataTableComponent, TableColumn, PageHeaderComponent, AsyncSectionStatus } from '@registerwerk/ui';
import { SupportService } from '../../core/api/support.service';
import { SupportTicket } from '../../core/models';

/**
 * Customer self-service support tickets — wraps `support.web.MeSupportTicketController`
 * (`/api/v1/me/support-tickets`), which had no frontend caller at all.
 */
@Component({
  selector: 'app-support-list',
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
  template: `
    <div class="page-container">
      <app-page-header title="Support" subtitle="Reach out with a technical, compliance, billing, or trading question.">
        <button mat-raised-button color="primary" (click)="openCreateDialog()">
          <mat-icon>add</mat-icon>
          New Ticket
        </button>
      </app-page-header>

      <rw-data-table
        [columns]="columns"
        [rows]="tickets"
        [state]="state"
        filterPlaceholder="Filter tickets…"
        emptyMessage="No support tickets yet."
        [actionsTemplate]="rowActions">
      </rw-data-table>

      <ng-template #rowActions let-t>
        <button mat-icon-button color="primary" (click)="open(t)" matTooltip="Open ticket">
          <mat-icon>open_in_new</mat-icon>
        </button>
      </ng-template>
    </div>

    <ng-template #createDialogTpl>
      <h2 mat-dialog-title>New Support Ticket</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px;min-width:420px">
        <mat-form-field appearance="outline">
          <mat-label>Subject</mat-label>
          <input matInput [(ngModel)]="createForm.subject" />
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Category</mat-label>
          <mat-select [(ngModel)]="createForm.category">
            <mat-option value="TECHNICAL">Technical</mat-option>
            <mat-option value="COMPLIANCE">Compliance</mat-option>
            <mat-option value="BILLING">Billing</mat-option>
            <mat-option value="ASSET_ISSUE">Asset issue</mat-option>
            <mat-option value="TRADING">Trading</mat-option>
            <mat-option value="ONBOARDING">Onboarding</mat-option>
            <mat-option value="OTHER">Other</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Priority</mat-label>
          <mat-select [(ngModel)]="createForm.priority">
            <mat-option value="LOW">Low</mat-option>
            <mat-option value="NORMAL">Normal</mat-option>
            <mat-option value="HIGH">High</mat-option>
            <mat-option value="URGENT">Urgent</mat-option>
          </mat-select>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Description</mat-label>
          <textarea matInput rows="4" [(ngModel)]="createForm.description"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary"
                [disabled]="!createForm.subject || !createForm.description || creating"
                (click)="submitCreate()">
          <mat-icon>send</mat-icon>
          Submit
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class SupportListComponent implements OnInit {
  @ViewChild('createDialogTpl') createDialogTpl!: TemplateRef<unknown>;

  private readonly supportService = inject(SupportService);
  private readonly router = inject(Router);
  private readonly dialog = inject(MatDialog);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);

  tickets: SupportTicket[] = [];
  state: AsyncSectionStatus = 'pending';
  creating = false;

  createForm: { subject: string; description: string; category: SupportTicket['category']; priority: SupportTicket['priority'] } =
    { subject: '', description: '', category: 'TECHNICAL', priority: 'NORMAL' };

  readonly columns: TableColumn[] = [
    { key: 'subject', header: 'Subject', cell: (t: SupportTicket) => t.subject },
    { key: 'category', header: 'Category', cell: (t: SupportTicket) => t.category.replace(/_/g, ' '), type: 'badge' },
    { key: 'priority', header: 'Priority', cell: (t: SupportTicket) => t.priority, type: 'badge' },
    { key: 'status', header: 'Status', cell: (t: SupportTicket) => t.status.replace(/_/g, ' '), type: 'badge' },
    { key: 'createdAt', header: 'Created', cell: (t: SupportTicket) => t.createdAt, type: 'date' },
    { key: 'updatedAt', header: 'Updated', cell: (t: SupportTicket) => t.updatedAt, type: 'date' },
  ];

  ngOnInit(): void {
    this.loadTickets();
  }

  loadTickets(): void {
    this.state = 'pending';
    this.cdr.markForCheck();
    this.supportService.list().subscribe({
      next: (tickets) => {
        this.tickets = tickets;
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
    this.router.navigate(['/support', ticket.id]);
  }

  openCreateDialog(): void {
    this.createForm = { subject: '', description: '', category: 'TECHNICAL', priority: 'NORMAL' };
    this.dialog.open(this.createDialogTpl, { width: '480px' });
  }

  submitCreate(): void {
    if (!this.createForm.subject || !this.createForm.description) return;
    this.creating = true;
    this.cdr.markForCheck();

    this.supportService.create(this.createForm.subject, this.createForm.description, this.createForm.category, this.createForm.priority).subscribe({
      next: (ticket) => {
        this.dialog.closeAll();
        this.creating = false;
        this.tickets = [ticket, ...this.tickets];
        this.cdr.markForCheck();
        this.snackBar.open('Ticket submitted.', 'OK', { duration: 3000 });
        this.router.navigate(['/support', ticket.id]);
      },
      error: () => {
        this.creating = false;
        this.cdr.markForCheck();
        this.snackBar.open('Failed to submit ticket. Please try again.', 'OK', { duration: 3000 });
      },
    });
  }
}
