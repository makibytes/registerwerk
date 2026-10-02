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
import { MatSlideToggleModule } from '@angular/material/slide-toggle';
import { AuthService } from '../../core/auth/auth.service';
import { RepoCollateral, RepoCounterparty, RepoParticipation, RepoQuote, RepoRfq, RepoTrade } from '../../core/models';
import { CashPrincipalLine, humanize, openCashPrincipal, REPO_TERMINAL_STATES, settlementLabel, SETTLEMENT_SELF_CONFIRMED } from './repo-desk.labels';
import { RepoTradePanelComponent } from './repo-trade-panel.component';

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
    MatProgressSpinnerModule, MatSelectModule, MatSlideToggleModule, MatSnackBarModule, MatTabsModule,
    RepoTradePanelComponent],
  template: `
    <div class="repo-page">
      <header class="hero">
        <div>
          <span class="eyebrow">Bilateral funding</span>
          <h1>Repo Desk</h1>
          <p>Negotiate fixed-term sale-and-repurchase trades with known counterparties. This is separate from pooled securities-backed lending.</p>
        </div>
        <button mat-flat-button color="primary" type="button" [disabled]="!participation?.participating" (click)="showCreate = !showCreate">
          <mat-icon>{{ showCreate ? 'close' : 'add' }}</mat-icon>{{ showCreate ? 'Close' : 'New RFQ' }}
        </button>
      </header>

      <div class="product-note" role="note">
        <mat-icon>info</mat-icon>
        <div><strong>Title-transfer repo workflow</strong><span>Terms are agreed bilaterally. Settlement is bilateral and self-confirmed: the payer declares each leg sent, the recipient confirms receipt, and Registerwerk neither moves nor verifies cash or securities. Lifecycle events remain visible to both parties.</span></div>
      </div>

      @if (participation && !loading) {
        <section class="participation" [class.inactive]="!participation.participating" aria-label="Repo Desk participation">
          <mat-icon>{{ participation.participating ? 'verified_user' : 'lock' }}</mat-icon>
          <div class="p-text">
            @if (participation.participating) {
              <strong>Your company takes part in the Repo Desk</strong>
              <span>Opted in {{ participation.optedInAt | date:'mediumDate' }}. Only professional or eligible-counterparty clients that passed KYC and screening can trade here;
                if your status changes, new actions are blocked and open trades are flagged.</span>
              @if (participation.eligibilityIssues.length > 0) {
                <span class="issue">Currently not eligible: {{ participation.eligibilityIssues.join('; ') }}.</span>
              }
            } @else {
              <strong>Opt in to use the Repo Desk</strong>
              <span>Participation is optional and needs a company administrator. The Repo Desk is for professional clients and eligible counterparties only,
                and every participant needs a valid LEI before terms can be agreed.</span>
              @if (participation.eligibilityIssues.length > 0) {
                <span class="issue">Your company is currently not eligible: {{ participation.eligibilityIssues.join('; ') }}.</span>
              }
            }
            @if (isCompanyAdmin) {
              <mat-slide-toggle [checked]="listedChoice" (change)="listedChoice = $event.checked" [disabled]="busy">
                List my company in the counterparty directory (name and LEI visible to other participants)
              </mat-slide-toggle>
            } @else {
              <span>Ask a company administrator to {{ participation.participating ? 'change' : 'start' }} participation.</span>
            }
          </div>
          @if (isCompanyAdmin) {
            <div class="p-actions">
              <button mat-flat-button color="primary" type="button" [disabled]="busy" (click)="optIn()">{{ participation.participating ? 'Save listing' : 'Opt in' }}</button>
              @if (participation.participating) {<button mat-button color="warn" type="button" [disabled]="busy" (click)="optOut()">Opt out</button>}
            </div>
          }
        </section>
      }

      @if (showCreate && participation?.participating) {
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
              <mat-form-field appearance="outline"><mat-label>Settlement</mat-label><mat-select [(ngModel)]="form.settlementMethod"><mat-option value="DVP">{{ settlementLabel('DVP') }}</mat-option><mat-option value="FOP">{{ settlementLabel('FOP') }}</mat-option></mat-select></mat-form-field>
              @if (form.visibility === 'TARGETED') {
                <mat-form-field appearance="outline" class="span-2"><mat-label>Counterparties</mat-label><mat-select multiple [(ngModel)]="form.targetEntityIds">@for (party of counterparties; track party.id) {<mat-option [value]="party.id">{{ party.name }}{{ party.lei ? ' · ' + party.lei : '' }}</mat-option>}</mat-select><mat-hint>Only selected companies can view and quote. The directory lists opted-in, eligible professional companies that chose to be listed.</mat-hint></mat-form-field>
              }
              <mat-form-field appearance="outline" class="span-2"><mat-label>Trading note</mat-label><textarea matInput rows="2" maxlength="1000" [(ngModel)]="form.notes"></textarea></mat-form-field>
            </div>
            <div class="form-actions"><button mat-button type="button" (click)="resetForm()">Reset</button><button mat-flat-button color="primary" type="button" [disabled]="busy || !formValid" (click)="createRfq()">Send RFQ</button></div>
          </mat-card-content>
        </mat-card>
      }

      @if (loading) {<div class="loading"><mat-spinner diameter="42"></mat-spinner><span>Loading repo book…</span></div>}
      @else if (participation?.participating) {
        <section class="metrics" aria-label="Repo Desk summary">
          <div><span>{{ openRfqs }}</span><small>Open RFQs</small></div>
          <div><span>{{ actionableRfqs }}</span><small>Awaiting my action</small></div>
          <div><span>{{ activeTrades }}</span><small>Active trades</small></div>
          @for (line of cashPrincipal; track line.role + line.currency) {
            <div><span>{{ line.amount | number:'1.0-0' }} {{ line.currency }}</span><small>{{ line.role }} (open trades)</small></div>
          }
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
                    <div><small>Indicative rate</small><strong>{{ rfq.proposedRepoRate === null ? 'Open' : (rfq.proposedRepoRate | number:'1.2-4') + '%' }}</strong><span>ACT/360</span></div>
                    <div><small>Haircut</small><strong>{{ rfq.proposedHaircutBps === null ? 'Open' : (rfq.proposedHaircutBps / 100 | number:'1.2-2') + '%' }}</strong><span>{{ settlementLabel(rfq.settlementMethod) }}</span></div>
                    <div><small>Distribution</small><strong>{{ rfq.visibility === 'TARGETED' ? 'Targeted' : 'Broadcast' }}</strong><span>Expires {{ rfq.expiresAt | date:'short' }}</span></div>
                  </div>
                  @if (rfq.notes) {<p class="desk-note">{{ rfq.notes }}</p>}

                  @if (rfq.mine && rfq.quotes.length > 0) {
                    <h3>Counterparty quotes</h3>
                    <div class="quote-list">@for (quote of rfq.quotes; track quote.id) {
                      <div class="quote-row"><div><strong>{{ quote.quotingEntityName }}</strong><span>v{{ quote.version }} · {{ quote.message || 'No message' }}</span></div><div><small>Cash</small><strong>{{ quote.cashAmount | number:'1.0-2' }} {{ rfq.cashCurrency }}</strong></div><div><small>Rate</small><strong>{{ quote.repoRate | number:'1.2-4' }}%</strong></div><div><small>Haircut</small><strong>{{ quote.haircutBps / 100 | number:'1.2-2' }}%</strong></div><div><span class="status" [attr.data-status]="quote.status">{{ label(quote.status) }}</span>@if (quote.status === 'ACTIVE' && rfq.status === 'OPEN') {<button mat-flat-button color="primary" type="button" [disabled]="busy" (click)="accept(rfq, quote)">Accept</button>}</div></div>
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
              @if (trades.length === 0) {<div class="empty"><mat-icon>handshake</mat-icon><h2>No matched repo trades</h2><p>An accepted quote appears here with settlement declarations, margin, default and dispute controls.</p></div>}
              @for (trade of trades; track trade.id) {
                <mat-expansion-panel class="trade-panel">
                  <mat-expansion-panel-header><mat-panel-title><span class="trade-icon"><mat-icon>handshake</mat-icon></span><span><strong>{{ trade.collateralAssetName }}</strong><small>{{ trade.cashBorrowerName }} ↔ {{ trade.cashLenderName }}</small></span></mat-panel-title><mat-panel-description><span class="status" [attr.data-status]="trade.status">{{ label(trade.status) }}</span><strong>{{ trade.cashAmount | number:'1.0-2' }} {{ trade.cashCurrency }}</strong></mat-panel-description></mat-expansion-panel-header>
                  <app-repo-trade-panel [trade]="trade" [collateral]="collateral" (updated)="tradeUpdated($event)"></app-repo-trade-panel>
                </mat-expansion-panel>
              }
            </div>
          </mat-tab>
        </mat-tab-group>
      }
    </div>
  `,
  styles: [`
    :host{display:block}.participation{display:flex;gap:16px;align-items:flex-start;padding:16px 18px;margin-bottom:24px;border:1px solid var(--rw-border);border-radius:16px;background:var(--rw-surface-soft)}.participation.inactive{border-color:var(--rw-accent);background:var(--rw-accent-subtle)}.participation>mat-icon{flex:none;color:var(--rw-accent)}.p-text{display:flex;flex-direction:column;gap:6px;flex:1;min-width:0}.p-text span{color:var(--rw-text-secondary);font-size:.85rem}.p-text .issue{color:var(--rw-text-danger)}.p-actions{display:flex;gap:8px;flex:none}@media(max-width:700px){.participation{flex-direction:column}.p-actions{width:100%}}.repo-page{max-width:1440px;margin:0 auto;padding:28px 32px 64px}.hero{display:flex;justify-content:space-between;gap:32px;align-items:flex-start;padding:18px 0 28px}.hero h1{font-size:clamp(2rem,4vw,3.25rem);line-height:1;margin:6px 0 14px;letter-spacing:-.045em}.hero p{max-width:740px;margin:0;color:var(--mat-sys-on-surface-variant);font-size:1.05rem;line-height:1.6}.eyebrow{font:600 .72rem/1 var(--font-mono,monospace);letter-spacing:.14em;text-transform:uppercase;color:var(--mat-sys-primary)}.product-note{display:flex;gap:14px;align-items:center;border:1px solid color-mix(in srgb,var(--mat-sys-primary) 26%,transparent);background:color-mix(in srgb,var(--mat-sys-primary-container) 42%,transparent);border-radius:16px;padding:16px 18px;margin-bottom:24px}.product-note>mat-icon{flex:0 0 24px}.product-note div{display:flex;flex-direction:column;gap:3px}.product-note span{color:var(--mat-sys-on-surface-variant)}.create-card{margin:0 0 28px;border-radius:20px!important}.create-card mat-card-content{padding-top:22px}.form-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 18px}.span-2,.full{grid-column:1/-1}.form-actions,.panel-actions{display:flex;justify-content:flex-end;align-items:center;gap:10px}.loading,.empty{min-height:260px;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:12px;color:var(--mat-sys-on-surface-variant);text-align:center}.empty mat-icon{font-size:44px;width:44px;height:44px;color:var(--mat-sys-primary)}.empty h2,.empty p{margin:0}.metrics{display:grid;grid-template-columns:repeat(4,1fr);border:1px solid var(--mat-sys-outline-variant);border-radius:18px;overflow:hidden;margin-bottom:24px}.metrics div{padding:20px 22px;border-right:1px solid var(--mat-sys-outline-variant);display:flex;flex-direction:column}.metrics div:last-child{border:0}.metrics span{font-size:1.65rem;font-weight:650;letter-spacing:-.04em}.metrics small,.terms-grid small{color:var(--mat-sys-on-surface-variant);text-transform:uppercase;letter-spacing:.08em;font-size:.68rem}.tab-body{padding:22px 0;display:flex;flex-direction:column;gap:14px}.rfq-panel,.trade-panel{border:1px solid var(--mat-sys-outline-variant);box-shadow:none!important;border-radius:16px!important}.rfq-panel.owned{border-left:4px solid var(--mat-sys-primary)}mat-panel-title{gap:12px;align-items:center;min-width:0}mat-panel-title>span:last-child{display:flex;flex-direction:column;gap:3px;min-width:0;overflow:hidden}mat-panel-title strong,mat-panel-title small{display:block;max-width:100%;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}mat-panel-title small{font-weight:400;color:var(--mat-sys-on-surface-variant)}mat-panel-description{justify-content:flex-end;align-items:center;gap:20px}.side-mark,.trade-icon{width:38px;height:38px;border-radius:12px;display:grid;place-items:center;background:var(--mat-sys-secondary-container);color:var(--mat-sys-on-secondary-container);font-weight:750;font-size:.75rem}.status[data-status=DISPUTED]{background:var(--rw-pending-bg);color:var(--rw-pending-fg)}.status{display:inline-flex;border-radius:99px;padding:5px 9px;font-size:.7rem;font-weight:700;letter-spacing:.04em;background:var(--mat-sys-surface-container-high);white-space:nowrap}.status[data-status=OPEN],.status[data-status=ACTIVE],.status[data-status=CLOSED],.status[data-status=ACCEPTED]{background:#dff5e8;color:#145b35}.status[data-status=MARGIN_CALL],.status[data-status=DEFAULTED]{background:#fde3df;color:#8b241b}.status[data-status=PENDING_OPEN_SETTLEMENT],.status[data-status=PENDING_CLOSE],.status[data-status=MATCHED]{background:#e5edff;color:#234e9b}.term{color:var(--mat-sys-on-surface-variant);font-size:.78rem}.terms-grid{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;padding:8px 0 20px}.terms-grid>div{display:flex;flex-direction:column;gap:4px;padding:14px;border-radius:12px;background:var(--mat-sys-surface-container-low)}.terms-grid strong{font-size:1rem}.terms-grid span{font-size:.78rem;color:var(--mat-sys-on-surface-variant)}.desk-note{padding:14px 16px;border-left:3px solid var(--mat-sys-outline);background:var(--mat-sys-surface-container-low);border-radius:0 10px 10px 0}.quote-list{display:flex;flex-direction:column;gap:8px;margin:10px 0 20px}.quote-row{display:grid;grid-template-columns:minmax(180px,1.4fr) repeat(3,minmax(90px,.65fr)) minmax(150px,.8fr);gap:14px;align-items:center;padding:14px;border:1px solid var(--mat-sys-outline-variant);border-radius:12px}.quote-row>div{display:flex;flex-direction:column;gap:3px}.quote-row>div:last-child{align-items:flex-end}.quote-row span,.quote-row small{color:var(--mat-sys-on-surface-variant);font-size:.75rem}.quote-box{background:var(--mat-sys-surface-container-low);padding:18px;border-radius:14px;margin-top:12px}.quote-box h3,.quote-box p{margin:0}.quote-box p{color:var(--mat-sys-on-surface-variant);font-size:.82rem;margin-top:3px}.quote-fields{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin-top:16px}.quote-box .full{width:100%}.matched{display:flex;align-items:center;gap:7px;color:var(--mat-sys-primary);font-weight:600}
    @media(max-width:900px){.repo-page{padding:20px 16px 48px}.metrics{grid-template-columns:repeat(2,1fr)}.metrics div:nth-child(2){border-right:0}.metrics div:nth-child(-n+2){border-bottom:1px solid var(--mat-sys-outline-variant)}.form-grid,.terms-grid{grid-template-columns:1fr 1fr}.quote-fields{grid-template-columns:1fr 1fr}.quote-row{grid-template-columns:1fr 1fr}mat-panel-description .term{display:none}}
    @media(max-width:600px){.hero{flex-direction:column}.hero button{width:100%}.form-grid,.terms-grid,.quote-fields{grid-template-columns:1fr}.span-2{grid-column:auto}.quote-row{grid-template-columns:1fr}.quote-row>div:last-child{align-items:flex-start}mat-panel-description{display:none}.metrics div{padding:14px}.metrics span{font-size:1.3rem}}
    @media(prefers-color-scheme:dark){.status[data-status=OPEN],.status[data-status=ACTIVE],.status[data-status=CLOSED],.status[data-status=ACCEPTED]{background:#153d2b;color:#a7efc3}.status[data-status=MARGIN_CALL],.status[data-status=DEFAULTED]{background:#52231f;color:#ffc2b9}.status[data-status=PENDING_OPEN_SETTLEMENT],.status[data-status=PENDING_CLOSE],.status[data-status=MATCHED]{background:#1d345e;color:#bed1ff}}
  `]
})
export class RepoDeskComponent implements OnInit {
  private readonly api = inject(RepoDeskService); private readonly snack = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef); private readonly auth = inject(AuthService);
  protected readonly settlementLabel = settlementLabel;
  protected readonly selfConfirmedLabel = SETTLEMENT_SELF_CONFIRMED;
  loading = true; busy = false; showCreate = false; rfqs: RepoRfq[] = []; trades: RepoTrade[] = [];
  participation: RepoParticipation | null = null; listedChoice = false;
  counterparties: RepoCounterparty[] = []; collateral: RepoCollateral[] = [];
  form: RfqForm = this.freshForm(); haircutPercent: number | null = 2;
  quoteCash: Record<string, number | null> = {}; quoteRate: Record<string, number | null> = {};
  quoteHaircut: Record<string, number | null> = {}; quoteValidity: Record<string, string> = {}; quoteMessage: Record<string, string> = {};

  get isCompanyAdmin(): boolean { return this.auth.hasRole('COMPANY_ADMIN'); }

  ngOnInit(): void { this.reload(); }
  reload(): void {
    this.loading = true;
    // The desk endpoints answer 403 until the company opted in, so participation is read first.
    this.api.participation().subscribe({
      next: participation => {
        this.participation = participation; this.listedChoice = participation.listed;
        if (!participation.participating) { this.rfqs = []; this.trades = []; this.loading = false; this.cdr.markForCheck(); return; }
        forkJoin({ rfqs: this.api.listRfqs(), trades: this.api.listTrades(), counterparties: this.api.counterparties(), collateral: this.api.collateral() }).subscribe({
          next: data => { Object.assign(this, data); this.seedDrafts(); this.loading = false; this.cdr.markForCheck(); },
          error: e => { this.loading = false; this.fail(e); this.cdr.markForCheck(); },
        });
      },
      error: e => { this.loading = false; this.fail(e); this.cdr.markForCheck(); },
    });
  }
  optIn(): void { this.changeParticipation(this.api.optIn(this.listedChoice), 'Repo Desk participation saved'); }
  optOut(): void { this.changeParticipation(this.api.optOut(), 'Opted out of the Repo Desk. Open trades continue; new RFQs and quotes are blocked.'); }
  private changeParticipation(request: ReturnType<RepoDeskService['optIn']>, success: string): void {
    this.busy = true;
    request.subscribe({
      next: () => { this.busy = false; this.snack.open(success, 'Close', { duration: 4000 }); this.reload(); },
      error: e => { this.busy = false; this.fail(e); this.cdr.markForCheck(); },
    });
  }
  get openRfqs(): number { return this.rfqs.filter(r => r.status === 'OPEN').length; }
  get actionableRfqs(): number { return this.rfqs.filter(r => (r.mine && r.quotes.some(q => q.status === 'ACTIVE')) || r.canQuote).length; }
  get activeTrades(): number { return this.trades.filter(t => !REPO_TERMINAL_STATES.includes(t.status)).length; }
  /** Open trades only, per role and currency - never one mixed number (8A-09). */
  get cashPrincipal(): CashPrincipalLine[] { return openCashPrincipal(this.trades); }
  get formValid(): boolean { return !!(this.form.collateralAssetId && this.form.collateralQuantity && this.form.cashAmount && this.form.startDate && this.form.endDate && this.form.expiresAt && (this.form.visibility === 'BROADCAST' || this.form.targetEntityIds.length)); }
  visibilityChanged(): void { if (this.form.visibility === 'BROADCAST') this.form.targetEntityIds = []; }
  resetForm(): void { this.form = this.freshForm(); this.haircutPercent = 2; }
  createRfq(): void { if (!this.formValid) return; this.mutate(this.api.createRfq({ ...this.form, proposedHaircutBps: this.haircutPercent == null ? null : Math.round(this.haircutPercent * 100), expiresAt: new Date(this.form.expiresAt).toISOString() }), 'RFQ sent', () => { this.showCreate = false; this.resetForm(); }); }
  cancel(rfq: RepoRfq): void { this.mutate(this.api.cancelRfq(rfq.id), 'RFQ cancelled'); }
  /** Sends the terms hash of the quote shown on screen; a newer version or changed terms answers 409. */
  accept(rfq: RepoRfq, quote: RepoQuote): void {
    this.busy = true;
    this.api.acceptQuote(rfq.id, quote.id, quote.termsHash).subscribe({
      next: () => { this.busy = false; this.snack.open('Quote accepted. Trade created.', 'Close', { duration: 3000 }); this.reload(); },
      error: e => {
        this.busy = false;
        if ((e as { status?: number } | null)?.status === 409) {
          this.snack.open(`${(e as { error?: { message?: string } }).error?.message || 'The quote changed.'} The quote book was refreshed; review the current terms before accepting.`, 'Close', { duration: 9000 });
          this.reload();
        } else { this.fail(e); }
        this.cdr.markForCheck();
      },
    });
  }
  withdraw(rfq: RepoRfq): void { this.mutate(this.api.withdrawQuote(rfq.id), 'Quote withdrawn'); }
  myQuote(rfq: RepoRfq) { return rfq.mine ? undefined : rfq.quotes[0]; }
  quoteValid(rfq: RepoRfq): boolean { return !!(this.quoteCash[rfq.id] && this.quoteRate[rfq.id] != null && this.quoteHaircut[rfq.id] != null && this.quoteValidity[rfq.id]); }
  submitQuote(rfq: RepoRfq): void { if (!this.quoteValid(rfq)) return; this.mutate(this.api.quote(rfq.id, { cashAmount: this.quoteCash[rfq.id], repoRate: this.quoteRate[rfq.id], haircutBps: Math.round((this.quoteHaircut[rfq.id] || 0) * 100), validUntil: new Date(this.quoteValidity[rfq.id]).toISOString(), message: this.quoteMessage[rfq.id] || null }), 'Private quote submitted (a new version replaces any earlier one)'); }
  tradeUpdated(trade: RepoTrade): void { this.trades = this.trades.map(t => t.id === trade.id ? trade : t); this.cdr.markForCheck(); }
  label(value: string): string { return humanize(value); }
  private mutate(request: ReturnType<RepoDeskService['cancelRfq']>, success: string, after?: () => void): void { this.busy=true; request.subscribe({next:()=>{this.busy=false;this.snack.open(success,'Close',{duration:3000});after?.();this.reload();},error:e=>{this.busy=false;this.fail(e);this.cdr.markForCheck();}}); }
  private seedDrafts(): void { for (const r of this.rfqs) { this.quoteCash[r.id] ??= r.cashAmount; this.quoteRate[r.id] ??= r.proposedRepoRate; this.quoteHaircut[r.id] ??= r.proposedHaircutBps == null ? 2 : r.proposedHaircutBps/100; this.quoteValidity[r.id] ||= this.localDateTime(new Date(Math.min(new Date(r.expiresAt).getTime(),Date.now()+3_600_000))); } }
  private freshForm(): RfqForm { const start=new Date();start.setUTCDate(start.getUTCDate()+1);const end=new Date(start);end.setUTCDate(end.getUTCDate()+7);const expires=new Date();expires.setHours(expires.getHours()+4);return {side:'BORROW_CASH',visibility:'TARGETED',collateralAssetId:'',collateralQuantity:null,cashAmount:null,cashCurrency:'EUR',startDate:start.toISOString().slice(0,10),endDate:end.toISOString().slice(0,10),proposedRepoRate:3.25,proposedHaircutBps:200,settlementMethod:'DVP',expiresAt:this.localDateTime(expires),targetEntityIds:[],notes:''}; }
  private localDateTime(date: Date): string { const offset=date.getTimezoneOffset()*60000;return new Date(date.getTime()-offset).toISOString().slice(0,16); }
  private fail(error: unknown):void {
    const response = error as { error?: { message?: string } } | null;
    this.snack.open(response?.error?.message || 'Repo Desk request failed', 'Close', {duration: 6000});
  }
}
