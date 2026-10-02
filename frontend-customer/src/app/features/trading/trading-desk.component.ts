import { ChangeDetectorRef, Component, OnInit, TemplateRef, ViewChild, inject } from '@angular/core';
import { PAYMENT_DISPUTED_TOAST, failedExplanation } from './trading-desk.labels';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { forkJoin, Observable, of } from 'rxjs';
import { catchError } from 'rxjs/operators';
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
import { AuthService } from '../../core/auth/auth.service';
import { TradingService } from '../../core/api/trading.service';
import { downloadBlob } from '../../core/utils/download.util';
import {
  CatalogPaymentRail,
  CompanyTraderSettings,
  CompanyTraderWalletDefault,
  Endpoint,
  PaymentOption,
  SellableHolding,
  TradeConfig,
  TradeExecution,
  TradeListing,
  TradeNote,
  TradingAssetType,
  TradingOffer,
  TradingOrderType,
  TradingVenue,
  WalletPreferenceMode,
  TokenStandard,
} from '../../core/models';

interface SellForm {
  holderId: string;
  quantity: number | null;
  pricePerUnit: number | null;
  useCompanyDefaultPaymentOption: boolean;
  allowedPaymentOptions: PaymentOption[];
  /** Seller opt-in for the demo instant path; only offered while the backend exposes it. */
  allowInstantSettlement: boolean;
  currency: string;
  paymentRailCode: string | null;
  targetEntityId: string;
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

      <mat-card class="warning-card demo-notice" role="note">
        <mat-card-content>
          <div class="warning-line">
            <mat-icon>science</mat-icon>
            <div>
              <strong>Demonstration secondary-market workflow, not an authorised trading venue</strong>
              <p>
                This desk records bilateral trades between two customers and is not a regulated
                market, MTF or OTF. Payment is arranged and confirmed by the parties themselves;
                Registerwerk does not hold or verify funds.
              </p>
            </div>
          </div>
        </mat-card-content>
      </mat-card>

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
        @if (loadWarnings.length > 0) {
          <mat-card class="warning-card load-warning">
            <mat-card-content role="alert">
              <div class="warning-line">
                <mat-icon>cloud_off</mat-icon>
                <div>
                  <strong>Some trading data is unavailable</strong>
                  @for (warning of loadWarnings; track warning) { <p>{{ warning }}</p> }
                </div>
                <button mat-button type="button" (click)="reload()">Retry</button>
              </div>
            </mat-card-content>
          </mat-card>
        }
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
                            <td>
                              {{ offer.pricePerUnit | number:'1.2-4' }}
                              @if (offer.currency) { <span class="ccy">{{ offer.currency }}</span> }
                              @else { <span class="ccy legacy">currency not recorded</span> }
                              @if (offer.lastTradePrice !== null && offer.lastTradePrice !== undefined) {
                                <div class="dimmed small" matTooltip="One settled trade between unrelated parties. Indicative only, not a price formed on an authorised venue.">
                                  last trade {{ offer.lastTradePrice | number:'1.2-4' }} (indicative)
                                </div>
                              }
                            </td>
                            <td>
                              <div class="chip-row">
                                @for (option of offer.allowedPaymentOptions; track option) {
                                  <mat-chip>{{ paymentLabel(option) }}{{ option === 'STABLECOIN' && offer.paymentRailCode ? ' (' + offer.paymentRailCode + ')' : '' }}</mat-chip>
                                }
                                @if (offer.targeted) { <mat-chip>Addressed to you</mat-chip> }
                              </div>
                            </td>
                            <td>
                              @if (isExecutable(offer)) {
                                <button mat-flat-button color="primary" type="button" [disabled]="mutating" (click)="selectOffer(offer)">Buy</button>
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
                    <mat-card-subtitle>{{ selectedOffer.venueDisplayName }} · {{ selectedOffer.pricePerUnit | number:'1.2-4' }} {{ selectedOffer.currency ?? '(currency not recorded)' }} per unit</mat-card-subtitle>
                  </mat-card-header>
                  <mat-card-content>
                    @if (config) {
                      <p class="hint" role="note">
                        Buying reserves the units for up to {{ config.pendingTimeoutHours }} h while you pay and the
                        seller confirms. You can hold at most {{ config.maxOpenReservationsPerBuyer }} open reservations
                        and after a cancelled or failed reservation you cannot reserve the same listing again for
                        {{ config.reservationCooldownHours }} h. Payment is between you and the seller; no instant
                        settlement is offered on your side.
                      </p>
                    }
                    <div class="form-grid">
                      <mat-form-field appearance="outline">
                        <mat-label>Quantity</mat-label>
                        <input matInput type="number" min="0.0001" [max]="selectedOffer.quantityAvailable" [(ngModel)]="buyForm.quantity">
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
                          <input matInput type="number" min="0.0001" [(ngModel)]="buyForm.limitPrice">
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
                          <mat-label>Bound wallet (address book)</mat-label>
                          <input matInput [(ngModel)]="buyForm.walletAddress" readonly placeholder="Pick a bound wallet from the address book" style="font-family:'IBM Plex Mono',monospace;font-size:13px">
                          <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                                  (click)="pickWallet(a => buyForm.walletAddress = a)">
                            <mat-icon style="font-size:18px">contacts</mat-icon>
                          </button>
                        </mat-form-field>
                      }
                    </div>
                  </mat-card-content>
                  <mat-card-actions align="end">
                    <button mat-button type="button" (click)="clearSelectedOffer()">Cancel</button>
                    <button mat-flat-button color="primary" type="button" [disabled]="mutating" (click)="submitBuy()">Submit buy order</button>
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
                  <mat-card-subtitle>Publish inventory to the simulated demonstration venue and choose the settlement currency and accepted payment options.</mat-card-subtitle>
                </mat-card-header>
                <mat-card-content>
                  @if (hasNonListableHoldings) {
                    <p class="hint" role="note">
                      Holdings of chain-deployed assets cannot be listed here: settlement on this venue only
                      changes the register, not the token contract. On-chain settlement is not available yet.
                    </p>
                  }
                  @if (hasSimulatedHoldings) {
                    <p class="hint" role="note">
                      Simulated settlement: trades in chain-deployed assets only change the register, not the
                      token contract (demo mode).
                    </p>
                  }
                  <div class="form-grid">
                    <mat-form-field appearance="outline" class="span-2">
                      <mat-label>Held asset</mat-label>
                      <mat-select [(ngModel)]="sellForm.holderId">
                        @for (holding of sellableHoldings; track holding.holderId) {
                          @if (holding.listable) {
                            <mat-option [value]="holding.holderId">
                              {{ holding.assetName }} · {{ holding.availableQuantity | number:'1.0-4' }} available{{ holding.simulatedOffchainSettlement ? ' (simulated settlement)' : '' }}
                            </mat-option>
                          }
                        }
                      </mat-select>
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Quantity</mat-label>
                      <input matInput type="number" min="0.0001" [(ngModel)]="sellForm.quantity">
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Price per unit</mat-label>
                      <input matInput type="number" min="0.0001" [(ngModel)]="sellForm.pricePerUnit">
                    </mat-form-field>

