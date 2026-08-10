import { CommonModule } from '@angular/common';
import { ChangeDetectorRef, Component, OnInit, inject } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { forkJoin } from 'rxjs';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressSpinnerModule } from '@angular/material/progress-spinner';
import { MatSelectModule } from '@angular/material/select';
import { MatSnackBar, MatSnackBarModule } from '@angular/material/snack-bar';
import { MatTabsModule } from '@angular/material/tabs';
import { RepoDeskService } from '../../core/api/repo-desk.service';
import { RepoCollateral, RepoCounterparty, RepoRfq, RepoTrade } from '../../core/models';

interface RfqForm {
  side: 'BORROW_CASH' | 'LEND_CASH'; visibility: 'TARGETED' | 'BROADCAST'; collateralAssetId: string;
  collateralQuantity: number | null; cashAmount: number | null; cashCurrency: string;
  startDate: string; endDate: string; proposedRepoRate: number | null; proposedHaircutBps: number | null;
  settlementMethod: 'DVP' | 'FOP'; expiresAt: string; targetEntityIds: string[]; notes: string;
}

@Component({
  selector: 'app-repo-desk',
  standalone: true,
  imports: [CommonModule, FormsModule, MatButtonModule, MatCardModule, MatChipsModule,
    MatExpansionModule, MatFormFieldModule, MatIconModule, MatInputModule,
    MatProgressSpinnerModule, MatSelectModule, MatSnackBarModule, MatTabsModule],
  template: `
    <div class="repo-page">
      <header class="hero">
        <div>
          <span class="eyebrow">Bilateral funding</span>
          <h1>Repo Desk</h1>
          <p>Negotiate fixed-term sale-and-repurchase trades with known counterparties. This is separate from pooled securities-backed lending.</p>
        </div>
        <button mat-flat-button color="primary" type="button" (click)="showCreate = !showCreate">
          <mat-icon>{{ showCreate ? 'close' : 'add' }}</mat-icon>{{ showCreate ? 'Close' : 'New RFQ' }}
        </button>
      </header>

      <div class="product-note" role="note">
        <mat-icon>info</mat-icon>
        <div><strong>Title-transfer repo workflow</strong><span>Terms are agreed bilaterally, both settlement legs are independently confirmed, and lifecycle events remain visible to both parties.</span></div>
      </div>

      @if (showCreate) {
        <mat-card class="create-card">
          <mat-card-header><mat-card-title>Create request for quote</mat-card-title><mat-card-subtitle>Define the economic terms and choose who may respond.</mat-card-subtitle></mat-card-header>
          <mat-card-content>
            <div class="form-grid">
              <mat-form-field appearance="outline"><mat-label>I want to</mat-label><mat-select [(ngModel)]="form.side"><mat-option value="BORROW_CASH">Borrow cash</mat-option><mat-option value="LEND_CASH">Lend cash</mat-option></mat-select></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Distribution</mat-label><mat-select [(ngModel)]="form.visibility" (selectionChange)="visibilityChanged()"><mat-option value="TARGETED">Targeted RFQ</mat-option><mat-option value="BROADCAST">Broadcast to all traders</mat-option></mat-select></mat-form-field>
              <mat-form-field appearance="outline" class="span-2"><mat-label>Collateral security</mat-label><mat-select [(ngModel)]="form.collateralAssetId">@for (asset of collateral; track asset.id) {<mat-option [value]="asset.id">{{ asset.name }} · {{ asset.isin || asset.assetNumber }}</mat-option>}</mat-select></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Collateral quantity</mat-label><input matInput type="number" min="0" [(ngModel)]="form.collateralQuantity"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Cash amount</mat-label><input matInput type="number" min="0" [(ngModel)]="form.cashAmount"><span matTextSuffix>{{ form.cashCurrency }}</span></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Start date</mat-label><input matInput type="date" [(ngModel)]="form.startDate"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>End date</mat-label><input matInput type="date" [(ngModel)]="form.endDate"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Indicative repo rate</mat-label><input matInput type="number" min="0" step="0.01" [(ngModel)]="form.proposedRepoRate"><span matTextSuffix>% p.a.</span></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Indicative haircut</mat-label><input matInput type="number" min="0" max="100" step="0.01" [(ngModel)]="haircutPercent"><span matTextSuffix>%</span></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>RFQ expires</mat-label><input matInput type="datetime-local" [(ngModel)]="form.expiresAt"></mat-form-field>
              <mat-form-field appearance="outline"><mat-label>Settlement</mat-label><mat-select [(ngModel)]="form.settlementMethod"><mat-option value="DVP">DvP — delivery versus payment</mat-option><mat-option value="FOP">FoP — free of payment</mat-option></mat-select></mat-form-field>
              @if (form.visibility === 'TARGETED') {
                <mat-form-field appearance="outline" class="span-2"><mat-label>Counterparties</mat-label><mat-select multiple [(ngModel)]="form.targetEntityIds">@for (party of counterparties; track party.id) {<mat-option [value]="party.id">{{ party.name }}{{ party.lei ? ' · ' + party.lei : '' }}</mat-option>}</mat-select><mat-hint>Only selected companies can view and quote.</mat-hint></mat-form-field>
              }
              <mat-form-field appearance="outline" class="span-2"><mat-label>Trading note</mat-label><textarea matInput rows="2" maxlength="1000" [(ngModel)]="form.notes"></textarea></mat-form-field>
            </div>
            <div class="form-actions"><button mat-button type="button" (click)="resetForm()">Reset</button><button mat-flat-button color="primary" type="button" [disabled]="busy || !formValid" (click)="createRfq()">Send RFQ</button></div>
          </mat-card-content>
        </mat-card>
      }

      @if (loading) {<div class="loading"><mat-spinner diameter="42"></mat-spinner><span>Loading repo book…</span></div>}
      @else {
        <section class="metrics" aria-label="Repo Desk summary">
          <div><span>{{ openRfqs }}</span><small>Open RFQs</small></div>
          <div><span>{{ actionableRfqs }}</span><small>Awaiting my action</small></div>
          <div><span>{{ activeTrades }}</span><small>Active trades</small></div>
          <div><span>{{ fundingTotal | number:'1.0-0' }}</span><small>Cash principal</small></div>
        </section>

        <mat-tab-group animationDuration="180ms">
          <mat-tab label="RFQ book">
            <div class="tab-body">
              @if (rfqs.length === 0) {<div class="empty"><mat-icon>swap_horiz</mat-icon><h2>No repo RFQs yet</h2><p>Create a targeted request or broadcast terms to the trader network.</p></div>}
              @for (rfq of rfqs; track rfq.id) {
                <mat-expansion-panel class="rfq-panel" [class.owned]="rfq.mine">
                  <mat-expansion-panel-header>
                    <mat-panel-title><span class="side-mark">{{ rfq.side === 'BORROW_CASH' ? 'BC' : 'LC' }}</span><span><strong>{{ rfq.collateralAssetName }}</strong><small>{{ rfq.mine ? 'My RFQ' : rfq.requesterName }} · {{ rfq.side === 'BORROW_CASH' ? 'borrows' : 'lends' }} {{ rfq.cashAmount | number:'1.0-2' }} {{ rfq.cashCurrency }}</small></span></mat-panel-title>
                    <mat-panel-description><span class="status" [attr.data-status]="rfq.status">{{ label(rfq.status) }}</span><span class="term">{{ rfq.startDate | date:'mediumDate' }} → {{ rfq.endDate | date:'mediumDate' }}</span></mat-panel-description>
                  </mat-expansion-panel-header>
                  <div class="terms-grid">
                    <div><small>Collateral</small><strong>{{ rfq.collateralQuantity | number:'1.0-4' }}</strong><span>{{ rfq.collateralIsin || 'No ISIN' }}</span></div>
                    <div><small>Indicative rate</small><strong>{{ rfq.proposedRepoRate == null ? 'Open' : (rfq.proposedRepoRate | number:'1.2-4') + '%' }}</strong><span>ACT/360</span></div>
                    <div><small>Haircut</small><strong>{{ rfq.proposedHaircutBps == null ? 'Open' : (rfq.proposedHaircutBps / 100 | number:'1.2-2') + '%' }}</strong><span>{{ rfq.settlementMethod }}</span></div>
                    <div><small>Distribution</small><strong>{{ rfq.visibility === 'TARGETED' ? 'Targeted' : 'Broadcast' }}</strong><span>Expires {{ rfq.expiresAt | date:'short' }}</span></div>
                  </div>
                  @if (rfq.notes) {<p class="desk-note">{{ rfq.notes }}</p>}

                  @if (rfq.mine && rfq.quotes.length > 0) {
                    <h3>Counterparty quotes</h3>
                    <div class="quote-list">@for (quote of rfq.quotes; track quote.id) {
                      <div class="quote-row"><div><strong>{{ quote.quotingEntityName }}</strong><span>{{ quote.message || 'No message' }}</span></div><div><small>Cash</small><strong>{{ quote.cashAmount | number:'1.0-2' }} {{ rfq.cashCurrency }}</strong></div><div><small>Rate</small><strong>{{ quote.repoRate | number:'1.2-4' }}%</strong></div><div><small>Haircut</small><strong>{{ quote.haircutBps / 100 | number:'1.2-2' }}%</strong></div><div><span class="status" [attr.data-status]="quote.status">{{ label(quote.status) }}</span>@if (quote.status === 'ACTIVE' && rfq.status === 'OPEN') {<button mat-flat-button color="primary" type="button" [disabled]="busy" (click)="accept(rfq, quote.id)">Accept</button>}</div></div>
                    }</div>
                  }

                  @if (rfq.canQuote) {
                    <div class="quote-box"><div><h3>{{ myQuote(rfq) ? 'Replace my quote' : 'Quote this RFQ' }}</h3><p>Other dealers cannot see your terms.</p></div><div class="quote-fields"><mat-form-field appearance="outline"><mat-label>Cash amount</mat-label><input matInput type="number" min="0" [(ngModel)]="quoteCash[rfq.id]"></mat-form-field><mat-form-field appearance="outline"><mat-label>Repo rate</mat-label><input matInput type="number" min="0" step="0.01" [(ngModel)]="quoteRate[rfq.id]"><span matTextSuffix>%</span></mat-form-field><mat-form-field appearance="outline"><mat-label>Haircut</mat-label><input matInput type="number" min="0" max="100" step="0.01" [(ngModel)]="quoteHaircut[rfq.id]"><span matTextSuffix>%</span></mat-form-field><mat-form-field appearance="outline"><mat-label>Valid until</mat-label><input matInput type="datetime-local" [(ngModel)]="quoteValidity[rfq.id]"></mat-form-field></div><mat-form-field appearance="outline" class="full"><mat-label>Message</mat-label><input matInput maxlength="500" [(ngModel)]="quoteMessage[rfq.id]"></mat-form-field><div class="form-actions">@if (myQuote(rfq)?.status === 'ACTIVE') {<button mat-button color="warn" type="button" (click)="withdraw(rfq)">Withdraw</button>}<button mat-flat-button color="primary" type="button" [disabled]="busy || !quoteValid(rfq)" (click)="submitQuote(rfq)">Submit private quote</button></div></div>
                  }
                  <div class="panel-actions">@if (rfq.mine && rfq.status === 'OPEN') {<button mat-button color="warn" type="button" [disabled]="busy" (click)="cancel(rfq)"><mat-icon>cancel</mat-icon>Cancel RFQ</button>}@if (rfq.tradeId) {<span class="matched"><mat-icon>handshake</mat-icon>Matched — track settlement under Trades</span>}</div>
                </mat-expansion-panel>
              }
            </div>
          </mat-tab>

          <mat-tab label="Trades">
            <div class="tab-body">
              @if (trades.length === 0) {<div class="empty"><mat-icon>handshake</mat-icon><h2>No matched repo trades</h2><p>An accepted quote appears here with opening and closing settlement controls.</p></div>}
              @for (trade of trades; track trade.id) {
                <mat-expansion-panel class="trade-panel">
                  <mat-expansion-panel-header><mat-panel-title><span class="trade-icon"><mat-icon>handshake</mat-icon></span><span><strong>{{ trade.collateralAssetName }}</strong><small>{{ trade.cashBorrowerName }} ↔ {{ trade.cashLenderName }}</small></span></mat-panel-title><mat-panel-description><span class="status" [attr.data-status]="trade.status">{{ label(trade.status) }}</span><strong>{{ trade.cashAmount | number:'1.0-2' }} {{ trade.cashCurrency }}</strong></mat-panel-description></mat-expansion-panel-header>
                  <div class="terms-grid trade-terms"><div><small>Repo rate</small><strong>{{ trade.repoRate | number:'1.2-4' }}%</strong><span>ACT/360</span></div><div><small>Repurchase amount</small><strong>{{ trade.repurchaseAmount | number:'1.2-2' }}</strong><span>{{ trade.endDate | date:'mediumDate' }}</span></div><div><small>Collateral / haircut</small><strong>{{ trade.collateralQuantity | number:'1.0-4' }}</strong><span>{{ trade.haircutBps / 100 | number:'1.2-2' }}%</span></div><div><small>My role</small><strong>{{ trade.borrower ? 'Cash borrower' : 'Cash lender' }}</strong><span>{{ trade.settlementMethod }}</span></div></div>

                  @if (trade.status === 'PENDING_OPEN_SETTLEMENT') {
                    <div class="action-strip"><div><strong>Opening settlement</strong><span>{{ canOpenSettle(trade) ? 'Each recipient confirms the leg they received.' : 'Scheduled for ' + (trade.startDate | date:'mediumDate') + '. Confirmation opens on the start date.' }}</span></div><mat-form-field appearance="outline"><mat-label>Settlement reference</mat-label><input matInput [(ngModel)]="references[trade.id]" [disabled]="!canOpenSettle(trade)"></mat-form-field>@if (trade.borrower && !trade.openCashConfirmed) {<button mat-flat-button color="primary" [disabled]="!canOpenSettle(trade)" (click)="confirmOpen(trade, 'CASH')">Confirm cash received</button>}@if (!trade.borrower && !trade.openCollateralConfirmed) {<button mat-flat-button color="primary" [disabled]="!canOpenSettle(trade)" (click)="confirmOpen(trade, 'COLLATERAL')">Confirm collateral received</button>}<div class="leg-state"><mat-icon>{{ trade.openCashConfirmed ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>Cash <mat-icon>{{ trade.openCollateralConfirmed ? 'check_circle' : 'radio_button_unchecked' }}</mat-icon>Collateral</div></div>
                  }
                  @if (trade.status === 'MARGIN_CALL') {
                    <div class="alert-strip"><mat-icon>warning</mat-icon><div><strong>Margin call: {{ trade.marginCallAmount | number:'1.2-2' }} {{ trade.cashCurrency }}</strong><span>Due {{ trade.marginCallDueAt | date:'short' }}</span></div>@if (trade.borrower) {<mat-form-field appearance="outline"><mat-label>Transfer reference</mat-label><input matInput [(ngModel)]="references[trade.id]"></mat-form-field><button mat-flat-button color="primary" (click)="satisfyMargin(trade)">Confirm margin delivered</button>}</div>
                  }
                  @if (trade.status === 'OPEN' && !trade.borrower) {
                    <div class="secondary-actions"><mat-form-field appearance="outline"><mat-label>Margin amount</mat-label><input matInput type="number" min="0" [(ngModel)]="marginAmounts[trade.id]"></mat-form-field><mat-form-field appearance="outline"><mat-label>Due</mat-label><input matInput type="datetime-local" [(ngModel)]="marginDue[trade.id]"></mat-form-field><button mat-stroked-button type="button" (click)="callMargin(trade)">Issue margin call</button></div>
                  }
                  @if (trade.pendingSubstitutionAssetId && !trade.borrower) {<div class="alert-strip neutral"><mat-icon>published_with_changes</mat-icon><div><strong>Collateral substitution requested</strong><span>{{ trade.pendingSubstitutionQuantity | number:'1.0-4' }} units of replacement collateral</span></div><button mat-button color="warn" (click)="decideSubstitution(trade, false)">Reject</button><button mat-flat-button color="primary" (click)="decideSubstitution(trade, true)">Approve</button></div>}
                  @if (trade.status === 'OPEN' && trade.borrower && !trade.pendingSubstitutionAssetId) {<div class="secondary-actions"><mat-form-field appearance="outline"><mat-label>Replacement collateral</mat-label><mat-select [(ngModel)]="substitutionAsset[trade.id]">@for (asset of collateral; track asset.id) {@if (asset.id !== trade.collateralAssetId) {<mat-option [value]="asset.id">{{ asset.name }}</mat-option>}}</mat-select></mat-form-field><mat-form-field appearance="outline"><mat-label>Quantity</mat-label><input matInput type="number" min="0" [(ngModel)]="substitutionQuantity[trade.id]"></mat-form-field><button mat-stroked-button (click)="requestSubstitution(trade)">Request substitution</button></div>}
                  @if (trade.status === 'OPEN' && canClose(trade)) {<div class="panel-actions"><button mat-flat-button color="primary" (click)="initiateClose(trade)">Start closing settlement</button></div>}
                  @if (trade.status === 'PENDING_CLOSE') {<div class="action-strip"><div><strong>Closing settlement</strong><span>Repurchase cash and collateral return are confirmed separately.</span></div><mat-form-field appearance="outline"><mat-label>Settlement reference</mat-label><input matInput [(ngModel)]="references[trade.id]"></mat-form-field>@if (!trade.borrower && !trade.closeCashConfirmed) {<button mat-flat-button color="primary" (click)="confirmClose(trade, 'CASH')">Confirm cash received</button>}@if (trade.borrower && !trade.closeCollateralConfirmed) {<button mat-flat-button color="primary" (click)="confirmClose(trade, 'COLLATERAL')">Confirm collateral returned</button>}</div>}

                  <h3 class="timeline-title">Lifecycle</h3><div class="timeline">@for (event of reversedEvents(trade); track event.id) {<div class="event"><span class="event-dot"></span><div><strong>{{ eventLabel(event.type) }}</strong><small>{{ event.actorName }} · {{ event.createdAt | date:'short' }}</small>@if (event.reference) {<span>Reference: {{ event.reference }}</span>}@if (event.note) {<span>{{ event.note }}</span>}</div></div>}</div>
                </mat-expansion-panel>
              }
            </div>
          </mat-tab>
        </mat-tab-group>
      }
    </div>
  `,
  styles: [`
    :host{display:block}.repo-page{max-width:1440px;margin:0 auto;padding:28px 32px 64px}.hero{display:flex;justify-content:space-between;gap:32px;align-items:flex-start;padding:18px 0 28px}.hero h1{font-size:clamp(2rem,4vw,3.25rem);line-height:1;margin:6px 0 14px;letter-spacing:-.045em}.hero p{max-width:740px;margin:0;color:var(--mat-sys-on-surface-variant);font-size:1.05rem;line-height:1.6}.eyebrow{font:600 .72rem/1 var(--font-mono,monospace);letter-spacing:.14em;text-transform:uppercase;color:var(--mat-sys-primary)}.product-note{display:flex;gap:14px;align-items:center;border:1px solid color-mix(in srgb,var(--mat-sys-primary) 26%,transparent);background:color-mix(in srgb,var(--mat-sys-primary-container) 42%,transparent);border-radius:16px;padding:16px 18px;margin-bottom:24px}.product-note>mat-icon{flex:0 0 24px}.product-note div{display:flex;flex-direction:column;gap:3px}.product-note span{color:var(--mat-sys-on-surface-variant)}.create-card{margin:0 0 28px;border-radius:20px!important}.create-card mat-card-content{padding-top:22px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 18px}.span-2,.full{grid-column:1/-1}.form-actions,.panel-actions{display:flex;justify-content:flex-end;align-items:center;gap:10px}.loading,.empty{min-height:260px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--mat-sys-on-surface-variant);text-align:center}.empty mat-icon{font-size:44px;width:44px;height:44px;color:var(--mat-sys-primary)}.empty h2,.empty p{margin:0}.metrics{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--mat-sys-outline-variant);border-radius:18px;overflow:hidden;margin-bottom:24px}.metrics div{padding:20px 22px;border-right:1px solid var(--mat-sys-outline-variant);display:flex;flex-direction:column}.metrics div:last-child{border:0}.metrics span{font-size:1.65rem;font-weight:650;letter-spacing:-.04em}.metrics small,.terms-grid small{color:var(--mat-sys-on-surface-variant);text-transform:uppercase;letter-spacing:.08em;font-size:.68rem}.tab-body{padding:22px 0;display:flex;flex-direction:column;gap:14px}.rfq-panel,.trade-panel{border:1px solid var(--mat-sys-outline-variant);box-shadow:none!important;border-radius:16px!important}.rfq-panel.owned{border-left:4px solid var(--mat-sys-primary)}mat-panel-title{gap:12px;align-items:center;min-width:0}mat-panel-title>span:last-child{display:flex;flex-direction:column;gap:3px;min-width:0;overflow:hidden}mat-panel-title strong,mat-panel-title small{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}mat-panel-title small{font-weight:400;color:var(--mat-sys-on-surface-variant)}mat-panel-description{justify-content:flex-end;align-items:center;gap:20px}.side-mark,.trade-icon{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;background:var(--mat-sys-secondary-container);color:var(--mat-sys-on-secondary-container);font-weight:750;font-size:.75rem}.status{display:inline-flex;border-radius:99px;padding:5px 9px;font-size:.7rem;font-weight:700;letter-spacing:.04em;background:var(--mat-sys-surface-container-high);white-space:nowrap}.status[data-status=OPEN],.status[data-status=ACTIVE],.status[data-status=CLOSED],.status[data-status=ACCEPTED]{background:#dff5e8;color:#145b35}.status[data-status=MARGIN_CALL],.status[data-status=DEFAULTED]{background:#fde3df;color:#8b241b}.status[data-status=PENDING_OPEN_SETTLEMENT],.status[data-status=PENDING_CLOSE],.status[data-status=MATCHED]{background:#e5edff;color:#234e9b}.term{color:var(--mat-sys-on-surface-variant);font-size:.78rem}.terms-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:8px 0 20px}.terms-grid>div{display:flex;flex-direction:column;gap:4px;padding:14px;border-radius:12px;background:var(--mat-sys-surface-container-low)}.terms-grid strong{font-size:1rem}.terms-grid span{font-size:.78rem;color:var(--mat-sys-on-surface-variant)}.desk-note{padding:14px 16px;border-left:3px solid var(--mat-sys-outline);background:var(--mat-sys-surface-container-low);border-radius:0 10px 10px 0}.quote-list{display:flex;flex-direction:column;gap:8px;margin:10px 0 20px}.quote-row{display:grid;grid-template-columns:minmax(180px,1.4fr) repeat(3,minmax(90px,.65fr)) minmax(150px,.8fr);gap:14px;align-items:center;padding:14px;border:1px solid var(--mat-sys-outline-variant);border-radius:12px}.quote-row>div{display:flex;flex-direction:column;gap:3px}.quote-row>div:last-child{align-items:flex-end}.quote-row span,.quote-row small{color:var(--mat-sys-on-surface-variant);font-size:.75rem}.quote-box{background:var(--mat-sys-surface-container-low);padding:18px;border-radius:14px;margin-top:12px}.quote-box h3,.quote-box p{margin:0}.quote-box p{color:var(--mat-sys-on-surface-variant);font-size:.82rem;margin-top:3px}.quote-fields{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:16px}.quote-box .full{width:100%}.matched{display:flex;align-items:center;gap:7px;color:var(--mat-sys-primary);font-weight:600}.action-strip,.alert-strip,.secondary-actions{display:flex;align-items:center;gap:14px;padding:16px;margin:12px 0;background:var(--mat-sys-primary-container);border-radius:14px}.action-strip>div:first-child,.alert-strip>div{display:flex;flex-direction:column;flex:1}.action-strip mat-form-field,.secondary-actions mat-form-field,.alert-strip mat-form-field{margin-bottom:-20px;min-width:180px}.leg-state{display:flex!important;flex-direction:row!important;align-items:center;gap:5px;font-size:.8rem}.leg-state mat-icon{color:var(--mat-sys-primary)}.alert-strip{background:#fff1d8;color:#4f3900}.alert-strip.neutral{background:var(--mat-sys-surface-container-high);color:inherit}.secondary-actions{background:var(--mat-sys-surface-container-low);justify-content:flex-end}.timeline-title{margin-top:24px}.timeline{padding:4px 0 4px 9px}.event{display:grid;grid-template-columns:16px 1fr;gap:10px;position:relative;padding-bottom:18px}.event:before{content:'';position:absolute;left:4px;top:12px;bottom:-2px;width:1px;background:var(--mat-sys-outline-variant)}.event:last-child:before{display:none}.event-dot{width:9px;height:9px;border-radius:50%;background:var(--mat-sys-primary);margin-top:5px;z-index:1}.event>div{display:flex;flex-direction:column;gap:2px}.event small,.event span{font-size:.78rem;color:var(--mat-sys-on-surface-variant)}
    @media(max-width:900px){.repo-page{padding:20px 16px 48px}.metrics{grid-template-columns:repeat(2,1fr)}.metrics div:nth-child(2){border-right:0}.metrics div:nth-child(-n+2){border-bottom:1px solid var(--mat-sys-outline-variant)}.form-grid,.terms-grid{grid-template-columns:1fr 1fr}.quote-fields{grid-template-columns:1fr 1fr}.quote-row{grid-template-columns:1fr 1fr}.action-strip,.alert-strip,.secondary-actions{align-items:stretch;flex-direction:column}.action-strip mat-form-field,.secondary-actions mat-form-field,.alert-strip mat-form-field{width:100%}mat-panel-description .term{display:none}}
    @media(max-width:600px){.hero{flex-direction:column}.hero button{width:100%}.form-grid,.terms-grid,.quote-fields{grid-template-columns:1fr}.span-2{grid-column:auto}.quote-row{grid-template-columns:1fr}.quote-row>div:last-child{align-items:flex-start}mat-panel-description{display:none}.metrics div{padding:14px}.metrics span{font-size:1.3rem}}
    @media(prefers-color-scheme:dark){.status[data-status=OPEN],.status[data-status=ACTIVE],.status[data-status=CLOSED],.status[data-status=ACCEPTED]{background:#153d2b;color:#a7efc3}.status[data-status=MARGIN_CALL],.status[data-status=DEFAULTED]{background:#52231f;color:#ffc2b9}.status[data-status=PENDING_OPEN_SETTLEMENT],.status[data-status=PENDING_CLOSE],.status[data-status=MATCHED]{background:#1d345e;color:#bed1ff}.alert-strip{background:#473817;color:#ffe19a}}
  `]
})
export class RepoDeskComponent implements OnInit {
  private readonly api = inject(RepoDeskService); private readonly snack = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  loading = true; busy = false; showCreate = false; rfqs: RepoRfq[] = []; trades: RepoTrade[] = [];
  counterparties: RepoCounterparty[] = []; collateral: RepoCollateral[] = [];
  form: RfqForm = this.freshForm(); haircutPercent: number | null = 2;
  quoteCash: Record<string, number | null> = {}; quoteRate: Record<string, number | null> = {};
  quoteHaircut: Record<string, number | null> = {}; quoteValidity: Record<string, string> = {}; quoteMessage: Record<string, string> = {};
  references: Record<string, string> = {}; marginAmounts: Record<string, number | null> = {}; marginDue: Record<string, string> = {};
  substitutionAsset: Record<string, string> = {}; substitutionQuantity: Record<string, number | null> = {};

