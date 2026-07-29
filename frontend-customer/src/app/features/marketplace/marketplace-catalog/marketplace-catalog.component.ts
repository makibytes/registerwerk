import {
  ChangeDetectionStrategy,
  ChangeDetectorRef,
  Component,
  OnInit,
  inject,
} from '@angular/core';
import { DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';

import { MarketplaceService } from '../../../core/api/marketplace.service';
import { CatalogCardView } from '../../../core/models';

@Component({
  selector: 'app-marketplace-catalog',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    DatePipe,
    FormsModule,
    RouterLink,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
  ],
  styles: [`
    .filter-row {
      display: flex;
      gap: 12px;
      align-items: center;
      flex-wrap: wrap;
      margin: 16px 0 20px;

      mat-form-field { max-width: 320px; flex: 1; }
    }

    .dapp-card {
      display: flex;
      flex-direction: column;
      height: 100%;
      cursor: pointer;
      transition: box-shadow 0.15s ease, transform 0.15s ease;

      &:hover { box-shadow: var(--rw-shadow-md); transform: translateY(-2px); }

      .dapp-card-title {
        display: flex;
        align-items: center;
        gap: 10px;
        font-size: 16px;
        font-weight: 700;

        mat-icon { color: var(--rw-accent); }
      }

      .dapp-card-publisher {
        font-size: 12px;
        color: var(--rw-text-muted);
        margin: 2px 0 0;
      }

      .dapp-card-description {
        flex: 1;
        font-size: 13px;
        color: var(--rw-text-secondary);
        margin: 10px 0;
      }

      .dapp-card-meta {
        display: flex;
        gap: 8px;
        flex-wrap: wrap;
        font-size: 11px;
        color: var(--rw-text-muted);

        .meta-chip {
          padding: 2px 8px;
          border-radius: 999px;
          background: var(--rw-surface-soft);
          border: 1px solid var(--rw-border-subtle);
        }
      }
    }

    .empty-state {
      text-align: center;
      padding: 60px 16px;
      color: var(--rw-text-secondary);

      mat-icon { font-size: 44px; width: 44px; height: 44px; opacity: 0.35; }
      p { margin: 12px 0 0; font-size: 14px; }
    }
  `],
  template: `
    <div class="page-container">
      <div class="page-header">
        <h1>dApp Marketplace</h1>
      </div>
      <p style="margin:0;color:var(--rw-text-secondary);font-size:14px">
        Tokenization dApps built on the Registerwerk identity &amp; permission framework —
        reviewed by the registry operator, anchored onchain, self-hosted by you.
      </p>

      <div class="filter-row">
        <mat-form-field appearance="outline">
          <mat-label>Search</mat-label>
          <input matInput [(ngModel)]="query" (ngModelChange)="onFilterChange()"
                 placeholder="Name or slug…" />
          <mat-icon matSuffix>search</mat-icon>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Category</mat-label>
          <mat-select [(ngModel)]="category" (selectionChange)="onFilterChange()">
            <mat-option [value]="''">All categories</mat-option>
            @for (cat of categories; track cat) {
              <mat-option [value]="cat">{{ cat }}</mat-option>
            }
          </mat-select>
        </mat-form-field>
      </div>

      @if (loading) {
        <div class="empty-state"><mat-spinner diameter="36" style="margin:0 auto"></mat-spinner></div>
      } @else if (cards.length === 0) {
        <div class="empty-state">
          <mat-icon>storefront</mat-icon>
          <p>No published dApps match your filters yet.</p>
        </div>
      } @else {
        <div class="card-grid">
          @for (card of cards; track card.slug) {
            <mat-card class="dapp-card" [routerLink]="['/marketplace', card.slug]">
              <mat-card-content>
                <div class="dapp-card-title">
                  <mat-icon>widgets</mat-icon>
                  {{ card.name }}
                </div>
                <p class="dapp-card-publisher">by {{ card.publisherName ?? 'Unknown publisher' }}</p>
                <div class="dapp-card-meta" style="margin-top:12px">
                  <span class="meta-chip">{{ card.category }}</span>
                  @if (card.version) {
                    <span class="meta-chip">v{{ card.version }}</span>
                  }
                  <span class="meta-chip">{{ card.requiredPermissionCount }} permission(s)</span>
                  @if (card.pricingNote) {
                    <span class="meta-chip">{{ card.pricingNote }}</span>
                  }
                </div>
                @if (card.paymentMethods.length > 0) {
                  <div class="dapp-card-meta" style="margin-top:6px">
                    <mat-icon style="font-size:14px;width:14px;height:14px;color:var(--rw-text-muted)">payments</mat-icon>
                    @for (method of card.paymentMethods; track $index) {
                      <span class="meta-chip">{{ method.displayName ?? method.customName }}</span>
                    }
                  </div>
                }
                <p class="dapp-card-publisher" style="margin-top:10px">
                  Updated {{ card.updatedAt | date: 'mediumDate' }}
                </p>
              </mat-card-content>
            </mat-card>
          }
        </div>
      }
      @if (error) {
        <p class="error-message">{{ error }}</p>
      }
    </div>
  `,
})
export class MarketplaceCatalogComponent implements OnInit {
  private readonly marketplaceService = inject(MarketplaceService);
  private readonly cdr = inject(ChangeDetectorRef);

  categories: string[] = [];

  cards: CatalogCardView[] = [];
  loading = true;
  error = '';
  query = '';
  category = '';

  private filterTimeout: ReturnType<typeof setTimeout> | null = null;

  ngOnInit(): void {
    this.load();
    this.marketplaceService.categories().subscribe({
      next: (categories) => {
        this.categories = categories;
        this.cdr.markForCheck();
      },
    });
  }

  onFilterChange(): void {
    if (this.filterTimeout) clearTimeout(this.filterTimeout);
    this.filterTimeout = setTimeout(() => this.load(), 300);
  }

  load(): void {
    this.loading = true;
    this.error = '';
    this.cdr.markForCheck();

    this.marketplaceService
      .browse({ query: this.query || undefined, category: this.category || undefined })
      .subscribe({
        next: (page) => {
          this.cards = page.content;
          this.loading = false;
          this.cdr.markForCheck();
        },
        error: (err) => {
          this.error = err?.error?.message ?? 'Failed to load the marketplace catalog.';
          this.loading = false;
          this.cdr.markForCheck();
        },
      });
  }
}