                    <mat-form-field appearance="outline">
                      <mat-label>Currency (ISO 4217)</mat-label>
                      <input matInput maxlength="3" [ngModel]="sellForm.currency"
                             (ngModelChange)="sellForm.currency = ($event ?? '').toUpperCase()"
                             placeholder="e.g. EUR" [disabled]="sellUsesStablecoin">
                      <mat-hint>
                        {{ sellUsesStablecoin ? 'Taken from the selected payment rail.' : 'Leave empty to use the asset currency.' }}
                      </mat-hint>
                    </mat-form-field>

                    @if (sellUsesStablecoin) {
                      <mat-form-field appearance="outline">
                        <mat-label>Stablecoin payment rail</mat-label>
                        <mat-select [(ngModel)]="sellForm.paymentRailCode">
                          @for (rail of stablecoinRails; track rail.code) {
                            <mat-option [value]="rail.code">{{ rail.displayName }} ({{ rail.currency }})</mat-option>
                          }
                        </mat-select>
                        @if (stablecoinRails.length === 0) {
                          <mat-hint>No enabled stablecoin rail is available.</mat-hint>
                        }
                      </mat-form-field>
                    }

                    <mat-form-field appearance="outline" class="span-2">
                      <mat-label>Counterparty entity ID (optional)</mat-label>
                      <input matInput [(ngModel)]="sellForm.targetEntityId" placeholder="Leave empty for an open listing">
                      <mat-hint>A bilateral listing is visible to and buyable by this company only.</mat-hint>
                    </mat-form-field>
                  </div>

