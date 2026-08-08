import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { DatePipe } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';

import { StatusBadgeComponent } from '@registerwerk/ui';
import { SupportService } from '../../core/api/support.service';
import { SupportTicket, SupportTicketMessage } from '../../core/models';

@Component({
  selector: 'app-support-detail',
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
    MatSnackBarModule,
    StatusBadgeComponent,
  ],
  styles: [`
    .page-container { max-width: 780px; margin: 0 auto; padding: 16px; }
    .back-row { margin-bottom: 12px; }
    .ticket-header { margin-bottom: 20px; }
    .ticket-header h2 { margin: 0 0 6px; font-size: 18px; }
    .ticket-meta { font-size: 12px; color: var(--rw-text-secondary); display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .description-card { margin-bottom: 20px; white-space: pre-wrap; font-size: 13px; }
    .thread { display: flex; flex-direction: column; gap: 10px; margin-bottom: 20px; }
    .message { border-radius: 10px; padding: 10px 14px; font-size: 13px; max-width: 80%; }
    .message.mine { align-self: flex-end; background: var(--rw-accent-bg, rgba(13,148,136,0.10)); }
    .message.operator { align-self: flex-start; background: var(--rw-border-subtle); }
    .message-meta { font-size: 11px; color: var(--rw-text-muted); margin-bottom: 4px; }
    .reply-row { display: flex; gap: 12px; align-items: flex-end; }
    .spinner-wrap { display: flex; justify-content: center; padding: 48px 0; }
    .closed-note { font-size: 12px; color: var(--rw-text-muted); padding: 12px 0; text-align: center; }
    .error-state { display: grid; justify-items: center; gap: 10px; padding: 48px 16px; color: var(--rw-text-secondary); text-align: center; }
    .error-state mat-icon { color: var(--rw-text-danger); }
    .thread-error { display: flex; align-items: center; justify-content: center; gap: 8px; flex-wrap: wrap; color: var(--rw-text-danger); font-size: 12px; }
  `],
  template: `
    <div class="page-container">
      <div class="back-row">
        <button mat-button type="button" routerLink="/support">
          <mat-icon>arrow_back</mat-icon>
          Back to Support
        </button>
      </div>

      @if (loading) {
        <div class="spinner-wrap"><mat-spinner diameter="40" /></div>
      } @else if (loadError) {
        <div class="error-state" role="alert">
          <mat-icon>error_outline</mat-icon>
          <span>This support ticket could not be loaded.</span>
          <button mat-stroked-button type="button" (click)="load()">Retry</button>
        </div>
      } @else if (ticket) {
        <div class="ticket-header">
          <h2>{{ ticket.subject }}</h2>
          <div class="ticket-meta">
            <app-status-badge [status]="ticket.status" />
            <span>{{ ticket.category.replace('_',' ') }}</span>
            <span>·</span>
            <span>{{ ticket.priority }} priority</span>
            <span>·</span>
            <span>Opened {{ ticket.createdAt | date:'mediumDate' }}</span>
          </div>
        </div>

        <mat-card class="description-card">
          <mat-card-content>{{ ticket.description }}</mat-card-content>
        </mat-card>

        @if (ticket.resolutionNotes) {
          <mat-card class="description-card">
            <mat-card-header>
              <mat-card-title style="font-size:13px">Resolution</mat-card-title>
            </mat-card-header>
            <mat-card-content>{{ ticket.resolutionNotes }}</mat-card-content>
          </mat-card>
        }

        <div class="thread">
          @if (messagesError) {
            <div class="thread-error" role="alert">
              Replies could not be loaded.
              <button mat-button type="button" (click)="loadMessages()">Retry</button>
            </div>
          }
          @for (m of messages; track m.id) {
            <div class="message" [class.mine]="!m.authorIsOperator" [class.operator]="m.authorIsOperator">
              <div class="message-meta">{{ m.authorIsOperator ? 'Registerwerk Support' : 'You' }} · {{ m.createdAt | date:'medium' }}</div>
              <div>{{ m.body }}</div>
            </div>
          }
          @if (messages.length === 0) {
            <p style="text-align:center;color:var(--rw-text-secondary);font-size:13px;padding:16px">No replies yet.</p>
          }
        </div>

        @if (ticket.status === 'CLOSED') {
          <p class="closed-note">This ticket is closed. Open a new ticket if you need further help.</p>
        } @else {
          <div class="reply-row">
            <mat-form-field appearance="outline" style="flex:1">
              <mat-label>Reply</mat-label>
              <textarea matInput rows="2" [(ngModel)]="replyBody"></textarea>
            </mat-form-field>
            <button mat-raised-button color="primary" type="button" [disabled]="!replyBody.trim() || sending" (click)="sendReply()">
              <mat-icon>send</mat-icon>
              Send
            </button>
          </div>
        }
      }
    </div>
  `,
})
export class SupportDetailComponent implements OnInit {
  private readonly route = inject(ActivatedRoute);
  private readonly supportService = inject(SupportService);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly snackBar = inject(MatSnackBar);

  id!: string;
  ticket: SupportTicket | null = null;
  messages: SupportTicketMessage[] = [];
  loading = true;
  loadError = false;
  messagesError = false;
  sending = false;
  replyBody = '';

  ngOnInit(): void {
    this.id = this.route.snapshot.paramMap.get('id') ?? '';
    if (!this.id) {
      this.loading = false;
      this.loadError = true;
      return;
    }
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
        this.cdr.markForCheck();
        this.snackBar.open('Reply could not be sent. Please try again.', 'Dismiss', { duration: 5000 });
      },
    });
  }
}
