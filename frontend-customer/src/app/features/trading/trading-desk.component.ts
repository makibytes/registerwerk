import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { MatDialogModule, MatDialog } from '@angular/material/dialog';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AddressPickerDialogComponent, AddressPickerDialogData } from '../../shared/components/address-picker-dialog.component';
import { EndpointService } from '../../core/api/endpoint.service';
import { TradingService } from '../../core/api/trading.service';
import {
  CompanyTraderSettings,
  CompanyTraderWalletDefault,
  Endpoint,
  PaymentOption,
  SellableHolding,
  TradeExecution,
  TradeListing,
  TradingAssetType,
  TradingOffer,
  TradingOrderType,
  TradingVenue,
  WalletPreferenceMode,
  WalletTargetType,
  TokenStandard,
} from '../../core/models';

interface SellForm {
  holderId: string;
  quantity: number | null;
  pricePerUnit: number | null;
  useCompanyDefaultPaymentOption: boolean;
  allowedPaymentOptions: PaymentOption[];
}

interface BuyForm {
  quantity: number | null;
  orderType: TradingOrderType;
  limitPrice: number | null;
  paymentOption: PaymentOption | null;
  walletPreferenceMode: WalletPreferenceMode;
  endpointId: string | null;
  walletAddress: string;
}