                  @if (config?.demoInstantSettlementAvailable) {
                    <mat-slide-toggle [(ngModel)]="sellForm.allowInstantSettlement">
                      Allow instant settlement (demo only, no cash leg)
                    </mat-slide-toggle>
                    <p class="hint" role="note">
                      When on, a buyer's order moves the register immediately without any payment. Use it only
                      for demonstrations; it is unavailable in production.
                    </p>
                  }

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
                  <button mat-flat-button color="primary" type="button" [disabled]="mutating" (click)="submitSell()">Create listing</button>
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
                            <td>
                              {{ listing.pricePerUnit | number:'1.2-4' }}
                              @if (listing.currency) { <span class="ccy">{{ listing.currency }}</span> }
                              @else { <span class="ccy legacy">currency not recorded</span> }
                            </td>
                            <td>
                              <div class="chip-row">
                                @for (option of listing.allowedPaymentOptions; track option) {
                                  <mat-chip>{{ paymentLabel(option) }}{{ option === 'STABLECOIN' && listing.paymentRailCode ? ' (' + listing.paymentRailCode + ')' : '' }}</mat-chip>
                                }
                                @if (listing.allowInstantSettlement) { <mat-chip>Instant (demo)</mat-chip> }
                                @if (listing.targetEntityId) { <mat-chip>Bilateral</mat-chip> }
                              </div>
                            </td>
                            <td>
                              @if (listing.status === 'OPEN' || listing.status === 'PARTIALLY_FILLED') {
                                <button type="button" mat-button color="warn" (click)="cancelListing(listing.id)">Cancel</button>
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
                            <td>
                              {{ trade.totalPrice | number:'1.2-4' }}
                              @if (trade.currency) { <span class="ccy">{{ trade.currency }}</span> }
                              @else { <span class="ccy legacy">currency not recorded</span> }
                              @if (trade.totalPriceUnrounded !== null && trade.totalPriceUnrounded !== undefined && trade.totalPriceUnrounded !== trade.totalPrice) {
                                <div class="dimmed small">rounded from {{ trade.totalPriceUnrounded }} ({{ trade.priceRoundingMode }})</div>
                              }
                            </td>
                            <td>{{ paymentLabel(trade.paymentOption) }}{{ trade.paymentRailCode ? ' (' + trade.paymentRailCode + ')' : '' }}</td>
                            <td>
                              <span class="status-chip" [class]="'status-' + trade.settlementStatus.toLowerCase()"
                                    [matTooltip]="statusExplanation(trade)">{{ statusLabel(trade.settlementStatus) }}</span>
                              @if (trade.instantSettlement) {
                                <div class="small sim-flag">SIMULATED, no cash leg</div>
                              }
                              @if (trade.relatedParty) {
                                <div class="small dimmed" [matTooltip]="trade.relatedPartyReasons ?? ''">Related-party trade, excluded from reference price</div>
                              }
                              @if (trade.settlementStatus === 'PAYMENT_UNRESOLVED') {
                                <div class="small dimmed">{{ statusExplanation(trade) }}</div>
                                @if (trade.unresolvedReason) { <div class="small dimmed">{{ trade.unresolvedReason }}</div> }
                              }
                              @if ((trade.settlementStatus === 'FAILED' || trade.settlementStatus === 'CANCELLED') && trade.failureReason) {
                                <mat-icon class="status-hint" [matTooltip]="trade.failureReason" inline="true">info</mat-icon>
                              }
                              @if (trade.side === 'BUY' && isCoolingDown(trade)) {
                                <div class="small dimmed">You can reserve this listing again after {{ trade.buyerCooldownUntil | date:'medium' }}.</div>
                              }
                            </td>
                            <td class="mono">{{ trade.walletAddress }}</td>
                            <td class="actions-cell">
                              @if (trade.side === 'BUY' && trade.settlementStatus === 'PENDING') {
                                <button type="button" mat-flat-button color="primary" (click)="openDeclarePaymentDialog(trade)">Declare payment</button>
                              }
                              @if (trade.side === 'BUY' && trade.settlementStatus === 'PENDING') {
                                <button type="button" mat-stroked-button (click)="openCancelDialog(trade)">Cancel</button>
                              }
                              @if (trade.side === 'SELL' && trade.settlementStatus === 'PENDING') {
                                <span class="dimmed small">Awaiting buyer payment…</span>
                              }
                              @if (trade.settlementStatus === 'AWAITING_SELLER_CONFIRMATION' || trade.settlementStatus === 'PAYMENT_UNRESOLVED') {
                                <button type="button" mat-stroked-button (click)="openNotesDialog(trade)">
                                  <mat-icon>forum</mat-icon>
                                  Evidence notes
                                </button>
                              }
                              @if (trade.side === 'SELL' && trade.settlementStatus === 'AWAITING_SELLER_CONFIRMATION') {
                                <button type="button" mat-flat-button color="primary" (click)="confirmPayment(trade.id)"
                                        [matTooltip]="'Buyer declared payment reference: ' + trade.paymentReference">
                                  Confirm receipt
                                </button>
                                <button type="button" mat-stroked-button color="warn" (click)="openDisputeDialog(trade)">Dispute</button>
                              }
                              @if (trade.side === 'BUY' && trade.settlementStatus === 'AWAITING_SELLER_CONFIRMATION') {
                                <span class="dimmed small">Awaiting seller confirmation…</span>
                              }
                              @if (trade.settlementStatus === 'SETTLED') {
                                <button type="button" mat-stroked-button (click)="downloadConfirmation(trade.id)">
                                  <mat-icon>picture_as_pdf</mat-icon>
                                  Confirmation
                                </button>
                                <button type="button" mat-stroked-button (click)="downloadIso20022Confirmation(trade.id)"
                                        matTooltip="Download an ISO 20022-shaped settlement confirmation XML for straight-through processing">
                                  <mat-icon>code</mat-icon>
                                  ISO 20022
                                </button>
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

                  <p class="hint" role="note">
                    Settlement is always confirmed by the seller after the buyer declares payment. A seller can
                    offer the demo-only instant option per listing on the Sell tab where the platform allows it.
                  </p>

                  <div class="wallet-defaults">
                    <div class="wallet-defaults-header">
                      <h3>Wallet defaults</h3>
                      <button type="button" mat-stroked-button (click)="addWalletDefault()">
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
                            <mat-option value="CUSTOM_ADDRESS">Bound wallet (address book)</mat-option>
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
                            <input matInput [(ngModel)]="walletDefault.walletAddress" readonly placeholder="Pick a bound wallet from the address book" style="font-family:'IBM Plex Mono',monospace;font-size:13px">
                            <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                                    (click)="pickWallet(a => walletDefault.walletAddress = a)">
                              <mat-icon style="font-size:18px">contacts</mat-icon>
                            </button>
                          </mat-form-field>
                        }

                        <button type="button" mat-icon-button color="warn" (click)="removeWalletDefault(walletDefault)">
                          <mat-icon>delete</mat-icon>
                        </button>
                      </div>
                    }
                  </div>
                </mat-card-content>
                <mat-card-actions align="end">
                  <button type="button" mat-flat-button color="primary" (click)="saveSettings()">Save settings</button>
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

