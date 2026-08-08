import { ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar } from '@angular/material/snack-bar';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';

import { StatusBadgeComponent } from '@registerwerk/ui';
import { SupportService } from '../../../core/api/support.service';
import { AuthService } from '../../../core/auth/auth.service';
import { SupportTicket, SupportTicketMessage } from '../../../core/models';

/**
 * Ticket detail: message thread + assign/resolve/close/reopen. `SupportTicketAdminController`
 * previously had no frontend caller at all, so this is the first operator-facing view of a
 * customer support conversation the platform has ever had.
 */
@Component({
  selector: 'app-support-ticket-detail',
  standalone: true,
  imports: [
    RouterLink,
    FormsModule,
    DatePipe,
    MatButtonModule,
    MatIconModule,
    MatCardModule,
    MatFormFieldModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatDialogModule,
    StatusBadgeComponent,
  ],
  styles: [`
    .page-container { max-width: 860px; margin: 0 auto; padding: 16px; }
    .back-row { margin-bottom: 12px; }
    .ticket-header { display: flex; justify-content: space-between; align-items: flex-start; gap: 16px; margin-bottom: 20px; flex-wrap: wrap; }
    .ticket-header h2 { margin: 0 0 4px; font-size: 18px; }
    .ticket-meta { font-size: 12px; color: var(--rw-text-secondary); display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .ticket-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .description-card { margin-bottom: 20px; white-space: pre-wrap; font-size: 13px; }
    .thread { display: flex; flex-direction: column; gap: 10px; margin-bottom: 20px; }
    .message { border-radius: 10px; padding: 10px 14px; font-size: 13px; max-width: 80%; }
    .message.operator { align-self: flex-end; background: var(--rw-accent-bg, rgba(245,158,11,0.10)); }
    .message.customer { align-self: flex-start; background: var(--rw-border-subtle); }
    .message-meta { font-size: 11px; color: var(--rw-text-muted); margin-bottom: 4px; }
    .reply-row { display: flex; gap: 12px; align-items: flex-end; }
    .spinner-wrap { display: flex; justify-content: center; padding: 48px 0; }
    .load-error { display: grid; justify-items: center; gap: 10px; padding: 48px 16px; color: var(--rw-text-secondary); text-align: center; }
    @media (max-width: 620px) {
      .page-container { padding: 0; }
      .message { max-width: 95%; }
      .reply-row { align-items: stretch; flex-direction: column; }
    }
  `],
  template: `
    <div class="page-container">
      <div class="back-row">
        <button mat-button routerLink="/compliance/support-tickets">
          <mat-icon>arrow_back</mat-icon>
          Back to Support Tickets
        </button>
      </div>

      @if (loading) {
        <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
      } @else if (ticket) {
        <div class="ticket-header">
          <div>
            <h2>{{ ticket.subject }}</h2>
            <div class="ticket-meta">
              <app-status-badge [status]="ticket.status" />
              <span>{{ ticket.category.replace('_',' ') }}</span>
              <span>·</span>
              <span>{{ ticket.priority }} priority</span>
              <span>·</span>
              <span>Entity <code>{{ ticket.entityId }}</code></span>
              @if (ticket.assignedTo) {
                <span>·</span>
                <span>Assigned to <code>{{ ticket.assignedTo }}</code></span>
              }
            </div>
          </div>
          @if (canManage) {
          <div class="ticket-actions">
            @if (!ticket.assignedTo || ticket.assignedTo !== myUserId) {
              <button mat-stroked-button (click)="assignToMe()">
                <mat-icon>person_add</mat-icon>
                Assign to me
              </button>
            }
            @if (ticket.status === 'OPEN' || ticket.status === 'IN_PROGRESS') {
              <button mat-stroked-button color="primary" (click)="openResolveDialog()">
                <mat-icon>check_circle</mat-icon>
                Resolve
              </button>
              <button mat-stroked-button color="warn" (click)="close()">
                <mat-icon>block</mat-icon>
                Close
              </button>
            }
            @if (ticket.status === 'RESOLVED' || ticket.status === 'CLOSED') {
              <button mat-stroked-button (click)="reopen()">
                <mat-icon>replay</mat-icon>
                Reopen
              </button>
            }
          </div>
          }
        </div>

        <mat-card class="description-card">
          <mat-card-content>{{ ticket.description }}</mat-card-content>
        </mat-card>

        @if (ticket.resolutionNotes) {
          <mat-card class="description-card">
            <mat-card-header>
              <mat-card-title style="font-size:13px">Resolution notes</mat-card-title>
            </mat-card-header>
            <mat-card-content>{{ ticket.resolutionNotes }}</mat-card-content>
          </mat-card>
        }

        <div class="thread">
          @for (m of messages; track m.id) {
            <div class="message" [class.operator]="m.authorIsOperator" [class.customer]="!m.authorIsOperator">
              <div class="message-meta">{{ m.authorIsOperator ? 'Operator' : 'Customer' }} · {{ m.createdAt | date:'medium' }}</div>
              <div>{{ m.body }}</div>
            </div>
          }
          @if (messages.length === 0) {
            <p style="text-align:center;color:var(--rw-text-secondary);font-size:13px;padding:16px">
              {{ messagesError ? 'Messages could not be loaded.' : 'No messages yet.' }}
            </p>
            @if (messagesError) {
              <button mat-button type="button" (click)="loadMessages()">Retry messages</button>
            }
          }
        </div>

        <div class="reply-row">
          <mat-form-field appearance="outline" style="flex:1">
            <mat-label>Reply</mat-label>
            <textarea matInput rows="2" [(ngModel)]="replyBody"></textarea>
          </mat-form-field>
          <button mat-raised-button color="primary" [disabled]="!replyBody.trim() || sending" (click)="sendReply()">
            <mat-icon>send</mat-icon>
            Send
          </button>
        </div>
      } @else if (loadError) {
        <div class="load-error" role="alert">
          <mat-icon>error_outline</mat-icon>
          <span>Ticket details could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="load()">Retry</button>
        </div>
      }
    </div>

    <ng-template #resolveDialogTpl>
      <h2 mat-dialog-title>Resolve ticket</h2>
      <mat-dialog-content style="min-width:420px">
        <mat-form-field appearance="outline" style="width:100%">
          <mat-label>Resolution notes</mat-label>
          <textarea matInput rows="3" [(ngModel)]="resolutionNotes"></textarea>
        </mat-form-field>
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" [disabled]="!resolutionNotes.trim()" (click)="submitResolve()">
          Resolve
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
})
export class SupportTicketDetailComponent implements OnInit {
  @ViewChild('resolveDialogTpl') resolveDialogTplRef!: TemplateRef<unknown>;

  private readonly route = inject(ActivatedRoute);
  private readonly supportService = inject(SupportService);
  private readonly authService = inject(AuthService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);

  id!: string;
  ticket: SupportTicket | null = null;
  messages: SupportTicketMessage[] = [];
  loading = true;
  loadError = false;
  messagesError = false;
  sending = false;
  replyBody = '';
  resolutionNotes = '';
  readonly myUserId = this.authService.getUserId();
  readonly canManage = this.authService.hasRole('REGISTRY_ADMIN') || this.authService.hasRole('COMPLIANCE_OFFICER');

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id')!;
    this.load();
  }

  load(): void {
    this.loading = true;
    this.loadError = false;
    this.cdr.markForCheck();
    this.supportService.get(this.id).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.loading = false;
        this.cdr.markForCheck();
        this.loadMessages();
      },
      error: () => {
        this.ticket = null;
        this.loading = false;
        this.loadError = true;
        this.cdr.markForCheck();
      },
    });
  }

  loadMessages(): void {
    this.messagesError = false;
    this.supportService.messages(this.id).subscribe({
      next: (messages) => {
        this.messages = messages;
        this.cdr.markForCheck();
      },
      error: () => {
        this.messages = [];
        this.messagesError = true;
        this.cdr.markForCheck();
      },
    });
  }

  sendReply(): void {
    const body = this.replyBody.trim();
    if (!body) return;
    this.sending = true;
    this.cdr.markForCheck();
    this.supportService.addMessage(this.id, body).subscribe({
      next: (msg) => {
        this.messages = [...this.messages, msg];
        this.replyBody = '';
        this.sending = false;
        this.cdr.markForCheck();
      },
      error: () => {
        this.sending = false;
        this.snackBar.open('Reply could not be sent.', 'Dismiss', { duration: 5000 });
        this.cdr.markForCheck();
      },
    });
  }

  assignToMe(): void {
    if (!this.myUserId) return;
    this.supportService.assign(this.id, this.myUserId).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.cdr.markForCheck();
        this.snackBar.open('Ticket assigned to you.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to assign ticket.', 'Dismiss', { duration: 6000 }),
    });
  }

  openResolveDialog(): void {
    this.resolutionNotes = '';
    this.dialog.open(this.resolveDialogTplRef, { width: '480px' });
  }

  submitResolve(): void {
    const notes = this.resolutionNotes.trim();
    if (!notes) return;
    this.dialog.closeAll();
    this.supportService.resolve(this.id, notes).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.cdr.markForCheck();
        this.snackBar.open('Ticket resolved.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to resolve ticket.', 'Dismiss', { duration: 6000 }),
    });
  }

  close(): void {
    if (!confirm('Close this ticket without a resolution note?')) return;
    this.supportService.close(this.id).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.cdr.markForCheck();
        this.snackBar.open('Ticket closed.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to close ticket.', 'Dismiss', { duration: 6000 }),
    });
  }

  reopen(): void {
    this.supportService.reopen(this.id).subscribe({
      next: (ticket) => {
        this.ticket = ticket;
        this.cdr.markForCheck();
        this.snackBar.open('Ticket reopened.', 'Dismiss', { duration: 4000 });
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to reopen ticket.', 'Dismiss', { duration: 6000 }),
    });
  }
}
