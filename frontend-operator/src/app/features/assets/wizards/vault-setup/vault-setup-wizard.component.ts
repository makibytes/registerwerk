import {
  ChangeDetectionStrategy, ChangeDetectorRef, Component, inject
} from '@angular/core';
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatInputModule } from '@angular/material/input';
import { MatSelectModule } from '@angular/material/select';
import { MatIconModule } from '@angular/material/icon';
import { MatStepperModule } from '@angular/material/stepper';
import { MatSnackBar } from '@angular/material/snack-bar';
import { AssetService } from '../../../../core/api/asset.service';
import { TokenStandard } from '../../../../core/models';

@Component({
  selector: 'app-vault-setup-wizard',
  standalone: true,
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [
    ReactiveFormsModule, MatButtonModule, MatFormFieldModule,
    MatInputModule, MatSelectModule, MatIconModule, MatStepperModule,
  ],
  template: `
    <div class="vault-wizard">
      <header class="vault-header">
        <span class="badge">TOKENIZED VAULT</span>
        <h1 class="vault-title">New Vault</h1>
        <p class="vault-sub">Configure a tokenized fund or bond vault</p>
      </header>

      <mat-stepper linear class="vault-stepper">

        <!-- Step 1: Vault type -->
        <mat-step label="Type">
          <div class="step-body">
            <h2 class="step-h">Select vault standard</h2>
            <div class="type-cards">
              <div
                class="type-card"
                role="button"
                tabindex="0"
                [class.selected]="vaultType === 'ERC4626'"
                (click)="vaultType = 'ERC4626'"
                (keydown.enter)="vaultType = 'ERC4626'"
                (keydown.space)="vaultType = 'ERC4626'; $event.preventDefault()"
              >
                <div class="type-icon">◈</div>
                <span class="type-name">ERC-4626</span>
                <span class="type-desc">Synchronous vault — deposits and redemptions settle instantly at current NAV</span>
                <ul class="type-features">
                  <li>Immediate settlement</li>
                  <li>Operator-controlled NAV</li>
                  <li>eWpG regulatory controls</li>
                </ul>
              </div>
              <div
                class="type-card async"
                role="button"
                tabindex="0"
                [class.selected]="vaultType === 'ERC7540'"
                (click)="vaultType = 'ERC7540'"
                (keydown.enter)="vaultType = 'ERC7540'"
                (keydown.space)="vaultType = 'ERC7540'; $event.preventDefault()"
              >
                <div class="type-icon">◇</div>
                <span class="type-name">ERC-7540 <span class="async-badge">ASYNC</span></span>
                <span class="type-desc">Request-based vault — investors submit requests; operator fulfills after NAV strike</span>
                <ul class="type-features">
                  <li>T+1 / NAV cutoff settlement</li>
                  <li>Operator approval queue</li>
                  <li>Institutional-grade</li>
                </ul>
              </div>
            </div>
            <div class="step-actions">
              <button type="button" mat-flat-button class="btn-primary" [disabled]="!vaultType" matStepperNext>
                Next <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 2: Parameters -->
        <mat-step label="Parameters">
          <div class="step-body">
            <h2 class="step-h">Vault parameters</h2>
            <form [formGroup]="form" class="form-grid">
              <mat-form-field appearance="outline" class="field-full">
                <mat-label>Vault name</mat-label>
                <input matInput formControlName="vaultName" placeholder="Registerwerk Bond Fund A">
              </mat-form-field>

              <mat-form-field appearance="outline" class="field-full">
                <mat-label>Underlying asset ID (UUID of the ERC-20 asset to deposit)</mat-label>
                <input matInput formControlName="underlyingAssetId" placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Deposit cap (0 = unlimited)</mat-label>
                <input matInput type="number" formControlName="depositCap" placeholder="0">
              </mat-form-field>

              <mat-form-field appearance="outline">
                <mat-label>Chain</mat-label>
                <mat-select formControlName="chain">
                  <mat-option value="ETHEREUM">Ethereum</mat-option>
                  <mat-option value="POLYGON">Polygon</mat-option>
                  <mat-option value="BASE">Base</mat-option>
                  <mat-option value="ARBITRUM">Arbitrum</mat-option>
                </mat-select>
              </mat-form-field>

              @if (vaultType === 'ERC7540') {
                <mat-form-field appearance="outline" class="field-full">
                  <mat-label>Minimum settlement delay (seconds, e.g. 86400 = 1 day)</mat-label>
                  <input matInput type="number" formControlName="minSettlementDelay" placeholder="86400">
                </mat-form-field>
              }
            </form>
            <div class="step-actions">
              <button type="button" mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button type="button" mat-flat-button class="btn-primary" [disabled]="form.invalid" matStepperNext>
                Review <mat-icon>arrow_forward</mat-icon>
              </button>
            </div>
          </div>
        </mat-step>

        <!-- Step 3: Deploy -->
        <mat-step label="Deploy">
          <div class="step-body">
            <h2 class="step-h">Review &amp; deploy</h2>
            <div class="review-card">
              <div class="rv-row"><span>Standard</span><span class="mono">{{ vaultType }}</span></div>
              <div class="rv-row"><span>Name</span><span>{{ form.get('vaultName')?.value }}</span></div>
              <div class="rv-row"><span>Chain</span><span class="mono">{{ form.get('chain')?.value }}</span></div>
              @if (vaultType === 'ERC7540') {
                <div class="rv-row">
                  <span>Settlement delay</span>
                  <span class="mono">{{ form.get('minSettlementDelay')?.value }}s</span>
                </div>
              }
            </div>
            <div class="step-actions">
              <button type="button" mat-stroked-button matStepperPrevious class="btn-back">Back</button>
              <button type="button" mat-flat-button class="btn-deploy" [disabled]="deploying" (click)="deploy()">
                @if (deploying) {
                  <span class="spinner"></span> Deploying…
                } @else {
                  <ng-container><mat-icon>rocket_launch</mat-icon> Deploy vault</ng-container>
                }
              </button>
            </div>
          </div>
        </mat-step>

      </mat-stepper>
    </div>
  `,
  styles: [`
    :host {
      display: block;
      --accent: var(--rw-accent, #F59E0B);
      --surface: #0e1124;
      --border: rgba(245,158,11,.18);
    }

    .vault-wizard { max-width: 720px; margin: 0 auto; padding: 2rem 1.5rem; }

    .vault-header { margin-bottom: 2rem; }

    .badge {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .625rem;
      letter-spacing: .2em;
      color: var(--accent);
      background: rgba(245,158,11,.1);
      border: 1px solid var(--border);
      border-radius: 2px;
      padding: .2rem .625rem;
    }

    .vault-title {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 2rem;
      font-weight: 800;
      color: #f0f4ff;
      margin: .75rem 0 .25rem;
    }

    .vault-sub { color: #7b8aac; font-size: .875rem; margin: 0; }

    .vault-stepper { background: transparent; }

    .step-body { padding: 1.5rem 0; }

    .step-h {
      font-family: 'Manrope Variable', sans-serif;
      font-size: 1.125rem;
      font-weight: 700;
      color: #e2e8f8;
      margin: 0 0 1.25rem;
    }

    .type-cards {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 1rem;
      margin-bottom: 1.5rem;
    }

    .type-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.5rem;
      cursor: pointer;
      transition: border-color .15s;
      display: flex;
      flex-direction: column;
      gap: .5rem;
    }

    .type-card:hover { border-color: rgba(245,158,11,.35); }
    .type-card.selected { border-color: var(--accent); background: rgba(245,158,11,.05); }

    .type-icon {
      font-size: 1.75rem;
      color: var(--accent);
      line-height: 1;
      margin-bottom: .25rem;
    }

    .type-name {
      font-family: 'IBM Plex Mono', monospace;
      font-size: .875rem;
      color: #e2e8f8;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .async-badge {
      font-size: .5625rem;
      padding: .1rem .35rem;
      background: rgba(251,191,36,.15);
      color: #fbbf24;
      border-radius: 2px;
      letter-spacing: .08em;
    }

    .type-desc {
      font-size: .8125rem;
      color: #7b8aac;
      line-height: 1.5;
    }

    .type-features {
      margin: .5rem 0 0;
      padding-left: 1rem;
      font-size: .75rem;
      color: #4ade80;
      line-height: 1.8;
    }

    .form-grid {
      display: grid;
      grid-template-columns: 1fr 1fr;
      gap: 0 1rem;
    }

    .field-full { grid-column: 1 / -1; }

    .review-card {
      background: var(--surface);
      border: 1px solid var(--border);
      border-radius: 8px;
      padding: 1.25rem;
      margin-bottom: 1.5rem;
    }

    .rv-row {
      display: flex;
      justify-content: space-between;
      padding: .5rem 0;
      border-bottom: 1px solid rgba(255,255,255,.04);
      font-size: .875rem;
    }

    .rv-row:last-child { border-bottom: none; }
    .rv-row > :first-child { color: #7b8aac; }
    .rv-row > :last-child { color: #e2e8f8; }

    .mono { font-family: 'IBM Plex Mono', monospace; }

    .step-actions {
      display: flex;
      justify-content: flex-end;
      gap: .75rem;
      margin-top: 1.5rem;
    }

    .btn-primary {
      background: var(--accent) !important;
      color: #07091A !important;
      font-weight: 700;
    }

    .btn-primary:disabled { opacity: .5; }

    .btn-back { color: #a0aec0; border-color: var(--border); }

    .btn-deploy {
      background: #dc2626 !important;
      color: #fff !important;
      font-weight: 700;
      display: flex;
      align-items: center;
      gap: .5rem;
    }

    .btn-deploy:disabled { opacity: .5; }

    .spinner {
      width: 1rem; height: 1rem;
      border: 2px solid rgba(255,255,255,.3);
      border-top-color: #fff;
      border-radius: 50%;
      animation: spin .6s linear infinite;
      display: inline-block;
    }

    @keyframes spin { to { transform: rotate(360deg); } }

    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__leading,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__notch,
    ::ng-deep .mat-mdc-form-field .mdc-notched-outline__trailing { border-color: var(--border) !important; }
    ::ng-deep .mat-mdc-form-field input,
    ::ng-deep .mat-mdc-form-field .mat-mdc-select-value-text { color: #e2e8f8 !important; }
    ::ng-deep .mat-mdc-form-field .mat-mdc-floating-label { color: #7b8aac !important; }
    ::ng-deep .mat-mdc-form-field.mat-focused .mat-mdc-floating-label { color: var(--accent) !important; }
    ::ng-deep .mat-step-header .mat-step-icon-selected { background-color: var(--accent) !important; }
    ::ng-deep .mat-step-header .mat-step-icon { background-color: #1a1f3c; }
  `]
})
export class VaultSetupWizardComponent {
  private readonly assetService = inject(AssetService);
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly snackBar = inject(MatSnackBar);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);

  vaultType: TokenStandard | null = null;
  deploying = false;

  readonly form: FormGroup = this.fb.group({
    vaultName: ['', Validators.required],
    underlyingAssetId: ['', Validators.required],
    depositCap: [0],
    chain: ['ETHEREUM', Validators.required],
    minSettlementDelay: [86400],
  });

  deploy(): void {
    const assetId = this.route.snapshot.paramMap.get('id') ?? '';
    this.deploying = true;
    this.cdr.markForCheck();

    this.assetService.deployAsset(assetId, {
      chain: this.form.value.chain,
      network: 'MAINNET',
      tokenStandard: this.vaultType!,
    }).subscribe({
      next: () => {
        this.snackBar.open('Vault deployment initiated', 'Dismiss', { duration: 4000 });
        this.router.navigate(['..'], { relativeTo: this.route });
      },
      error: (err) => {
        this.snackBar.open(err?.error?.message ?? 'Deployment failed', 'Dismiss', { duration: 6000 });
        this.deploying = false;
        this.cdr.markForCheck();
      },
    });
  }
}
