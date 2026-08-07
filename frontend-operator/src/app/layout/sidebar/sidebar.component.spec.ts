import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { SidebarComponent } from './sidebar.component';
import { AuthService } from '../../core/auth/auth.service';

describe('SidebarComponent', () => {
  let authServiceSpy: jasmine.SpyObj<AuthService>;

  function createComponent() {
    const fixture = TestBed.createComponent(SidebarComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    authServiceSpy = jasmine.createSpyObj('AuthService', ['hasRole']);

    TestBed.configureTestingModule({
      imports: [SidebarComponent],
      providers: [
        provideRouter([]),
        { provide: AuthService, useValue: authServiceSpy },
      ],
    });
  });

  it('always shows sections that declare no role restriction (e.g. Overview)', () => {
    authServiceSpy.hasRole.and.returnValue(false);
    const fixture = createComponent();

    const labels = fixture.componentInstance.visibleSections.map(s => s.label);
    expect(labels).toContain('Overview');
  });

  it('hides role-restricted sections when the user has none of the required roles', () => {
    authServiceSpy.hasRole.and.returnValue(false);
    const fixture = createComponent();

    const labels = fixture.componentInstance.visibleSections.map(s => s.label);
    expect(labels).not.toContain('Registry');
    expect(labels).not.toContain('Compliance');
    expect(labels).not.toContain('Ecosystem');
    expect(labels).not.toContain('Infrastructure');
  });

  it('shows a role-restricted section when the user has one of its allowed roles', () => {
    authServiceSpy.hasRole.and.callFake((role: string) => role === 'COMPLIANCE_OFFICER');
    const fixture = createComponent();

    const labels = fixture.componentInstance.visibleSections.map(s => s.label);
    expect(labels).toContain('Compliance');
    expect(labels).not.toContain('Registry');
    expect(labels).not.toContain('Ecosystem');
  });

  it('shows every section to a REGISTRY_ADMIN', () => {
    authServiceSpy.hasRole.and.callFake((role: string) => role === 'REGISTRY_ADMIN');
    const fixture = createComponent();

    const labels = fixture.componentInstance.visibleSections.map(s => s.label);
    expect(labels).toEqual(['Overview', 'Registry', 'Compliance', 'Ecosystem', 'Infrastructure']);
  });

  it('renders one nav-section-label per visible section in the template', () => {
    authServiceSpy.hasRole.and.callFake((role: string) => role === 'REGISTRY_ADMIN');
    const fixture = createComponent();

    const renderedLabels = Array.from(
      fixture.nativeElement.querySelectorAll('.nav-section-label') as NodeListOf<HTMLElement>
    ).map(el => el.textContent?.trim());

    expect(renderedLabels).toEqual(['Overview', 'Registry', 'Compliance', 'Ecosystem', 'Infrastructure']);
  });

  it('exposes the configured customer-portal URL from the environment', () => {
    authServiceSpy.hasRole.and.returnValue(false);
    const fixture = createComponent();
    expect(fixture.componentInstance.customerUrl).toBeTruthy();
  });
});
