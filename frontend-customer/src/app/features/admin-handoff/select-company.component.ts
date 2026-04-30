import { Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { AuthService } from '../../core/auth/auth.service';
import { AdminService, EntityListItem } from '../../core/api/admin.service';

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
      font-family: 'Manrope', sans-serif;
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
      display: flex;
      align-items: center;
      gap: 14px;
      padding: 14px 16px;
      border-radius: 10px;
      border: 1px solid rgba(255,255,255,0.06);
      background: rgba(255,255,255,0.02);
      cursor: pointer;
      transition: border-color 0.15s ease, background 0.15s ease;

      &:hover {
        border-color: rgba(245,158,11,0.3);
        background: rgba(245,158,11,0.06);
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
        font-family: 'Manrope', sans-serif;

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

        <div class="search-wrap">
          <input
            class="search-input"
            type="text"
            [(ngModel)]="searchQuery"
            (ngModelChange)="onSearch()"
            placeholder="Search companies…"
          />
        </div>

        @if (loadingEntities) {
          <div class="loading-row"><mat-spinner diameter="28" /></div>
        } @else if (entities.length === 0) {
          <div class="empty-msg">No companies found.</div>
        } @else {
          <div class="entity-list">
            @for (entity of entities; track entity.id) {
              <div class="entity-row" (click)="selectEntity(entity)" [class.disabled]="selecting === entity.id">
                <div class="entity-icon"><mat-icon>domain</mat-icon></div>
                <div>
                  <div class="entity-name">{{ entity.currentName }}</div>
                  <div class="entity-meta">{{ entity.entityNumber }} · {{ entity.type }}</div>
                </div>
                @if (selecting === entity.id) {
                  <mat-spinner diameter="18" style="margin-left:auto;" />
                }
              </div>
            }
          </div>
        }

        <div class="logout-link">
          <button (click)="logout()">Sign out</button>
        </div>
      </div>
    </div>
  `,
})
export class SelectCompanyComponent implements OnInit {
  private readonly auth = inject(AuthService);
  private readonly adminService = inject(AdminService);
  private readonly router = inject(Router);

  entities: EntityListItem[] = [];
  loadingEntities = true;
  selecting: string | null = null;
  searchQuery = '';
  private searchTimer: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.loadEntities();
  }

  onSearch(): void {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.loadEntities(), 300);
  }

  selectEntity(entity: EntityListItem): void {
    if (this.selecting) return;
    this.selecting = entity.id;
    this.adminService.impersonate(entity.id).subscribe({
      next: (res) => {
        this.auth.enterImpersonation(res.token, res.entityId, res.entityName);
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.selecting = null;
        alert(err?.error?.message ?? 'Impersonation failed');
      },
    });
  }

  logout(): void {
    this.auth.logout();
  }

  private loadEntities(): void {
    this.loadingEntities = true;
    this.adminService.listEntities(this.searchQuery || undefined).subscribe({
      next: (page) => {
        this.entities = page.content;
        this.loadingEntities = false;
      },
      error: () => {
        this.loadingEntities = false;
      },
    });
  }
}
