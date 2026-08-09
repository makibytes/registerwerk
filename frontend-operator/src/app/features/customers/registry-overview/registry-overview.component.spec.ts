import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { of, throwError } from 'rxjs';
import { RegistryOverviewComponent } from './registry-overview.component';
import { RegistryOverviewService } from '../../../core/api/registry-overview.service';
import { RegistryEntityNode, RegistryOverview } from '../../../core/models';

function entity(id: string, role: 'ISSUER' | 'INVESTOR'): RegistryEntityNode {
    return {
        id,
        entityNumber: id,
        currentName: `Entity ${id}`,
        storedType: role,
        roles: [role],
        status: 'ACTIVE',
        kycStatus: 'APPROVED',
        issuedAssetCount: role === 'ISSUER' ? 1 : 0,
        investmentCount: role === 'INVESTOR' ? 1 : 0,
        linkedInvestorCount: 1,
        linkedIssuerCount: 1,
    };
}

function overview(issuerCount: number): RegistryOverview {
    const issuers = Array.from({ length: issuerCount }, (_, index) => entity(`issuer-${index}`, 'ISSUER'));
    const investor = entity('investor-1', 'INVESTOR');
    return {
        generatedAt: '2026-08-08T00:00:00Z',
        summary: {
            entityCount: issuers.length + 1,
            issuerCount: issuers.length,
            investorCount: 1,
            dualRoleCount: 0,
            relationshipCount: issuers.length,
        },
        entities: [...issuers, investor],
        relationships: issuers.map((issuer, index) => ({
            assetId: `asset-${index}`,
            assetNumber: `AST-${index}`,
            assetName: `Asset ${index}`,
            assetStatus: 'ISSUED',
            issuerId: issuer.id,
            investorId: investor.id,
            nominalAmount: 100000,
            whitelisted: true,
        })),
    };
}

describe('RegistryOverviewComponent', () => {
    let service: MockedObject<Pick<RegistryOverviewService, 'getOverview'>>;

    beforeEach(() => {
        service = {
            getOverview: vi.fn().mockName("RegistryOverviewService.getOverview")
        };
        TestBed.configureTestingModule({
            imports: [RegistryOverviewComponent],
            providers: [{ provide: RegistryOverviewService, useValue: service }],
        });
    });

    it('expands the graph for dense lanes and keeps SVG lines on the node centres', () => {
        service.getOverview.mockReturnValue(of(overview(7)));
        const fixture = TestBed.createComponent(RegistryOverviewComponent);
        fixture.detectChanges();

        const component = fixture.componentInstance;
        expect(component.graphHeight()).toBe(784);
        expect(component.graphNodeY(0, 7)).toBe(80);
        expect(component.graphNodeY(6, 7)).toBe(704);

        const graph = fixture.nativeElement.querySelector('.graph-inner') as HTMLElement;
        const lines = fixture.nativeElement.querySelector('.graph-lines') as SVGElement;
        const firstLine = fixture.nativeElement.querySelector('.graph-lines path') as SVGPathElement;
        expect(graph.style.minHeight).toBe('784px');
        expect(lines.getAttribute('viewBox')).toBe('0 0 1000 784');
        expect(firstLine.getAttribute('d')).toContain('M 322 80');
    });

    it('keeps relationship neighbours visible when an entity is selected', () => {
        service.getOverview.mockReturnValue(of(overview(2)));
        const fixture = TestBed.createComponent(RegistryOverviewComponent);
        fixture.detectChanges();

        const component = fixture.componentInstance;
        component.selectEntity('issuer-0');

        expect(component.isUnrelated('investor-1')).toBe(false);
        expect(component.isUnrelated('issuer-1')).toBe(true);
    });

    it('keeps the selected asset panel in sync with the active search filter', () => {
        service.getOverview.mockReturnValue(of(overview(2)));
        const fixture = TestBed.createComponent(RegistryOverviewComponent);
        fixture.detectChanges();
        const component = fixture.componentInstance;

        component.selectEntity('investor-1');
        expect(component.selectedInvestments().length).toBe(2);

        component.searchText.set('Asset 0');
        expect(component.selectedInvestments().length).toBe(1);

        component.searchText.set('does not exist');
        expect(component.selectedInvestments()).toEqual([]);
    });

    it('renders an explicit empty graph state when no relationships match', () => {
        service.getOverview.mockReturnValue(of(overview(1)));
        const fixture = TestBed.createComponent(RegistryOverviewComponent);
        fixture.detectChanges();

        fixture.componentInstance.searchText.set('does not exist');
        fixture.detectChanges();

        expect(fixture.nativeElement.textContent).toContain('No relationships match the current filter');
        expect(fixture.nativeElement.querySelector('.graph-lines')).toBeNull();
    });

    it('shows a recoverable error state instead of an empty graph when loading fails', () => {
        service.getOverview.mockReturnValue(throwError(() => new Error('network unavailable')));
        const fixture = TestBed.createComponent(RegistryOverviewComponent);
        fixture.detectChanges();

        expect(fixture.componentInstance.loading()).toBe(false);
        expect(fixture.componentInstance.loadError()).toBe(true);
        expect(fixture.nativeElement.textContent).toContain('could not load the registry relationship map');
    });
});
