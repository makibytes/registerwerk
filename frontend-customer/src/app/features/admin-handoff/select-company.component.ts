import { ChangeDetectorRef, Component, OnDestroy, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../core/auth/auth.service';
import { AdminService, EntityListItem } from '../../core/api/admin.service';
import { environment } from '../../../environments/environment';

/**
 * What to tell the operator when starting a support session fails. The backend answers step-up
 * refusals with a specific message and a stable `code` (STEP_UP_*), so each one is explained instead of
 * collapsing into "Access denied".
 */
export function describeSupportSessionError(err: unknown): string {
  const e = err as { status?: number; error?: { message?: string; code?: string } | null; message?: string } | null;
  const body = e?.error;
  if (body?.code === 'STEP_UP_ENROLMENT_REQUIRED') {
    return 'Your account has no authenticator app enrolled, so your identity cannot be confirmed. '
      + 'Enrol one in the operator portal (Security), then try again.';
  }
  if (e?.status === 0) {
    return 'The server could not be reached. Check your connection and try again.';
  }
  if (e?.status === 429) {
    return 'Too many attempts. Please wait a moment and try again.';
  }
  return body?.message ?? e?.message ?? 'The support session could not be started.';
}

@Component({
  selector: 'app-select-company',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatIconModule,
    MatProgressSpinnerModule,
    MatFormFieldModule,
    MatInputModule,
  ],
  styles: [`
    .page {
      min-height: 100vh;
      background: #0F1A2E;
      display: flex;
      align-items: center;
      justify-content: center;
      padding: 24px;
      position: relative;
      overflow: hidden;
    }

    .page::before {
      content: '';
      position: absolute;
      inset: 0;
      background:
        radial-gradient(ellipse 60% 50% at 15% 80%, rgba(245,158,11,0.08) 0%, transparent 60%),
        radial-gradient(ellipse 50% 60% at 85% 20%, rgba(245,158,11,0.04) 0%, transparent 60%);
      pointer-events: none;
    }

    .page::after {
      content: '';
      position: absolute;
      inset: 0;
      background-image: radial-gradient(circle, rgba(255,255,255,0.04) 1px, transparent 1px);
      background-size: 28px 28px;
      pointer-events: none;
    }

    .card {
      position: relative;
      z-index: 1;
      width: 100%;
      max-width: 560px;
      background: rgba(255,255,255,0.03);
      border: 1px solid rgba(255,255,255,0.08);
      border-radius: 18px;
      padding: 40px;
      backdrop-filter: blur(20px);
      box-shadow: 0 24px 64px rgba(0,0,0,0.5);
    }

    .header {
      text-align: center;
      margin-bottom: 32px;
    }

    .admin-badge {
      display: inline-flex;
      align-items: center;
      gap: 6px;
      padding: 4px 12px;
      border-radius: 999px;
      background: rgba(245,158,11,0.12);
      border: 1px solid rgba(245,158,11,0.2);
      color: #F59E0B;
      font-size: 11px;
      font-weight: 700;
      letter-spacing: 0.6px;
      text-transform: uppercase;
      margin-bottom: 16px;

      mat-icon { font-size: 14px; width: 14px; height: 14px; }
    }

    h1 {
      font-size: 22px;
      font-weight: 800;
      color: #FFFFFF;
      margin: 0 0 6px;
      letter-spacing: -0.4px;
    }

    .subtitle {
      font-size: 13px;
      color: rgba(255,255,255,0.4);
      margin: 0;
    }

    .search-wrap {
      margin-bottom: 16px;
    }

    .search-input {
      width: 100%;
      background: rgba(255,255,255,0.05);
      border: 1px solid rgba(255,255,255,0.1);
      border-radius: 9px;
      padding: 11px 14px;
      font-family: 'Manrope Variable', sans-serif;
      font-size: 14px;
      font-weight: 500;
      color: #FFFFFF;
      outline: none;
      transition: border-color 0.15s ease;

      &::placeholder { color: rgba(255,255,255,0.2); }
      &:focus { border-color: rgba(245,158,11,0.5); }
    }

    .entity-list { display: flex; flex-direction: column; gap: 6px; }

    .entity-row {
      width: 100%;
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 14px 16px;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,0.06);
      background: rgba(255,255,255,0.02);
      color: inherit;
      font: inherit;
      text-align: left;
      cursor: pointer;
      transition: border-color 0.15s ease, background 0.15s ease;

      &:hover {
        border-color: rgba(245,158,11,0.3);
        background: rgba(245,158,11,0.06);
      }

      &:disabled {
        cursor: wait;
        opacity: 0.65;
      }
    }

    .entity-icon {
      width: 36px;
      height: 36px;
      border-radius: 10px;
      background: rgba(245,158,11,0.12);
      color: #F59E0B;
      display: grid;
      place-items: center;
      flex-shrink: 0;

      mat-icon { font-size: 18px; width: 18px; height: 18px; }
    }

    .entity-name {
      font-size: 14px;
      font-weight: 600;
      color: #FFFFFF;
    }

    .entity-meta {
      font-size: 11px;
      color: rgba(255,255,255,0.35);
      margin-top: 2px;
    }

    .reason-panel { display: flex; flex-direction: column; gap: 10px; }
    .session-error {
      padding: 10px 12px;
      border-radius: 8px;
      border: 1px solid rgba(248,113,113,0.4);
      background: rgba(248,113,113,0.1);
      color: #FCA5A5;
      font-size: 13px;
      line-height: 1.5;
    }
    .session-error a { color: #FDE68A; }
    .reason-actions { display: flex; justify-content: flex-end; gap: 8px; }

    .loading-row {
      display: flex;
      justify-content: center;
      padding: 24px;
    }

    .empty-msg {
      text-align: center;
      padding: 24px;
      color: rgba(255,255,255,0.3);
      font-size: 13px;
    }

    .retry-btn {
      display: block;
      margin: 12px auto 0;
      border: 1px solid rgba(245,158,11,0.35);
      border-radius: 7px;
      padding: 7px 12px;
      background: rgba(245,158,11,0.08);
      color: #F59E0B;
      cursor: pointer;
      font: inherit;
      font-size: 12px;
      font-weight: 700;
    }

    .spinner-row { display: flex; justify-content: center; align-items: center; gap: 10px; }
    .spinner-text { color: rgba(255,255,255,0.4); font-size: 13px; }

    .logout-link {
      text-align: center;
      margin-top: 24px;

      button {
        background: none;
        border: none;
        cursor: pointer;
        font-size: 12px;
        color: rgba(255,255,255,0.25);
        font-family: 'Manrope Variable', sans-serif;

        &:hover { color: rgba(255,255,255,0.5); }
      }
    }
  `],
  template: `
    <div class="page">
      <div class="card">
        <div class="header">
          <div class="admin-badge">
            <mat-icon>admin_panel_settings</mat-icon>
            Admin Mode
          </div>
          <h1>Select a Company</h1>
          <p class="subtitle">Choose the company you want to manage in the customer portal.</p>
        </div>

        @if (pending) {
          <form class="reason-panel" role="group" aria-label="Start support session"
                (ngSubmit)="selectEntity(pending)">
            <div class="entity-name">Read-only support session for {{ pending.currentName }}</div>
            <p class="entity-meta">
              You will see the customer's data but cannot change anything. The reason is shown to the
              company's administrators and written to the audit trail. Acting on behalf of a customer is
              a separate action with a second approver in the operator console.
            </p>
            <input class="search-input" type="text" name="reason" [(ngModel)]="reason" maxlength="500"
                   placeholder="Reason (at least 15 characters)" aria-label="Reason" />
            <input class="search-input" type="text" name="ticket" [(ngModel)]="ticket" maxlength="100"
                   placeholder="Ticket reference (optional)" aria-label="Ticket reference" />
            <input class="search-input" type="text" name="totp" [(ngModel)]="totpCode" maxlength="6" inputmode="numeric"
                   autocomplete="one-time-code" placeholder="Authenticator code (6 digits)" aria-label="Authenticator code" />
            @if (sessionError) {
              <div class="session-error" role="alert">
                {{ sessionError }}
                @if (operatorUrl && enrolmentNeeded) {
                  <a [href]="operatorUrl" target="_blank" rel="noopener">Open operator portal</a>
                }
              </div>
            }
            <div class="reason-actions">
              <button class="retry-btn" type="button" (click)="cancelPending()" [disabled]="!!selecting">Back</button>
              <button class="retry-btn" type="submit"
                      [disabled]="!!selecting || reason.trim().length < 15 || totpCode.length < 6">
                @if (selecting) { Starting… } @else { Start session }
              </button>
            </div>
          </form>
        } @else {
          <div class="search-wrap">
            <input
              class="search-input"
              type="text"
              [(ngModel)]="searchQuery"
              (ngModelChange)="onSearch()"
              placeholder="Search companies…"
              aria-label="Search companies"
            />
          </div>

          @if (loadingEntities) {
            <div class="loading-row"><mat-spinner diameter="28" /></div>
          } @else if (loadError) {
            <div class="empty-msg" role="alert">
              {{ loadError }}
              <button class="retry-btn" type="button" (click)="loadEntities()">Retry</button>
            </div>
          } @else if (entities.length === 0) {
            <div class="empty-msg">No companies found.</div>
          } @else {
            <div class="entity-list">
              @for (entity of entities; track entity.id) {
                <button
                  class="entity-row"
                  type="button"
                  (click)="chooseEntity(entity)"
                  [attr.aria-label]="'Manage ' + entity.currentName"
                >
                  <div class="entity-icon"><mat-icon>domain</mat-icon></div>
                  <div>
                    <div class="entity-name">{{ entity.currentName }}</div>
                    <div class="entity-meta">{{ entity.entityNumber }} · {{ entity.type }}</div>
                  </div>
                </button>
              }
            </div>
          }
        }

        <div class="logout-link">
          <button type="button" (click)="logout()">Sign out</button>
        </div>
      </div>
    </div>
  `,
})
export class SelectCompanyComponent implements OnInit, OnDestroy {
  private readonly auth = inject(AuthService);
  private readonly adminService = inject(AdminService);
  private readonly router = inject(Router);
  private readonly cdr = inject(ChangeDetectorRef);

