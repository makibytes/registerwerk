import { beforeEach, describe, expect, it, type MockedObject, vi } from "vitest";
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SidebarComponent } from './sidebar.component';
import { AuthService } from '../../core/auth/auth.service';

describe('SidebarComponent', () => {
    let authServiceSpy: MockedObject<Pick<AuthService, 'hasRole'>>;

    function createComponent() {
        const fixture = TestBed.createComponent(SidebarComponent);
        fixture.detectChanges();
        return fixture;
    }

    beforeEach(() => {
        authServiceSpy = {
            hasRole: vi.fn().mockName("AuthService.hasRole")
        };

        TestBed.configureTestingModule({
            imports: [SidebarComponent],
            providers: [
                provideRouter([]),
                { provide: AuthService, useValue: authServiceSpy },
            ],
        });
    });

    it('always shows sections that declare no role restriction (e.g. Overview)', () => {
        authServiceSpy.hasRole.mockReturnValue(false);
        const fixture = createComponent();

        const labels = fixture.componentInstance.visibleSections.map(s => s.label);
        expect(labels).toContain('Overview');
    });

    it('hides role-restricted sections when the user has none of the required roles', () => {
        authServiceSpy.hasRole.mockReturnValue(false);
        const fixture = createComponent();

        const labels = fixture.componentInstance.visibleSections.map(s => s.label);
        expect(labels).not.toContain('Registry');
        expect(labels).not.toContain('Compliance');
        expect(labels).not.toContain('Ecosystem');
        expect(labels).not.toContain('Infrastructure');
    });

    it('shows a role-restricted section when the user has one of its allowed roles', () => {
        authServiceSpy.hasRole.mockImplementation((role: string) => role === 'COMPLIANCE_OFFICER');
        const fixture = createComponent();

        const labels = fixture.componentInstance.visibleSections.map(s => s.label);
        expect(labels).toContain('Compliance');
        expect(labels).not.toContain('Registry');
        expect(labels).not.toContain('Ecosystem');
    });

    it('shows every section to a REGISTRY_ADMIN', () => {
        authServiceSpy.hasRole.mockImplementation((role: string) => role === 'REGISTRY_ADMIN');
        const fixture = createComponent();

        const labels = fixture.componentInstance.visibleSections.map(s => s.label);
        expect(labels).toEqual(['Overview', 'Registry', 'Compliance', 'Ecosystem', 'Infrastructure', 'Operations']);
    });

    it('renders one nav-section-label per visible section in the template', () => {
        authServiceSpy.hasRole.mockImplementation((role: string) => role === 'REGISTRY_ADMIN');
        const fixture = createComponent();

        const renderedLabels = Array.from(fixture.nativeElement.querySelectorAll('.nav-section-label') as NodeListOf<HTMLElement>).map(el => el.textContent?.trim());

        expect(renderedLabels).toEqual(['Overview', 'Registry', 'Compliance', 'Ecosystem', 'Infrastructure', 'Operations']);
    });

    it('only exposes audit-authorized compliance and operations links to AUDIT users', () => {
        authServiceSpy.hasRole.mockImplementation((role: string) => role === 'AUDIT');
        const fixture = createComponent();

        const sections = fixture.componentInstance.visibleSections;
        expect(sections.map(section => section.label)).toEqual(['Overview', 'Registry', 'Compliance', 'Operations']);
        expect(sections.find(section => section.label === 'Compliance')?.items.map(item => item.label))
            .toEqual([
              'Chain Drift',
              'Unresolved Compensation',
              'Finality Policy',
              'Indexers',
              'Support Tickets',
              'Audit Log',
            ]);
        expect(sections.find(section => section.label === 'Registry')?.items.map(item => item.label))
            .toEqual(['Assets', 'Customers', 'Registry']);
    });

    it('exposes the configured customer-portal URL from the environment', () => {
        authServiceSpy.hasRole.mockReturnValue(false);
        const fixture = createComponent();
        expect(fixture.componentInstance.customerUrl).toBeTruthy();
    });
});
