import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { of, throwError } from 'rxjs';
import { DashboardComponent } from './dashboard.component';
import { EntityService } from '../../core/api/entity.service';
import { AssetService } from '../../core/api/asset.service';
import { AuditService } from '../../core/api/audit.service';
import { AuthService } from '../../core/auth/auth.service';
import { Asset, AuditEvent, LegalEntity, PageResponse } from '../../core/models';

function page<T>(content: T[], totalElements = content.length): PageResponse<T> {
    return { content, totalElements, totalPages: 1, page: 0, size: content.length || 1 };
}

function asset(status: Asset['status']): Asset {
    return { id: status + Math.random(), status } as Asset;
}

describe('DashboardComponent', () => {
    let entityServiceSpy: MockedObject<Pick<EntityService, 'getEntities'>>;
    let assetServiceSpy: MockedObject<Pick<AssetService, 'getAssets'>>;
    let auditServiceSpy: MockedObject<Pick<AuditService, 'searchEvents'>>;
    let authServiceSpy: MockedObject<Pick<AuthService, 'hasRole'>>;

    function createComponent() {
        const fixture = TestBed.createComponent(DashboardComponent);
        return fixture;
    }

    beforeEach(() => {
        entityServiceSpy = {
            getEntities: vi.fn().mockName("EntityService.getEntities")
        };
        assetServiceSpy = {
            getAssets: vi.fn().mockName("AssetService.getAssets")
        };
        auditServiceSpy = {
            searchEvents: vi.fn().mockName("AuditService.searchEvents")
        };
        authServiceSpy = {
            hasRole: vi.fn().mockName("AuthService.hasRole")
        };
        authServiceSpy.hasRole.mockImplementation((role) => role === 'REGISTRY_ADMIN');

        TestBed.configureTestingModule({
            imports: [DashboardComponent],
            providers: [
                provideRouter([]),
                { provide: EntityService, useValue: entityServiceSpy },
                { provide: AssetService, useValue: assetServiceSpy },
                { provide: AuditService, useValue: auditServiceSpy },
                { provide: AuthService, useValue: authServiceSpy },
            ],
        });
    });

    it('starts in a loading state before data arrives', () => {
        entityServiceSpy.getEntities.mockReturnValue(of(page<LegalEntity>([])));
        assetServiceSpy.getAssets.mockReturnValue(of(page<Asset>([])));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>([])));

        const fixture = createComponent();
        expect(fixture.componentInstance.loading).toBe(true);
    });

    it('populates stats and stops loading once all four requests resolve', () => {
        entityServiceSpy.getEntities.mockReturnValueOnce(of(page<LegalEntity>([{} as LegalEntity, {} as LegalEntity], 2))).mockReturnValueOnce(of(page<LegalEntity>([{} as LegalEntity], 1)));
        assetServiceSpy.getAssets.mockReturnValue(of(page<Asset>([asset('ISSUED')], 10)));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>([])));

        const fixture = createComponent();
        fixture.detectChanges();
        const component = fixture.componentInstance;

        expect(component.loading).toBe(false);
        expect(component.stats.activeEntities).toBe(2);
        expect(component.stats.pendingKyc).toBe(1);
        expect(component.stats.totalAssets).toBe(10);
    });

    it('computes the asset status breakdown and issuedAssets count from the fetched page', () => {
        entityServiceSpy.getEntities.mockReturnValue(of(page<LegalEntity>([])));
        assetServiceSpy.getAssets.mockReturnValueOnce(of(page<Asset>([
            asset('ISSUED'), asset('ISSUED'), asset('DRAFT'), asset('SUSPENDED'),
        ]))).mockReturnValueOnce(of(page<Asset>([], 2)));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>([])));

        const fixture = createComponent();
        fixture.detectChanges();
        const component = fixture.componentInstance;

        const byStatus = Object.fromEntries(component.assetStatusBreakdown.map(e => [e.status, e.count]));
        expect(byStatus['ISSUED']).toBe(2);
        expect(byStatus['DRAFT']).toBe(1);
        expect(byStatus['SUSPENDED']).toBe(1);
        expect(component.stats.issuedAssets).toBe(2);
    });

    it('builds donut slices with known colors for known statuses and a fallback color for unknown ones', () => {
        entityServiceSpy.getEntities.mockReturnValue(of(page<LegalEntity>([])));
        assetServiceSpy.getAssets.mockReturnValue(of(page<Asset>([
            asset('ISSUED'),
            { id: 'x', status: 'REDEEMED' } as Asset,
        ])));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>([])));

        const fixture = createComponent();
        fixture.detectChanges();
        const component = fixture.componentInstance;

        const issuedSlice = component.assetDonutSlices.find(s => s.label === 'ISSUED');
        expect(issuedSlice?.color).toBe('var(--rw-issued-fg)');
        expect(issuedSlice?.value).toBe(1);

        // Labels have underscores replaced with spaces (e.g. PENDING_APPROVAL -> "PENDING APPROVAL")
        const allLabels = component.assetDonutSlices.map(s => s.label);
        expect(allLabels.every(l => !l.includes('_'))).toBe(true);
    });

    it('surfaces the most recent audit events returned by the audit service', () => {
        const events = [{ id: 'e1', eventType: 'ASSET_ISSUED' } as AuditEvent];
        entityServiceSpy.getEntities.mockReturnValue(of(page<LegalEntity>([])));
        assetServiceSpy.getAssets.mockReturnValue(of(page<Asset>([])));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>(events)));

        const fixture = createComponent();
        fixture.detectChanges();

        expect(fixture.componentInstance.recentEvents).toEqual(events);
    });

    it('stops the loading state (without throwing) if any of the underlying requests errors', () => {
        entityServiceSpy.getEntities.mockReturnValue(throwError(() => new Error('boom')));
        assetServiceSpy.getAssets.mockReturnValue(of(page<Asset>([])));
        auditServiceSpy.searchEvents.mockReturnValue(of(page<AuditEvent>([])));

        const fixture = createComponent();
        expect(() => fixture.detectChanges()).not.toThrow();
        expect(fixture.componentInstance.loading).toBe(false);
        expect(fixture.componentInstance.hasLoadFailures).toBe(true);
    });

    it('does not request registry-wide data for roles without registry overview access', () => {
        authServiceSpy.hasRole.mockReturnValue(false);

        const fixture = createComponent();
        fixture.detectChanges();

        expect(entityServiceSpy.getEntities).not.toHaveBeenCalled();
        expect(assetServiceSpy.getAssets).not.toHaveBeenCalled();
        expect(auditServiceSpy.searchEvents).not.toHaveBeenCalled();
        expect(fixture.nativeElement.textContent).toContain('Your operator workspace is ready');
    });
});
