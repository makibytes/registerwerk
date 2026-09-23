import { beforeEach, describe, expect, it, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { MatDialogRef } from '@angular/material/dialog';
import { of } from 'rxjs';
import { RegisterInvestorDialogComponent } from './register-investor-dialog.component';
import { EntityService } from '../../../core/api/entity.service';
import { LegalEntity } from '../../../core/models';
import { countryByAlpha2 } from '../../../shared/iso3166';

const ENTITY_ID = '11111111-2222-3333-4444-555555555555';

describe('RegisterInvestorDialogComponent', () => {
    let close: ReturnType<typeof vi.fn>;
    let getEntity: ReturnType<typeof vi.fn>;

    beforeEach(() => {
        close = vi.fn();
        getEntity = vi.fn().mockReturnValue(of({ id: ENTITY_ID, registrationCountry: 'DE' } as LegalEntity));
        TestBed.configureTestingModule({
            imports: [RegisterInvestorDialogComponent],
            providers: [
                { provide: MatDialogRef, useValue: { close } },
                { provide: EntityService, useValue: { getEntity } },
            ],
        });
    });

    function fill(component: RegisterInvestorDialogComponent) {
        component.form.patchValue({
            walletAddress: '0x1234567890123456789012345678901234567890',
            chainConfigId: 'cfg',
        });
    }

    it('requires a country picked from the list — free text is not enough', () => {
        const component = TestBed.createComponent(RegisterInvestorDialogComponent).componentInstance;
        fill(component);
        component.form.patchValue({ legalEntityId: 'not-a-uuid', country: 'Germany' });
        expect(component.form.invalid).toBe(true);
        component.submit();
        expect(close).not.toHaveBeenCalled();
    });

    it('prefills the country from the entity KYC record and submits its numeric code', () => {
        const component = TestBed.createComponent(RegisterInvestorDialogComponent).componentInstance;
        fill(component);
        component.form.patchValue({ legalEntityId: ENTITY_ID });

        expect(getEntity).toHaveBeenCalledWith(ENTITY_ID);
        expect(component.form.controls.country.value).toEqual(countryByAlpha2('DE'));
        component.submit();
        expect(close).toHaveBeenCalledWith(expect.objectContaining({ legalEntityId: ENTITY_ID, countryCode: 276 }));
    });
});