@Component({
  selector: 'app-trading-desk',
  standalone: true,
  imports: [
    CommonModule,
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressSpinnerModule,
    MatSelectModule,
    MatSlideToggleModule,
    MatSnackBarModule,
    MatTabsModule,
    MatDialogModule,
    MatTooltipModule,
  ],
  template: `
    <div class="page-container">
      <div class="page-header">
        <div>
          <h1>Trading</h1>
          <p class="page-subtitle">Browse integrated venues, sell held assets, manage company-shared trader defaults, and monitor settlements.</p>
        </div>
      </div>

      @if (loading) {
        <div class="loading-overlay"><mat-spinner diameter="40"></mat-spinner></div>
      } @else if (tradingDisabled) {
        <mat-card class="warning-card">
          <mat-card-content>
            <div class="warning-line">
              <mat-icon>block</mat-icon>
              <div>
                <strong>Trading is disabled</strong>
                <p>The backend trading feature flag is turned off. Enable it in docker-compose to use this desk.</p>
              </div>
            </div>
          </mat-card-content>
        </mat-card>
      } @else {
        <mat-tab-group>
          <mat-tab label="Marketplace">
            <div class="tab-body">
              <mat-card class="filter-card">
                <mat-card-content>
                  <div class="filter-grid">
                    <mat-form-field appearance="outline">
                      <mat-label>Search</mat-label>
                      <input matInput [(ngModel)]="marketSearch" placeholder="Asset name, number, or ISIN">
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Asset type</mat-label>
                      <mat-select [(ngModel)]="marketAssetType">
                        <mat-option [value]="null">All</mat-option>
                        @for (option of assetTypeOptions; track option.value) {
                          <mat-option [value]="option.value">{{ option.label }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Token standard</mat-label>
                      <mat-select [(ngModel)]="marketTokenStandard">
                        <mat-option [value]="null">All</mat-option>
                        @for (standard of tokenStandardOptions; track standard) {
                          <mat-option [value]="standard">{{ standard }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Venue</mat-label>
                      <mat-select [(ngModel)]="marketVenueCode">
                        <mat-option [value]="null">All</mat-option>
                        @for (venue of venues; track venue.code) {
                          <mat-option [value]="venue.code">{{ venue.displayName }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Payment</mat-label>
                      <mat-select [(ngModel)]="marketPaymentOption">
                        <mat-option [value]="null">All</mat-option>
                        @for (option of paymentOptions; track option.value) {
                          <mat-option [value]="option.value">{{ option.label }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>
                  </div>
                </mat-card-content>
              </mat-card>

              <mat-card class="summary-card">
                <mat-card-content>
                  <div class="summary-grid">
                    <div>
                      <span class="summary-value">{{ filteredOffers.length }}</span>
                      <span class="summary-label">Visible offers</span>
                    </div>
                    <div>
                      <span class="summary-value">{{ executableVenueCount }}</span>
                      <span class="summary-label">Executable venues</span>
                    </div>
                    <div>
                      <span class="summary-value">{{ connectedVenueCount }}</span>
                      <span class="summary-label">Connected venues</span>
                    </div>
                  </div>
                </mat-card-content>
              </mat-card>

              <mat-card>
                <mat-card-content>
                  @if (filteredOffers.length === 0) {
                    <div class="empty-state">
                      <mat-icon>storefront</mat-icon>
                      <p><strong>No matching offers</strong></p>
                      <p>Adjust the filters or create a sell listing on the simulated venue.</p>
                    </div>
                  } @else {
                    <table class="desk-table">
                      <thead>
                        <tr>
                          <th>Asset</th>
                          <th>Venue</th>
                          <th>Type</th>
                          <th>Chain</th>
                          <th>Available</th>
                          <th>Price</th>
                          <th>Payment</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (offer of filteredOffers; track offer.listingId) {
                          <tr>
                            <td>
                              <div class="asset-cell">
                                <strong>{{ offer.assetName }}</strong>
                                <span>{{ offer.assetNumber }}</span>
                                @if (offer.isin) {
                                  <span>{{ offer.isin }}</span>
                                }
                              </div>
                            </td>
                            <td>
                              <div class="venue-cell">
                                <strong>{{ offer.venueDisplayName }}</strong>
                                <span>{{ offer.supportedOrderTypes.join(', ') }}</span>
                              </div>
                            </td>
                            <td>{{ assetTypeLabel(offer.assetType) }}</td>
                            <td>{{ offer.chain ?? '—' }}</td>
                            <td>{{ offer.quantityAvailable | number:'1.0-4' }}</td>
                            <td>{{ offer.pricePerUnit | number:'1.2-4' }}</td>
                            <td>
                              <div class="chip-row">
                                @for (option of offer.allowedPaymentOptions; track option) {
                                  <mat-chip>{{ paymentLabel(option) }}</mat-chip>
                                }
                              </div>
                            </td>
                            <td>
                              @if (isExecutable(offer)) {
                                <button mat-flat-button color="primary" (click)="selectOffer(offer)">Buy</button>
                              } @else {
                                <span class="readiness-only" matTooltip="This venue is connected for price discovery only — Registerwerk cannot yet execute an order against it.">
                                  Readiness only
                                </span>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </mat-card-content>
              </mat-card>

              @if (selectedOffer) {
                <mat-card class="action-card">
                  <mat-card-header>
                    <mat-card-title>Buy {{ selectedOffer.assetName }}</mat-card-title>
                    <mat-card-subtitle>{{ selectedOffer.venueDisplayName }} · {{ selectedOffer.pricePerUnit | number:'1.2-4' }} per unit</mat-card-subtitle>
                  </mat-card-header>
                  <mat-card-content>
                    <div class="form-grid">
                      <mat-form-field appearance="outline">
                        <mat-label>Quantity</mat-label>
                        <input matInput type="number" [(ngModel)]="buyForm.quantity">
                      </mat-form-field>

                      <mat-form-field appearance="outline">
                        <mat-label>Order type</mat-label>
                        <mat-select [(ngModel)]="buyForm.orderType">
                          @for (orderType of selectedOffer.supportedOrderTypes; track orderType) {
                            <mat-option [value]="orderType">{{ orderType }}</mat-option>
                          }
                        </mat-select>
                      </mat-form-field>

                      @if (buyForm.orderType === 'LIMIT') {
                        <mat-form-field appearance="outline">
                          <mat-label>Limit price</mat-label>
                          <input matInput type="number" [(ngModel)]="buyForm.limitPrice">
                        </mat-form-field>
                      }

                      <mat-form-field appearance="outline">
                        <mat-label>Payment option</mat-label>
                        <mat-select [(ngModel)]="buyForm.paymentOption">
                          @for (option of selectedOffer.allowedPaymentOptions; track option) {
                            <mat-option [value]="option">{{ paymentLabel(option) }}</mat-option>
                          }
                        </mat-select>
                      </mat-form-field>

                      <mat-form-field appearance="outline">
                        <mat-label>Settlement wallet source</mat-label>
                        <mat-select [(ngModel)]="buyForm.walletPreferenceMode">
                          @for (mode of walletPreferenceModes; track mode.value) {
                            <mat-option [value]="mode.value">{{ mode.label }}</mat-option>
                          }
                        </mat-select>
                      </mat-form-field>

                      @if (buyForm.walletPreferenceMode === 'ENDPOINT') {
                        <mat-form-field appearance="outline">
                          <mat-label>Endpoint</mat-label>
                          <mat-select [(ngModel)]="buyForm.endpointId">
                            @for (endpoint of walletEndpoints; track endpoint.id) {
                              <mat-option [value]="endpoint.id">{{ endpoint.name }} · {{ endpoint.address }}</mat-option>
                            }
                          </mat-select>
                        </mat-form-field>
                      }

                      @if (buyForm.walletPreferenceMode === 'CUSTOM_ADDRESS') {
                        <mat-form-field appearance="outline">
                          <mat-label>Custom wallet address</mat-label>
                          <input matInput [(ngModel)]="buyForm.walletAddress" placeholder="0x…" style="font-family:'IBM Plex Mono',monospace;font-size:13px">
                          <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                                  (click)="pickWallet(a => buyForm.walletAddress = a)">
                            <mat-icon style="font-size:18px">contacts</mat-icon>
                          </button>
                        </mat-form-field>
                      }
                    </div>
                  </mat-card-content>
                  <mat-card-actions align="end">
                    <button mat-button (click)="clearSelectedOffer()">Cancel</button>
                    <button mat-flat-button color="primary" (click)="submitBuy()">Submit buy order</button>
                  </mat-card-actions>
                </mat-card>
              }
            </div>
          </mat-tab>

          <mat-tab label="Sell">
            <div class="tab-body">
              <mat-card class="action-card">
                <mat-card-header>
                  <mat-card-title>Create sell listing</mat-card-title>
                  <mat-card-subtitle>Publish inventory to the executable simulated venue and choose accepted payment options.</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  <div class="form-grid">
                    <mat-form-field appearance="outline" class="span-2">
                      <mat-label>Held asset</mat-label>
                      <mat-select [(ngModel)]="sellForm.holderId">
                        @for (holding of sellableHoldings; track holding.holderId) {
                          <mat-option [value]="holding.holderId">
                            {{ holding.assetName }} · {{ holding.availableQuantity | number:'1.0-4' }} available
                          </mat-option>
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Quantity</mat-label>
                      <input matInput type="number" [(ngModel)]="sellForm.quantity">
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Price per unit</mat-label>
                      <input matInput type="number" [(ngModel)]="sellForm.pricePerUnit">
                    </mat-form-field>
                  </div>

                  <mat-slide-toggle [(ngModel)]="sellForm.useCompanyDefaultPaymentOption">
                    Use company default payment option
                  </mat-slide-toggle>

                  @if (!sellForm.useCompanyDefaultPaymentOption) {
                    <mat-form-field appearance="outline" class="full-width">
                      <mat-label>Accepted payment options</mat-label>
                      <mat-select [(ngModel)]="sellForm.allowedPaymentOptions" multiple>
                        @for (option of paymentOptions; track option.value) {
                          <mat-option [value]="option.value">{{ option.label }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>
                  }
                </mat-card-content>
                <mat-card-actions align="end">
                  <button mat-flat-button color="primary" (click)="submitSell()">Create listing</button>
                </mat-card-actions>
              </mat-card>

              <mat-card>
                <mat-card-header>
                  <mat-card-title>My listings</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  @if (companyListings.length === 0) {
                    <div class="empty-state compact">
                      <p>No listings yet.</p>
                    </div>
                  } @else {
                    <table class="desk-table">
                      <thead>
                        <tr>
                          <th>Asset</th>
                          <th>Status</th>
                          <th>Available</th>
                          <th>Total</th>
                          <th>Price</th>
                          <th>Payment</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (listing of companyListings; track listing.id) {
                          <tr>
                            <td>{{ listing.assetName }}</td>
                            <td>{{ listing.status }}</td>
                            <td>{{ listing.quantityAvailable | number:'1.0-4' }}</td>
                            <td>{{ listing.quantityTotal | number:'1.0-4' }}</td>
                            <td>{{ listing.pricePerUnit | number:'1.2-4' }}</td>
                            <td>
                              <div class="chip-row">
                                @for (option of listing.allowedPaymentOptions; track option) {
                                  <mat-chip>{{ paymentLabel(option) }}</mat-chip>
                                }
                              </div>
                            </td>
                            <td>
                              @if (listing.status === 'OPEN' || listing.status === 'PARTIALLY_FILLED') {
                                <button mat-button color="warn" (click)="cancelListing(listing.id)">Cancel</button>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </mat-card-content>
              </mat-card>
            </div>
          </mat-tab>

          <mat-tab label="History">
            <div class="tab-body">
              <mat-card>
                <mat-card-header>
                  <mat-card-title>Trading history</mat-card-title>
                  <mat-card-subtitle>Executed buys, sells, and pending settlements.</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  @if (history.length === 0) {
                    <div class="empty-state compact">
                      <p>No trades yet.</p>
                    </div>
                  } @else {
                    <table class="desk-table">
                      <thead>
                        <tr>
                          <th>Side</th>
                          <th>Asset</th>
                          <th>Venue</th>
                          <th>Quantity</th>
                          <th>Price</th>
                          <th>Total</th>
                          <th>Payment</th>
                          <th>Settlement</th>
                          <th>Wallet</th>
                          <th></th>
                        </tr>
                      </thead>
                      <tbody>
                        @for (trade of history; track trade.id) {
                          <tr>
                            <td>{{ trade.side }}</td>
                            <td>{{ trade.assetName }}</td>
                            <td>{{ venueLabel(trade.venueCode) }}</td>
                            <td>{{ trade.executedQuantity | number:'1.0-4' }}</td>
                            <td>{{ trade.unitPrice | number:'1.2-4' }}</td>
                            <td>{{ trade.totalPrice | number:'1.2-4' }}</td>
                            <td>{{ paymentLabel(trade.paymentOption) }}</td>
                            <td>{{ trade.settlementStatus }}</td>
                            <td class="mono">{{ trade.walletAddress }}</td>
                            <td>
                              @if (trade.side === 'BUY' && trade.settlementStatus === 'PENDING') {
                                <button mat-flat-button color="primary" (click)="settleTrade(trade.id)">Settle</button>
                              }
                            </td>
                          </tr>
                        }
                      </tbody>
                    </table>
                  }
                </mat-card-content>
              </mat-card>
            </div>
          </mat-tab>

          <mat-tab label="Settings">
            <div class="tab-body">
              <mat-card class="action-card">
                <mat-card-header>
                  <mat-card-title>Company trader defaults</mat-card-title>
                  <mat-card-subtitle>These settings are shared across all traders in this company.</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  <div class="form-grid">
                    <mat-form-field appearance="outline">
                      <mat-label>Default payment option</mat-label>
                      <mat-select [(ngModel)]="settings.defaultPaymentOption">
                        @for (option of paymentOptions; track option.value) {
                          <mat-option [value]="option.value">{{ option.label }}</mat-option>
                        }
                      </mat-select>
                    </mat-form-field>
                  </div>

                  <mat-slide-toggle [(ngModel)]="settings.immediateSettlementEnabled">
                    Immediate post-trade settlement
                  </mat-slide-toggle>

                  <div class="wallet-defaults">
                    <div class="wallet-defaults-header">
                      <h3>Wallet defaults</h3>
                      <button mat-stroked-button (click)="addWalletDefault()">
                        <mat-icon>add</mat-icon>
                        Add wallet default
                      </button>
                    </div>

                    @for (walletDefault of settings.walletDefaults; track $index) {
                      <div class="wallet-default-row">
                        <mat-form-field appearance="outline">
                          <mat-label>Scope</mat-label>
                          <mat-select [(ngModel)]="walletDefault.assetType">
                            <mat-option [value]="null">Global default</mat-option>
                            @for (option of assetTypeOptions; track option.value) {
                              <mat-option [value]="option.value">{{ option.label }}</mat-option>
                            }
                          </mat-select>
                        </mat-form-field>

                        <mat-form-field appearance="outline">
                          <mat-label>Target type</mat-label>
                          <mat-select [(ngModel)]="walletDefault.targetType">
                            <mat-option value="ENDPOINT">Endpoint</mat-option>
                            <mat-option value="CUSTOM_ADDRESS">Custom address</mat-option>
                          </mat-select>
                        </mat-form-field>

                        @if (walletDefault.targetType === 'ENDPOINT') {
                          <mat-form-field appearance="outline" class="span-2">
                            <mat-label>Endpoint</mat-label>
                            <mat-select [(ngModel)]="walletDefault.endpointId">
                              @for (endpoint of walletEndpoints; track endpoint.id) {
                                <mat-option [value]="endpoint.id">{{ endpoint.name }} · {{ endpoint.address }}</mat-option>
                              }
                            </mat-select>
                          </mat-form-field>
                        } @else {
                          <mat-form-field appearance="outline" class="span-2">
                            <mat-label>Wallet address</mat-label>
                            <input matInput [(ngModel)]="walletDefault.walletAddress" placeholder="0x…" style="font-family:'IBM Plex Mono',monospace;font-size:13px">
                            <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                                    (click)="pickWallet(a => walletDefault.walletAddress = a)">
                              <mat-icon style="font-size:18px">contacts</mat-icon>
                            </button>
                          </mat-form-field>
                        }

                        <button mat-icon-button color="warn" (click)="removeWalletDefault(walletDefault)">
                          <mat-icon>delete</mat-icon>
                        </button>
                      </div>
                    }
                  </div>
                </mat-card-content>
                <mat-card-actions align="end">
                  <button mat-flat-button color="primary" (click)="saveSettings()">Save settings</button>
                </mat-card-actions>
              </mat-card>

              <mat-card>
                <mat-card-header>
                  <mat-card-title>Integrated venues</mat-card-title>
                </mat-card-header>
                <mat-card-content>
                  <div class="venue-grid">
                    @for (venue of venues; track venue.code) {
                      <div class="venue-tile">
                        <div class="venue-title">{{ venue.displayName }}</div>
                        <div class="venue-meta">
                          <span>{{ venue.connected ? 'Connected' : 'Prepared' }}</span>
                          <span>{{ venue.executable ? 'Executable' : 'Readiness only' }}</span>
                        </div>
                        <p>{{ venue.summary }}</p>
                      </div>
                    }
                  </div>
                </mat-card-content>
              </mat-card>
            </div>
          </mat-tab>
        </mat-tab-group>
      }
    </div>
  `,
  styles: [`
    .page-container { max-width: 1320px; margin: 0 auto; padding: 32px 24px; }
    .page-header { margin-bottom: 20px; }
    .page-header h1 { margin: 0; font-size: 21px; font-weight: 700; color: var(--rw-text-primary); letter-spacing: -0.4px; }
    .page-subtitle { margin: 6px 0 0; color: var(--rw-text-secondary); font-size: 13px; max-width: 900px; }
    .tab-body { padding: 20px 4px 4px; display: grid; gap: 16px; }
    .filter-grid, .form-grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
    .span-2 { grid-column: span 2; }
    .full-width { width: 100%; }
    .summary-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }
    .summary-value { display: block; font-size: 24px; font-weight: 700; color: var(--rw-text-primary); }
    .summary-label { font-size: 12px; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.4px; }
    .desk-table { width: 100%; border-collapse: collapse; }
    .desk-table th, .desk-table td { padding: 12px 10px; border-bottom: 1px solid var(--rw-border); text-align: left; vertical-align: top; font-size: 13px; }
    .desk-table th { font-size: 11px; color: var(--rw-text-muted); text-transform: uppercase; letter-spacing: 0.4px; }
    .asset-cell, .venue-cell { display: flex; flex-direction: column; gap: 2px; }
    .asset-cell span, .venue-cell span { color: var(--rw-text-secondary); font-size: 12px; }
    .chip-row { display: flex; flex-wrap: wrap; gap: 6px; }
    .readiness-only { font-size: 11.5px; color: var(--rw-text-muted); cursor: help; white-space: nowrap; }
    .action-card, .filter-card, .summary-card { border: 1px solid var(--rw-border); }
    .warning-card { border-left: 4px solid var(--rw-accent); }
    .warning-line { display: flex; gap: 12px; align-items: flex-start; }
    .warning-line mat-icon { color: var(--rw-accent); }
    .warning-line p { margin: 4px 0 0; color: var(--rw-text-secondary); }
    .empty-state { text-align: center; padding: 40px 20px; color: var(--rw-text-muted); }
    .empty-state.compact { padding: 20px; }
    .empty-state mat-icon { width: 36px; height: 36px; font-size: 36px; margin-bottom: 10px; }
    .wallet-defaults { margin-top: 18px; display: grid; gap: 12px; }
    .wallet-defaults-header { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
    .wallet-defaults-header h3 { margin: 0; font-size: 15px; color: var(--rw-text-primary); }
    .wallet-default-row { display: grid; gap: 12px; grid-template-columns: repeat(4, minmax(0, 1fr)) auto; align-items: start; }
    .venue-grid { display: grid; gap: 12px; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); }
    .venue-tile { border: 1px solid var(--rw-border); border-radius: 10px; padding: 14px; background: var(--rw-surface-raised); }
    .venue-title { font-weight: 700; color: var(--rw-text-primary); margin-bottom: 4px; }
    .venue-meta { display: flex; gap: 8px; color: var(--rw-text-muted); font-size: 11px; text-transform: uppercase; letter-spacing: 0.4px; margin-bottom: 8px; }
    .venue-tile p { margin: 0; color: var(--rw-text-secondary); font-size: 13px; }
    .mono { font-family: 'IBM Plex Mono', monospace; font-size: 12px; }
    .loading-overlay { display: flex; justify-content: center; padding: 48px 0; }
    @media (max-width: 900px) {
      .summary-grid { grid-template-columns: 1fr; }
      .wallet-default-row { grid-template-columns: 1fr; }
      .span-2 { grid-column: span 1; }
    }
  `]
})
export class TradingDeskComponent implements OnInit {
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly tradingService = inject(TradingService);
  private readonly endpointService = inject(EndpointService);
  private readonly snackBar = inject(MatSnackBar);
  private readonly dialog = inject(MatDialog);