    <ng-template #declarePaymentDialogTpl>
      <h2 mat-dialog-title>Declare Payment</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <p class="dimmed small" style="margin:0">
          This records evidence of payment for the seller to independently confirm — it does not
          settle the trade by itself. Total due: {{ activeTrade?.totalPrice | number:'1.2-4' }} {{ activeTrade?.currency ?? '(currency not recorded)' }}.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Payment reference</mat-label>
          <input matInput maxlength="500" [(ngModel)]="paymentReferenceInput" placeholder="Stablecoin tx hash, SEPA reference, etc.">
        </mat-form-field>
        @if (dialogError) { <p class="error-text" role="alert">{{ dialogError }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" [disabled]="mutating" mat-dialog-close>Cancel</button>
        <button mat-raised-button color="primary" type="button" [disabled]="mutating || !paymentReferenceInput.trim()" (click)="submitDeclarePayment()">
          Declare payment
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #disputeDialogTpl>
      <h2 mat-dialog-title>Dispute Payment</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <p class="dimmed small" style="margin:0">
          The trade moves to "payment unresolved": the units stay reserved and are not re-offered until a
          registry operator resolves it (second approver required). Add evidence notes afterwards. The
          buyer's payment reference was: {{ activeTrade?.paymentReference }}.
        </p>
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" maxlength="2000" [(ngModel)]="disputeReasonInput" placeholder="e.g. no matching payment received"></textarea>
        </mat-form-field>
        @if (dialogError) { <p class="error-text" role="alert">{{ dialogError }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" [disabled]="mutating" mat-dialog-close>Cancel</button>
        <button mat-raised-button color="warn" type="button" [disabled]="mutating || !disputeReasonInput.trim()" (click)="submitDisputePayment()">
          Dispute payment
        </button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #notesDialogTpl>
      <h2 mat-dialog-title>Evidence notes</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <p class="dimmed small" style="margin:0">
          Notes are visible to both parties and to the registry operator who resolves an unresolved payment.
          They cannot be edited or deleted.
        </p>
        @if (notes.length === 0) {
          <p class="dimmed small" style="margin:0">No notes yet.</p>
        }
        @for (note of notes; track note.id) {
          <div class="note">
            <div class="small dimmed">{{ note.actorRole === 'REGISTRY_ADMIN' ? 'Registry operator' : (note.actorEntityId === ownEntityId ? 'You' : 'Counterparty') }} · {{ note.createdAt | date:'medium' }}</div>
            <div>{{ note.text }}</div>
          </div>
        }
        <mat-form-field appearance="outline">
          <mat-label>Add a note</mat-label>
          <textarea matInput rows="3" maxlength="2000" [(ngModel)]="noteInput" placeholder="e.g. bank statement shows transfer on 2 March"></textarea>
        </mat-form-field>
        @if (dialogError) { <p class="error-text" role="alert">{{ dialogError }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" mat-dialog-close>Close</button>
        <button mat-raised-button color="primary" type="button" [disabled]="mutating || !noteInput.trim()" (click)="submitNote()">Add note</button>
      </mat-dialog-actions>
    </ng-template>

    <ng-template #cancelDialogTpl>
      <h2 mat-dialog-title>Cancel Trade</h2>
      <mat-dialog-content style="display:flex;flex-direction:column;gap:12px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Reason</mat-label>
          <textarea matInput rows="3" maxlength="2000" [(ngModel)]="cancelReasonInput" placeholder="e.g. buyer changed their mind"></textarea>
        </mat-form-field>
        @if (dialogError) { <p class="error-text" role="alert">{{ dialogError }}</p> }
      </mat-dialog-content>
      <mat-dialog-actions style="justify-content:flex-end;gap:8px">
        <button mat-stroked-button type="button" [disabled]="mutating" mat-dialog-close>Keep trade</button>
        <button mat-raised-button color="warn" type="button" [disabled]="mutating || !cancelReasonInput.trim()" (click)="submitCancelTrade()">
          Cancel trade
        </button>
      </mat-dialog-actions>
    </ng-template>
  `,
  styles: [`
    .ccy { font-size: 11px; color: var(--rw-text-muted); margin-left: 4px; }
    .ccy.legacy { font-style: italic; }
    .demo-notice { margin-bottom: 16px; }
    .sim-flag { color: var(--rw-text-danger); font-weight: 600; }
    .note { border: 1px solid var(--rw-border); border-radius: 8px; padding: 8px 10px; font-size: 13px; }
    .status-chip { display: inline-block; padding: 2px 8px; border-radius: 10px; font-size: 11.5px; font-weight: 600;
      border: 1px solid var(--rw-border); color: var(--rw-text-secondary); cursor: help; }
    .status-payment_unresolved { border-color: var(--rw-accent); color: var(--rw-accent); }
    .status-settled { color: var(--rw-text-primary); }
    .hint { margin: 0 0 12px; font-size: 13px; color: var(--rw-text-secondary); }
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
    .actions-cell { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .status-hint { font-size: 15px; width: 15px; height: 15px; vertical-align: text-bottom; margin-left: 4px; color: var(--rw-text-muted); cursor: help; }
    .dimmed { color: var(--rw-text-secondary); }
    .small { font-size: 12px; }
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
    .tab-body mat-card-content { overflow-x: auto; }
    .desk-table { min-width: 720px; }
    .load-warning { margin-bottom: 16px; }
    .load-warning .warning-line > div { flex: 1; }
    .error-text { color: var(--rw-text-danger); font-size: 12px; }
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
  private readonly auth = inject(AuthService);

  readonly ownEntityId = this.auth.getEntityId();

  @ViewChild('declarePaymentDialogTpl') declarePaymentDialogTpl!: TemplateRef<unknown>;
  @ViewChild('disputeDialogTpl') disputeDialogTpl!: TemplateRef<unknown>;
  @ViewChild('cancelDialogTpl') cancelDialogTpl!: TemplateRef<unknown>;
  @ViewChild('notesDialogTpl') notesDialogTpl!: TemplateRef<unknown>;

  activeTrade: TradeExecution | null = null;
  paymentReferenceInput = '';
  disputeReasonInput = '';
  cancelReasonInput = '';
  dialogError = '';
  notes: TradeNote[] = [];
  noteInput = '';
  config: TradeConfig | null = null;
  stablecoinRails: CatalogPaymentRail[] = [];

  loading = true;
  tradingDisabled = false;
  loadWarnings: string[] = [];
  mutating = false;

  venues: TradingVenue[] = [];
  offers: TradingOffer[] = [];
  sellableHoldings: SellableHolding[] = [];

  /** Stablecoin payment is offered on the listing being composed (explicitly or via the company default). */
  get sellUsesStablecoin(): boolean {
    return this.sellForm.useCompanyDefaultPaymentOption
      ? this.settings.defaultPaymentOption === 'STABLECOIN'
      : this.sellForm.allowedPaymentOptions.includes('STABLECOIN');
  }

  get hasNonListableHoldings(): boolean {
    return this.sellableHoldings.some((h) => !h.listable);
  }

  get hasSimulatedHoldings(): boolean {
    return this.sellableHoldings.some((h) => h.listable && h.simulatedOffchainSettlement);
  }
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
    allowInstantSettlement: false,
    currency: '',
    paymentRailCode: null,
    targetEntityId: '',
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
    { value: 'CUSTOM_ADDRESS', label: 'Bound wallet (address book)' },
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
    if (this.mutating) return;
    const holding = this.sellableHoldings.find((candidate) => candidate.holderId === this.sellForm.holderId && candidate.listable);
    const quantity = this.sellForm.quantity;
    const pricePerUnit = this.sellForm.pricePerUnit;
    if (!holding || quantity === null || !Number.isFinite(quantity) || quantity <= 0
        || quantity > holding.availableQuantity || pricePerUnit === null || !Number.isFinite(pricePerUnit)
        || pricePerUnit <= 0) {
      this.snackBar.open('Select a holding, quantity, and price.', 'OK', { duration: 3000 });
      return;
    }
    if (!this.sellForm.useCompanyDefaultPaymentOption && this.sellForm.allowedPaymentOptions.length === 0) {
      this.snackBar.open('Select at least one accepted payment option.', 'OK', { duration: 3000 });
      return;
    }
    const currency = this.sellForm.currency.trim().toUpperCase();
    if (currency && !/^[A-Z]{3}$/.test(currency)) {
      this.snackBar.open('Currency must be a three-letter ISO 4217 code, e.g. EUR.', 'OK', { duration: 3500 });
      return;
    }
    if (this.sellUsesStablecoin && !this.sellForm.paymentRailCode) {
      this.snackBar.open('Select the stablecoin payment rail.', 'OK', { duration: 3500 });
      return;
    }
    const rail = this.sellUsesStablecoin ? this.sellForm.paymentRailCode : null;
    const target = this.sellForm.targetEntityId.trim();
    this.mutating = true;
    this.tradingService.createListing({
      holderId: this.sellForm.holderId,
      quantity,
      pricePerUnit,
      useCompanyDefaultPaymentOption: this.sellForm.useCompanyDefaultPaymentOption,
      allowedPaymentOptions: this.sellForm.allowedPaymentOptions,
      allowInstantSettlement: !!this.config?.demoInstantSettlementAvailable && this.sellForm.allowInstantSettlement,
      currency: rail ? null : (currency || null),
      paymentRailCode: rail,
      targetEntityId: target || null,
    }).subscribe({
      next: () => {
        this.mutating = false;
        this.snackBar.open('Sell listing created.', 'OK', { duration: 3000 });
        this.sellForm = {
          holderId: '',
          quantity: null,
          pricePerUnit: null,
          useCompanyDefaultPaymentOption: true,
          allowedPaymentOptions: [],
          allowInstantSettlement: false,
          currency: '',
          paymentRailCode: null,
          targetEntityId: '',
        };
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to create listing.', 'OK', { duration: 4000 });
      },
    });
  }

  submitBuy(): void {
    if (this.mutating) return;
    const offer = this.selectedOffer;
    const quantity = this.buyForm.quantity;
    if (!offer || !this.isExecutable(offer) || quantity === null || !Number.isFinite(quantity) || quantity <= 0
        || quantity > offer.quantityAvailable || !this.buyForm.paymentOption
        || !offer.allowedPaymentOptions.includes(this.buyForm.paymentOption)
        || !offer.supportedOrderTypes.includes(this.buyForm.orderType)
        || (this.buyForm.orderType === 'LIMIT' && (!this.buyForm.limitPrice || this.buyForm.limitPrice <= 0))
        || (this.buyForm.walletPreferenceMode === 'ENDPOINT'
          && !this.walletEndpoints.some((endpoint) => endpoint.id === this.buyForm.endpointId))
        || (this.buyForm.walletPreferenceMode === 'CUSTOM_ADDRESS' && !this.buyForm.walletAddress.trim())) {
      this.snackBar.open('Choose quantity and payment option.', 'OK', { duration: 3000 });
      return;
    }
    this.mutating = true;
    this.tradingService.buy(offer.listingId, {
      quantity,
      orderType: this.buyForm.orderType,
      limitPrice: this.buyForm.orderType === 'LIMIT' ? this.buyForm.limitPrice : null,
      paymentOption: this.buyForm.paymentOption,
      walletPreferenceMode: this.buyForm.walletPreferenceMode,
      endpointId: this.buyForm.walletPreferenceMode === 'ENDPOINT' ? this.buyForm.endpointId : null,
      walletAddress: this.buyForm.walletPreferenceMode === 'CUSTOM_ADDRESS' ? this.buyForm.walletAddress.trim() : null,
    }).subscribe({
      next: (trade) => {
        this.mutating = false;
        this.snackBar.open(
          trade.settlementStatus === 'SETTLED' ? 'Buy order executed and settled.' : 'Buy order executed with pending settlement.',
          'OK',
          { duration: 3500 }
        );
        this.clearSelectedOffer();
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to submit buy order.', 'OK', { duration: 4000 });
      },
    });
  }

  cancelListing(listingId: string): void {
    if (this.mutating || !confirm('Cancel this sell listing? This action cannot be undone.')) return;
    this.mutating = true;
    this.tradingService.cancelListing(listingId).subscribe({
      next: () => {
        this.mutating = false;
        this.snackBar.open('Listing cancelled.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to cancel listing.', 'OK', { duration: 4000 });
      },
    });
  }

  openDeclarePaymentDialog(trade: TradeExecution): void {
    this.activeTrade = trade;
    this.paymentReferenceInput = '';
    this.dialogError = '';
    this.dialog.open(this.declarePaymentDialogTpl, { width: '480px', maxWidth: '95vw' });
  }

  submitDeclarePayment(): void {
    const trade = this.activeTrade;
    const reference = this.paymentReferenceInput.trim();
    if (!trade || !reference || this.mutating) return;
    this.mutating = true;
    this.dialogError = '';
    this.tradingService.declarePayment(trade.id, reference).subscribe({
      next: () => {
        this.mutating = false;
        this.dialog.closeAll();
        this.snackBar.open('Payment declared — waiting for the seller to confirm receipt.', 'OK', { duration: 4000 });
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.dialogError = err?.error?.message ?? 'Failed to declare payment.';
        this.cdr.markForCheck();
      },
    });
  }

  confirmPayment(executionId: string): void {
    if (this.mutating || !confirm('Confirm that payment was received and settle this trade?')) return;
    this.mutating = true;
    this.tradingService.confirmPayment(executionId).subscribe({
      next: () => {
        this.mutating = false;
        this.snackBar.open('Payment confirmed — trade settled.', 'OK', { duration: 3500 });
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to confirm payment.', 'OK', { duration: 4000 });
      },
    });
  }

  openDisputeDialog(trade: TradeExecution): void {
    this.activeTrade = trade;
    this.disputeReasonInput = '';
    this.dialogError = '';
    this.dialog.open(this.disputeDialogTpl, { width: '480px', maxWidth: '95vw' });
  }

  submitDisputePayment(): void {
    const trade = this.activeTrade;
    const reason = this.disputeReasonInput.trim();
    if (!trade || !reason || this.mutating) return;
    this.mutating = true;
    this.dialogError = '';
    this.tradingService.disputePayment(trade.id, reason).subscribe({
      next: () => {
        this.mutating = false;
        this.dialog.closeAll();
        this.snackBar.open(PAYMENT_DISPUTED_TOAST, 'OK', { duration: 6000 });
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.dialogError = err?.error?.message ?? 'Failed to dispute payment.';
        this.cdr.markForCheck();
      },
    });
  }

  openCancelDialog(trade: TradeExecution): void {
    this.activeTrade = trade;
    this.cancelReasonInput = '';
    this.dialogError = '';
    this.dialog.open(this.cancelDialogTpl, { width: '480px', maxWidth: '95vw' });
  }

  submitCancelTrade(): void {
    const trade = this.activeTrade;
    const reason = this.cancelReasonInput.trim();
    if (!trade || !reason || this.mutating) return;
    this.mutating = true;
    this.dialogError = '';
    this.tradingService.cancelTrade(trade.id, reason).subscribe({
      next: () => {
        this.mutating = false;
        this.dialog.closeAll();
        this.snackBar.open('Trade cancelled.', 'OK', { duration: 3000 });
        this.reload();
      },
      error: (err) => {
        this.mutating = false;
        this.dialogError = err?.error?.message ?? 'Failed to cancel trade.';
        this.cdr.markForCheck();
      },
    });
  }

  downloadConfirmation(executionId: string): void {
    this.tradingService.downloadConfirmation(executionId).subscribe({
      next: (pdf) => {
        downloadBlob(pdf, `trade-confirmation-${executionId}.pdf`);
      },
      error: () => this.snackBar.open('Failed to generate the trade confirmation.', 'OK', { duration: 4000 }),
    });
  }

  downloadIso20022Confirmation(executionId: string): void {
    this.tradingService.downloadIso20022Confirmation(executionId).subscribe({
      next: (xml) => {
        downloadBlob(xml, `trade-confirmation-${executionId}.xml`);
      },
      error: () => this.snackBar.open('Failed to generate the ISO 20022 confirmation.', 'OK', { duration: 4000 }),
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
    if (this.mutating) return;
    const invalidDefault = this.settings.walletDefaults.some((walletDefault) =>
      (walletDefault.targetType === 'ENDPOINT'
        && !this.walletEndpoints.some((endpoint) => endpoint.id === walletDefault.endpointId))
      || (walletDefault.targetType === 'CUSTOM_ADDRESS' && !walletDefault.walletAddress?.trim()),
    );
    if (invalidDefault) {
      this.snackBar.open('Complete every wallet default before saving.', 'OK', { duration: 4000 });
      return;
    }
    this.mutating = true;
    this.tradingService.saveSettings(this.settings).subscribe({
      next: (settings) => {
        this.mutating = false;
        this.settings = settings;
        this.snackBar.open('Trader settings saved.', 'OK', { duration: 3000 });
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.mutating = false;
        this.snackBar.open(err?.error?.message ?? 'Failed to save settings.', 'OK', { duration: 4000 });
      },
    });
  }

  pickWallet(setter: (addr: string) => void): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select wallet' }, width: '560px', maxWidth: '95vw' }
    ).afterClosed().subscribe(addr => {
      if (addr) {
        setter(addr);
        this.cdr.markForCheck();
      }
    });
  }

  statusLabel(status: string): string {
    switch (status) {
      case 'PENDING': return 'Awaiting buyer payment';
      case 'AWAITING_SELLER_CONFIRMATION': return 'Awaiting seller confirmation';
      case 'PAYMENT_UNRESOLVED': return 'Payment unresolved';
      case 'SETTLED': return 'Settled';
      case 'FAILED': return 'Failed';
      case 'CANCELLED': return 'Cancelled';
      case 'REFUNDED': return 'Refunded';
      default: return status;
    }
  }

  statusExplanation(trade: TradeExecution): string {
    switch (trade.settlementStatus) {
      case 'PENDING':
        return `The units are reserved. The buyer has ${this.config?.pendingTimeoutHours ?? 'a limited number of'} h to pay and declare the payment.`;
      case 'AWAITING_SELLER_CONFIRMATION':
        return 'The buyer declared a payment. The register changes only when the seller confirms receipt or disputes it.';
      case 'PAYMENT_UNRESOLVED':
        return trade.disputeReason
          ? 'The seller disputed the declared payment. The units stay reserved until a registry operator resolves the trade with a second approver.'
          : 'The trade could not be settled automatically while a payment may have been made. The units stay reserved until a registry operator resolves it with a second approver. Add evidence notes to help.';
      case 'SETTLED':
        return trade.instantSettlement ? 'Settled in demo mode: the register moved with no cash leg.' : 'Settled: the register was updated after the seller confirmed receipt.';
      case 'FAILED':
        return failedExplanation(trade);
      case 'CANCELLED':
        return 'Cancelled by the buyer before payment was declared.';
      case 'REFUNDED':
        return 'Reversed after settlement by a registry operator.';
      default:
        return trade.settlementStatus;
    }
  }

  isCoolingDown(trade: TradeExecution): boolean {
    return !!trade.buyerCooldownUntil && new Date(trade.buyerCooldownUntil).getTime() > Date.now();
  }

  openNotesDialog(trade: TradeExecution): void {
    this.activeTrade = trade;
    this.notes = [];
    this.noteInput = '';
    this.dialogError = '';
    this.tradingService.listNotes(trade.id).subscribe({
      next: (notes) => { this.notes = notes; this.cdr.markForCheck(); },
      error: () => { this.dialogError = 'Notes could not be loaded.'; this.cdr.markForCheck(); },
    });
    this.dialog.open(this.notesDialogTpl, { width: '520px', maxWidth: '95vw' });
  }

  submitNote(): void {
    const trade = this.activeTrade;
    const text = this.noteInput.trim();
    if (!trade || !text || this.mutating) return;
    this.mutating = true;
    this.dialogError = '';
    this.tradingService.addNote(trade.id, text).subscribe({
      next: (note) => {
        this.mutating = false;
        this.notes = [...this.notes, note];
        this.noteInput = '';
        this.cdr.markForCheck();
      },
      error: (err) => {
        this.mutating = false;
        this.dialogError = err?.error?.message ?? 'Failed to add the note.';
        this.cdr.markForCheck();
      },
    });
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

  reload(): void {
    this.loading = true;
    this.loadWarnings = [];
    const failures: { label: string; error: { status?: number } }[] = [];
    const safe = <T>(source: Observable<T>, fallback: T, label: string): Observable<T> => source.pipe(
      catchError((error) => {
        failures.push({ label, error });
        return of(fallback);
      }),
    );
    forkJoin({
      venues: safe(this.tradingService.listVenues(), this.venues, 'Trading venues'),
      offers: safe(this.tradingService.listMarketplaceOffers(), this.offers, 'Marketplace offers'),
      sellableHoldings: safe(this.tradingService.listSellableHoldings(), this.sellableHoldings, 'Sellable holdings'),
      companyListings: safe(this.tradingService.listCompanyListings(), this.companyListings, 'Company listings'),
      history: safe(this.tradingService.listHistory(), this.history, 'Trade history'),
      settings: safe(this.tradingService.getSettings(), this.settings, 'Trader settings'),
      endpoints: safe(this.endpointService.listEndpoints(), this.walletEndpoints, 'Wallet endpoints'),
      config: safe(this.tradingService.getConfig(), this.config, 'Trading configuration'),
      rails: safe(this.tradingService.listPaymentRails(), this.stablecoinRails, 'Payment rails'),
    }).subscribe({
      next: (payload) => {
        this.venues = payload.venues;
        this.offers = payload.offers;
        this.sellableHoldings = payload.sellableHoldings;
        this.companyListings = payload.companyListings;
        this.history = payload.history;
        this.settings = payload.settings;
        this.config = payload.config;
        this.stablecoinRails = (payload.rails ?? []).filter((rail) => rail.railType === 'STABLECOIN');
        this.walletEndpoints = payload.endpoints.filter(endpoint => endpoint.addressType === 'WALLET');
        this.loading = false;
        this.tradingDisabled = failures.some((failure) => failure.error?.status === 501);
        this.loadWarnings = failures
          .filter((failure) => failure.error?.status !== 501)
          .map((failure) => `${failure.label} could not be loaded.`);
        this.cdr.markForCheck();
      },
    });
  }
}