  ngOnInit(): void { this.reload(); }
  reload(): void { this.loading = true; forkJoin({ rfqs: this.api.listRfqs(), trades: this.api.listTrades(), counterparties: this.api.counterparties(), collateral: this.api.collateral() }).subscribe({ next: data => { Object.assign(this, data); this.seedDrafts(); this.loading = false; this.cdr.markForCheck(); }, error: e => { this.loading = false; this.fail(e); this.cdr.markForCheck(); } }); }
  get openRfqs(): number { return this.rfqs.filter(r => r.status === 'OPEN').length; }
  get actionableRfqs(): number { return this.rfqs.filter(r => (r.mine && r.quotes.some(q => q.status === 'ACTIVE')) || r.canQuote).length; }
  get activeTrades(): number { return this.trades.filter(t => !['CLOSED','CANCELLED','DEFAULTED'].includes(t.status)).length; }
  get fundingTotal(): number { return this.trades.reduce((sum, trade) => sum + trade.cashAmount, 0); }
  get formValid(): boolean { return !!(this.form.collateralAssetId && this.form.collateralQuantity && this.form.cashAmount && this.form.startDate && this.form.endDate && this.form.expiresAt && (this.form.visibility === 'BROADCAST' || this.form.targetEntityIds.length)); }
  visibilityChanged(): void { if (this.form.visibility === 'BROADCAST') this.form.targetEntityIds = []; }
  resetForm(): void { this.form = this.freshForm(); this.haircutPercent = 2; }
  createRfq(): void { if (!this.formValid) return; this.mutate(this.api.createRfq({ ...this.form, proposedHaircutBps: this.haircutPercent == null ? null : Math.round(this.haircutPercent * 100), expiresAt: new Date(this.form.expiresAt).toISOString() }), 'RFQ sent', () => { this.showCreate = false; this.resetForm(); }); }
  cancel(rfq: RepoRfq): void { this.mutate(this.api.cancelRfq(rfq.id), 'RFQ cancelled'); }
  accept(rfq: RepoRfq, quoteId: string): void { this.mutate(this.api.acceptQuote(rfq.id, quoteId), 'Quote accepted — trade created'); }
  withdraw(rfq: RepoRfq): void { this.mutate(this.api.withdrawQuote(rfq.id), 'Quote withdrawn'); }
  myQuote(rfq: RepoRfq) { return rfq.mine ? undefined : rfq.quotes[0]; }
  quoteValid(rfq: RepoRfq): boolean { return !!(this.quoteCash[rfq.id] && this.quoteRate[rfq.id] != null && this.quoteHaircut[rfq.id] != null && this.quoteValidity[rfq.id]); }
  submitQuote(rfq: RepoRfq): void { if (!this.quoteValid(rfq)) return; this.mutate(this.api.quote(rfq.id, { cashAmount: this.quoteCash[rfq.id], repoRate: this.quoteRate[rfq.id], haircutBps: Math.round((this.quoteHaircut[rfq.id] || 0) * 100), validUntil: new Date(this.quoteValidity[rfq.id]).toISOString(), message: this.quoteMessage[rfq.id] || null }), 'Private quote submitted'); }
  confirmOpen(t: RepoTrade, leg: 'CASH'|'COLLATERAL'): void { const ref=this.references[t.id]; if (!ref) return this.warn('Enter the settlement reference first'); this.mutateTrade(this.api.confirmOpen(t.id,leg,ref),'Opening leg confirmed'); }
  confirmClose(t: RepoTrade, leg: 'CASH'|'COLLATERAL'): void { const ref=this.references[t.id]; if (!ref) return this.warn('Enter the settlement reference first'); this.mutateTrade(this.api.confirmClose(t.id,leg,ref),'Closing leg confirmed'); }
  callMargin(t: RepoTrade): void { const amount=this.marginAmounts[t.id], due=this.marginDue[t.id]; if (!amount || !due) return this.warn('Enter an amount and deadline'); this.mutateTrade(this.api.marginCall(t.id,amount,new Date(due).toISOString(),'Variation margin required'),'Margin call issued'); }
  satisfyMargin(t: RepoTrade): void { const ref=this.references[t.id]; if (!ref) return this.warn('Enter the transfer reference first'); this.mutateTrade(this.api.satisfyMargin(t.id,ref,'Margin delivered'),'Margin delivery confirmed'); }
  requestSubstitution(t: RepoTrade): void { const asset=this.substitutionAsset[t.id], qty=this.substitutionQuantity[t.id]; if (!asset || !qty) return this.warn('Choose replacement collateral and quantity'); this.mutateTrade(this.api.requestSubstitution(t.id,asset,qty,'Collateral substitution request'),'Substitution requested'); }
  decideSubstitution(t: RepoTrade, approve: boolean): void { this.mutateTrade(this.api.decideSubstitution(t.id,approve,approve?'Replacement accepted':'Replacement declined'), approve?'Substitution approved':'Substitution rejected'); }
  initiateClose(t: RepoTrade): void { this.mutateTrade(this.api.initiateClose(t.id),'Closing settlement started'); }
  canClose(t: RepoTrade): boolean { return new Date(`${t.endDate}T00:00:00Z`) <= new Date(); }
  canOpenSettle(t: RepoTrade): boolean { return new Date(`${t.startDate}T00:00:00Z`) <= new Date(); }
  reversedEvents(t: RepoTrade) { return [...t.events].reverse(); }
  label(value: string): string { return value.toLowerCase().split('_').map(v => v[0].toUpperCase()+v.slice(1)).join(' '); }
  eventLabel(value: string): string { return this.label(value); }
  private mutate(request: ReturnType<RepoDeskService['cancelRfq']>, success: string, after?: () => void): void { this.busy=true; request.subscribe({next:()=>{this.busy=false;this.snack.open(success,'Close',{duration:3000});after?.();this.reload();},error:e=>{this.busy=false;this.fail(e);this.cdr.markForCheck();}}); }
  private mutateTrade(request: ReturnType<RepoDeskService['confirmOpen']>, success: string): void { this.busy=true; request.subscribe({next:trade=>{this.trades=this.trades.map(t=>t.id===trade.id?trade:t);this.busy=false;this.snack.open(success,'Close',{duration:3000});this.cdr.markForCheck();},error:e=>{this.busy=false;this.fail(e);this.cdr.markForCheck();}}); }
  private seedDrafts(): void { for (const r of this.rfqs) { this.quoteCash[r.id] ??= r.cashAmount; this.quoteRate[r.id] ??= r.proposedRepoRate; this.quoteHaircut[r.id] ??= r.proposedHaircutBps == null ? 2 : r.proposedHaircutBps/100; this.quoteValidity[r.id] ||= this.localDateTime(new Date(Math.min(new Date(r.expiresAt).getTime(),Date.now()+3_600_000))); } }
  private freshForm(): RfqForm { const start=new Date();start.setUTCDate(start.getUTCDate()+1);const end=new Date(start);end.setUTCDate(end.getUTCDate()+7);const expires=new Date();expires.setHours(expires.getHours()+4);return {side:'BORROW_CASH',visibility:'TARGETED',collateralAssetId:'',collateralQuantity:null,cashAmount:null,cashCurrency:'EUR',startDate:start.toISOString().slice(0,10),endDate:end.toISOString().slice(0,10),proposedRepoRate:3.25,proposedHaircutBps:200,settlementMethod:'DVP',expiresAt:this.localDateTime(expires),targetEntityIds:[],notes:''}; }
  private localDateTime(date: Date): string { const offset=date.getTimezoneOffset()*60000;return new Date(date.getTime()-offset).toISOString().slice(0,16); }
  private warn(message:string):void{this.snack.open(message,'Close',{duration:3500});}
  private fail(error:any):void{this.snack.open(error?.error?.message || 'Repo Desk request failed','Close',{duration:6000});}
}