  loading = true;
  tradingDisabled = false;

  venues: TradingVenue[] = [];
  offers: TradingOffer[] = [];
  sellableHoldings: SellableHolding[] = [];
  companyListings: TradeListing[] = [];
  history: TradeExecution[] = [];
  settings: CompanyTraderSettings = {
    defaultPaymentOption: 'OFFCHAIN_SEPA',
    immediateSettlementEnabled: true,
    walletDefaults: [],
  };
  walletEndpoints: Endpoint[] = [];

  marketSearch = '';
  marketAssetType: TradingAssetType | null = null;
  marketTokenStandard: TokenStandard | null = null;
  marketVenueCode: string | null = null;
  marketPaymentOption: PaymentOption | null = null;

  selectedOffer: TradingOffer | null = null;

  sellForm: SellForm = {
    holderId: '',
    quantity: null,
    pricePerUnit: null,
    useCompanyDefaultPaymentOption: true,
    allowedPaymentOptions: [],
  };

  buyForm: BuyForm = {
    quantity: null,
    orderType: 'MARKET',
    limitPrice: null,
    paymentOption: null,
    walletPreferenceMode: 'GLOBAL_DEFAULT',
    endpointId: null,
    walletAddress: '',
  };

  readonly paymentOptions: { value: PaymentOption; label: string }[] = [
    { value: 'NATIVE_CHAIN_CURRENCY', label: 'Native chain currency' },
    { value: 'STABLECOIN', label: 'Stablecoin' },
    { value: 'CBMT', label: 'CBMT' },
    { value: 'PONTES_TARGET', label: 'Pontes / TARGET' },
    { value: 'OFFCHAIN_SEPA', label: 'Off-chain SEPA' },
  ];

