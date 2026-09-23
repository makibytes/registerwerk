import { ChangeDetectorRef, Component, DestroyRef, inject } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { AbstractControl, ReactiveFormsModule, FormBuilder, ValidationErrors, Validators } from '@angular/forms';
import { MatAutocompleteModule } from '@angular/material/autocomplete';
import { MatButtonModule } from '@angular/material/button';
import { MatDialogModule, MatDialogRef, MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatTooltipModule } from '@angular/material/tooltip';
import { AddressPickerDialogComponent, AddressPickerDialogData } from '../../../shared/components/address-picker-dialog.component';
import { EntityService } from '../../../core/api/entity.service';
import { ISO_COUNTRIES, IsoCountry, countryByAlpha2 } from '../../../shared/iso3166';

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The country field holds typed search text until an option is picked; only a picked country is valid. */
function isoCountrySelected(control: AbstractControl): ValidationErrors | null {
  const v = control.value;
  return v && typeof v === 'object' && typeof v.numeric === 'number' ? null : { countryRequired: true };
}

export interface RegisterInvestorData {
  walletAddress: string;
  legalEntityId: string;
  chainConfigId: string;
  countryCode: number;
}

@Component({
  selector: 'app-register-investor-dialog',
  standalone: true,
  imports: [ReactiveFormsModule, MatAutocompleteModule, MatButtonModule, MatDialogModule, MatFormFieldModule, MatIconModule, MatInputModule, MatTooltipModule],
  template: `
    <h2 mat-dialog-title>Register Investor</h2>
    <mat-dialog-content>
      <form [formGroup]="form" style="display:flex;flex-direction:column;gap:12px;min-width:380px;padding-top:8px">
        <mat-form-field appearance="outline">
          <mat-label>Wallet Address</mat-label>
          <input matInput formControlName="walletAddress" placeholder="0x..." />
          <button matSuffix mat-icon-button type="button" matTooltip="Pick from address book"
                  (click)="pickWalletAddress()">
            <mat-icon style="font-size:18px">contacts</mat-icon>
          </button>
          <mat-error>Required, must start with 0x</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Legal Entity UUID</mat-label>
          <input matInput formControlName="legalEntityId" placeholder="xxxxxxxx-xxxx-..." />
          <mat-error>Required UUID</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Chain Config UUID</mat-label>
          <input matInput formControlName="chainConfigId" placeholder="xxxxxxxx-xxxx-..." />
          <mat-error>Required UUID</mat-error>
        </mat-form-field>
        <mat-form-field appearance="outline">
          <mat-label>Country</mat-label>
          <input matInput formControlName="country" [matAutocomplete]="countryAuto"
                 placeholder="Search by name or code" data-testid="register-investor-country" />
          <mat-autocomplete #countryAuto="matAutocomplete" [displayWith]="displayCountry">
            @for (c of filteredCountries; track c.alpha2) {
              <mat-option [value]="c">{{ c.name }} ({{ c.alpha2 }} · {{ c.numeric }})</mat-option>
            }
          </mat-autocomplete>
          @if (countryPrefilled) {
            <mat-hint>Prefilled from the entity's KYC registration country</mat-hint>
          }
          <mat-error>Required — pick a country from the list. It is registered on-chain and enforced
            by country restrictions.</mat-error>
        </mat-form-field>
      </form>
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button type="button" mat-button mat-dialog-close>Cancel</button>
      <button type="button" mat-raised-button color="primary" [disabled]="form.invalid" (click)="submit()">Register</button>
    </mat-dialog-actions>
  `,
})
export class RegisterInvestorDialogComponent {
  private readonly dialogRef = inject(MatDialogRef<RegisterInvestorDialogComponent>);
  private readonly dialog = inject(MatDialog);
  private readonly cdr = inject(ChangeDetectorRef);
  private readonly fb = inject(FormBuilder);
  private readonly entityService = inject(EntityService);
  private readonly destroyRef = inject(DestroyRef);

  form = this.fb.group({
    walletAddress: ['', [Validators.required, Validators.pattern(/^0x[0-9a-fA-F]{40}$/)]],
    legalEntityId: ['', Validators.required],
    chainConfigId: ['', Validators.required],
    country: [null as IsoCountry | string | null, isoCountrySelected],
  });

  filteredCountries: readonly IsoCountry[] = ISO_COUNTRIES;
  countryPrefilled = false;

  constructor() {
    this.form.controls.country.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(v => {
      const q = typeof v === 'string' ? v.trim().toLowerCase() : '';
      this.filteredCountries = q
        ? ISO_COUNTRIES.filter(c => c.name.toLowerCase().includes(q)
            || c.alpha2.toLowerCase() === q || String(c.numeric) === q)
        : ISO_COUNTRIES;
      if (typeof v === 'string') this.countryPrefilled = false;
      this.cdr.markForCheck();
    });
    // Prefill the country from KYC once a legal entity UUID is entered, unless one was picked already.
    this.form.controls.legalEntityId.valueChanges.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(id => {
      if (!id || !UUID_PATTERN.test(id.trim()) || this.form.controls.country.valid) return;
      this.entityService.getEntity(id.trim()).subscribe({
        next: entity => {
          const kycCountry = countryByAlpha2(entity.registrationCountry);
          if (kycCountry && !this.form.controls.country.valid) {
            this.form.controls.country.setValue(kycCountry);
            this.countryPrefilled = true;
          }
          this.cdr.markForCheck();
        },
        error: () => { /* unknown entity: the operator picks the country manually */ },
      });
    });
  }

  displayCountry(c: IsoCountry | string | null): string {
    return c && typeof c === 'object' ? `${c.name} (${c.numeric})` : (c ?? '');
  }

  pickWalletAddress(): void {
    this.dialog.open<AddressPickerDialogComponent, AddressPickerDialogData, string>(
      AddressPickerDialogComponent,
      { data: { mode: 'WALLET', title: 'Select investor wallet' }, width: '560px' }
    ).afterClosed().subscribe(addr => {
      if (addr) {
        this.form.patchValue({ walletAddress: addr });
        this.cdr.markForCheck(); // zoneless: keep view state consistent per project convention
      }
    });
  }

  submit(): void {
    if (this.form.invalid) return;
    const v = this.form.getRawValue();
    const result: RegisterInvestorData = {
      walletAddress: v.walletAddress!,
      legalEntityId: v.legalEntityId!,
      chainConfigId: v.chainConfigId!,
      countryCode: (v.country as IsoCountry).numeric,
    };
    this.dialogRef.close(result);
  }
}