  entities: EntityListItem[] = [];
  loadingEntities = true;
  selecting: string | null = null;
  searchQuery = '';
  loadError = '';
  private searchTimer: ReturnType<typeof setTimeout> | null = null;
  private loadRequestId = 0;

  ngOnInit(): void {
    this.loadEntities();
  }

  onSearch(): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.loadEntities(), 300);
  }

  ngOnDestroy(): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
  }

  pending: EntityListItem | null = null;
  reason = '';
  ticket = '';
  totpCode = '';
  /** Why the last attempt failed, shown inside the session form. */
  sessionError = '';
  enrolmentNeeded = false;
  readonly operatorUrl = environment.operatorUrl;

  /** Opens the session form for `entity` (it replaces the list, so it can never be below the fold). */
  chooseEntity(entity: EntityListItem): void {
    this.pending = entity;
    this.sessionError = '';
    this.enrolmentNeeded = false;
    this.cdr.markForCheck();
  }

  cancelPending(): void {
    this.pending = null;
    this.sessionError = '';
    this.totpCode = '';
    this.cdr.markForCheck();
  }

  /** Step-up (TOTP) -> start the read-only session -> exchange the one-time handoff code for the session cookie. */
  selectEntity(entity: EntityListItem): void {
    if (this.selecting) return;
    this.selecting = entity.id;
    this.sessionError = '';
    this.enrolmentNeeded = false;
    this.adminService.startReadOnlySession(entity.id, this.reason.trim(), this.totpCode, this.ticket.trim()).subscribe({
      next: ({ handoffCode, session }) => {
        this.auth.enterImpersonation(handoffCode, session.entityId, session.entityName).subscribe({
          next: () => this.router.navigate(['/dashboard']),
          error: (err) => this.fail(err),
        });
      },
      error: (err) => this.fail(err),
    });
  }

  private fail(err: unknown): void {
    this.selecting = null;
    this.totpCode = '';
    this.enrolmentNeeded = (err as { error?: { code?: string } } | null)?.error?.code === 'STEP_UP_ENROLMENT_REQUIRED';
    this.sessionError = describeSupportSessionError(err);
    this.cdr.markForCheck();
  }

  logout(): void {
    this.auth.logout();
  }

  loadEntities(): void {
    const requestId = ++this.loadRequestId;
    this.loadingEntities = true;
    this.loadError = '';
    const query = this.searchQuery.trim();
    this.adminService.listEntities(query || undefined).subscribe({
      next: (page) => {
        if (requestId !== this.loadRequestId) return;
        this.entities = page.content;
        this.loadingEntities = false;
        this.cdr.markForCheck();
      },
      error: () => {
        if (requestId !== this.loadRequestId) return;
        this.entities = [];
        this.loadingEntities = false;
        this.loadError = 'Companies could not be loaded.';
        this.cdr.markForCheck();
      },
    });
  }
}