  readonly assetTypeOptions: { value: TradingAssetType; label: string }[] = [
    { value: 'EQUITY', label: 'Equity' },
    { value: 'BOND', label: 'Bond' },
    { value: 'FUND', label: 'Fund' },
    { value: 'NOTE', label: 'Note' },
    { value: 'COMMODITY', label: 'Commodity' },
    { value: 'OTHER', label: 'Other' },
  ];

  readonly walletPreferenceModes: { value: WalletPreferenceMode; label: string }[] = [
    { value: 'GLOBAL_DEFAULT', label: 'Company global default' },
    { value: 'ASSET_TYPE_DEFAULT', label: 'Company asset-type default' },
    { value: 'ENDPOINT', label: 'Select endpoint' },
    { value: 'CUSTOM_ADDRESS', label: 'Custom address' },
  ];

  readonly tokenStandardOptions: TokenStandard[] = [
    'ERC20', 'ERC721', 'ERC1155', 'ERC3643', 'CONF_ERC20', 'CONF_ERC3643', 'SPL',
    'SPL_2022', 'STARKNET_ERC20', 'STELLAR_ASSET', 'CANTON_TOKEN',
  ];

  ngOnInit(): void {
    this.reload();
  }

  get filteredOffers(): TradingOffer[] {
    const search = this.marketSearch.trim().toLowerCase();
    return this.offers.filter(offer => {
      if (search && !`${offer.assetName} ${offer.assetNumber} ${offer.isin ?? ''}`.toLowerCase().includes(search)) {
        return false;
      }
      if (this.marketAssetType && offer.assetType !== this.marketAssetType) {
        return false;
      }
      if (this.marketTokenStandard && offer.tokenStandard !== this.marketTokenStandard) {
        return false;
      }
      if (this.marketVenueCode && offer.venueCode !== this.marketVenueCode) {
        return false;
      }
      return !(this.marketPaymentOption && !offer.allowedPaymentOptions.includes(this.marketPaymentOption));
    });
  }

  get executableVenueCount(): number {
    return this.venues.filter(venue => venue.executable).length;
  }

  get connectedVenueCount(): number {
    return this.venues.filter(venue => venue.connected).length;
  }

  /**
   * Whether `offer` can actually be bought through Registerwerk. Read-only venues (Archax,
   * Talos, …) are only connected for price-discovery aggregation in the marketplace table —
   * `buy()` has no corresponding TradeListing row for their offers and would reject the
   * request. Also gates IOC/FOK order types out of reach in the UI: only the executable
   * SIMULATED venue is wired to `TradingService.buy()`, and it only ever advertises
   * MARKET/LIMIT — external venues' own IOC/FOK support was otherwise selectable here despite
   * never being executable.
   */
  isExecutable(offer: TradingOffer): boolean {
    return this.venues.some(venue => venue.code === offer.venueCode && venue.executable);
  }

  selectOffer(offer: TradingOffer): void {
    this.selectedOffer = offer;
    this.buyForm = {
      quantity: offer.quantityAvailable,
      orderType: offer.supportedOrderTypes.includes('MARKET') ? 'MARKET' : offer.supportedOrderTypes[0],
      limitPrice: offer.pricePerUnit,
      paymentOption: offer.allowedPaymentOptions[0] ?? null,
      walletPreferenceMode: 'GLOBAL_DEFAULT',
      endpointId: null,
      walletAddress: '',
    };
  }

  clearSelectedOffer(): void {
    this.selectedOffer = null;
  }

  submitSell(): void {
    if (!this.sellForm.holderId || !this.sellForm.quantity || !this.sellForm.pricePerUnit) {
      this.snackBar.open('Select a holding, quantity, and price.', 'OK', { duration: 3000 });
      return;
    }
    this.tradingService.createListing({
      holderId: this.sellForm.holderId,
      quantity: this.sellForm.quantity,
      pricePerUnit: this.sellForm.pricePerUnit,
      useCompanyDefaultPaymentOption: this.sellForm.useCompanyDefaultPaymentOption,
      allowedPaymentOptions: this.sellForm.allowedPaymentOptions,
    }).subscribe({
      next: () => {
        this.snackBar.open('Sell listing created.', 'OK', { duration: 3000 });
        this.sellForm = {
          holderId: '',
          quantity: null,
          pricePerUnit: null,
          useCompanyDefaultPaymentOption: true,
          allowedPaymentOptions: [],
        };
        this.reload();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to create listing.', 'OK', { duration: 4000 }),
    });
  }

  submitBuy(): void {
    if (!this.selectedOffer || !this.buyForm.quantity || !this.buyForm.paymentOption) {
      this.snackBar.open('Choose quantity and payment option.', 'OK', { duration: 3000 });
      return;
    }
    this.tradingService.buy(this.selectedOffer.listingId, {
      quantity: this.buyForm.quantity,
      orderType: this.buyForm.orderType,
      limitPrice: this.buyForm.orderType === 'LIMIT' ? this.buyForm.limitPrice : null,
      paymentOption: this.buyForm.paymentOption,
      walletPreferenceMode: this.buyForm.walletPreferenceMode,
      endpointId: this.buyForm.walletPreferenceMode === 'ENDPOINT' ? this.buyForm.endpointId : null,
      walletAddress: this.buyForm.walletPreferenceMode === 'CUSTOM_ADDRESS' ? this.buyForm.walletAddress : null,
    }).subscribe({
      next: (trade) => {
        this.snackBar.open(
          trade.settlementStatus === 'SETTLED' ? 'Buy order executed and settled.' : 'Buy order executed with pending settlement.',
          'OK',
          { duration: 3500 }
        );
        this.clearSelectedOffer();
        this.reload();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to submit buy order.', 'OK', { duration: 4000 }),
    });
  }

  cancelListing(listingId: string): void {
    this.tradingService.cancelListing(listingId).subscribe({
      next: () => {
        this.snackBar.open('Listing cancelled.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to cancel listing.', 'OK', { duration: 4000 }),
    });
  }

  settleTrade(executionId: string): void {
    this.tradingService.settle(executionId).subscribe({
      next: () => {
        this.snackBar.open('Trade settled.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to settle trade.', 'OK', { duration: 4000 }),
    });
  }

  addWalletDefault(): void {
    this.settings.walletDefaults = [
      ...this.settings.walletDefaults,
      { assetType: null, targetType: 'ENDPOINT', endpointId: null, walletAddress: null },
    ];
  }

  removeWalletDefault(walletDefault: CompanyTraderWalletDefault): void {
    this.settings.walletDefaults = this.settings.walletDefaults.filter(candidate => candidate !== walletDefault);
  }

  saveSettings(): void {
    this.tradingService.saveSettings(this.settings).subscribe({
      next: (settings) => {
        this.settings = settings;
        this.snackBar.open('Trader settings saved.', 'OK', { duration: 3000 });
        this.cdr.detectChanges();
      },
      error: (err) => this.snackBar.open(err?.error?.message ?? 'Failed to save settings.', 'OK', { duration: 4000 }),
    });
  }

  pickWallet(setter: (addr: string) => void): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select wallet' }, width: '560px' }
    ).afterClosed().subscribe(addr => { if (addr) setter(addr); });
  }

  paymentLabel(option: PaymentOption): string {
    return this.paymentOptions.find(candidate => candidate.value === option)?.label ?? option;
  }

  assetTypeLabel(assetType: TradingAssetType): string {
    return this.assetTypeOptions.find(option => option.value === assetType)?.label ?? assetType;
  }

  venueLabel(code: string): string {
    return this.venues.find(venue => venue.code === code)?.displayName ?? code;
  }

  private reload(): void {
    this.loading = true;
    forkJoin({
      venues: this.tradingService.listVenues(),
      offers: this.tradingService.listMarketplaceOffers(),
      sellableHoldings: this.tradingService.listSellableHoldings(),
      companyListings: this.tradingService.listCompanyListings(),
      history: this.tradingService.listHistory(),
      settings: this.tradingService.getSettings(),
      endpoints: this.endpointService.listEndpoints(),
    }).subscribe({
      next: (payload) => {
        this.venues = payload.venues;
        this.offers = payload.offers;
        this.sellableHoldings = payload.sellableHoldings;
        this.companyListings = payload.companyListings;
        this.history = payload.history;
        this.settings = payload.settings;
        this.walletEndpoints = payload.endpoints.filter(endpoint => endpoint.addressType === 'WALLET');
        this.loading = false;
        this.tradingDisabled = false;
        this.cdr.detectChanges();
      },
      error: (err) => {
        this.loading = false;
        this.tradingDisabled = err?.status === 501;
        if (!this.tradingDisabled) {
          this.snackBar.open(err?.error?.message ?? 'Failed to load trading data.', 'OK', { duration: 4000 });
        }
        this.cdr.detectChanges();
      },
    });
  }
}
