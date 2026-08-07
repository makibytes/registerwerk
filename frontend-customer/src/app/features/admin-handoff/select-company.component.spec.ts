import { TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { Router } from '@angular/router';
import { MatSnackBar } from '@angular/material/snack-bar';
import { of, throwError } from 'rxjs';
import { SelectCompanyComponent } from './select-company.component';
import { AuthService } from '../../core/auth/auth.service';
import { AdminService, EntityListItem, EntityPage, ImpersonateResponse } from '../../core/api/admin.service';

describe('SelectCompanyComponent', () => {
  let authService: jasmine.SpyObj<AuthService>;
  let adminService: jasmine.SpyObj<AdminService>;
  let router: jasmine.SpyObj<Router>;
  let snackBarOpenSpy: jasmine.Spy;

  const entity: EntityListItem = {
    id: 'ent-1',
    currentName: 'Acme GmbH',
    entityNumber: 'HRB123',
    type: 'AG',
    status: 'ACTIVE',
    kycStatus: 'APPROVED',
  };

  const page: EntityPage = { content: [entity], totalElements: 1, totalPages: 1, number: 0, size: 50 };

  function createComponent() {
    const fixture = TestBed.createComponent(SelectCompanyComponent);
    fixture.detectChanges();
    return fixture;
  }

  beforeEach(() => {
    authService = jasmine.createSpyObj<AuthService>('AuthService', ['enterImpersonation', 'logout']);
    adminService = jasmine.createSpyObj<AdminService>('AdminService', ['listEntities', 'impersonate']);
    router = jasmine.createSpyObj<Router>('Router', ['navigate']);
    // Spying on the prototype (rather than providing a `useValue` spy object) matters here: this
    // component imports MatSnackBarModule directly, and the standalone component's own injector
    // scope resolves a real MatSnackBar instance regardless of a TestBed-level provider override
    // — spying on the prototype method works no matter which instance ends up injected.
    snackBarOpenSpy = spyOn(MatSnackBar.prototype, 'open').and.stub();

    adminService.listEntities.and.returnValue(of(page));

    TestBed.configureTestingModule({
      imports: [SelectCompanyComponent],
      providers: [
        provideZonelessChangeDetection(),
        { provide: AuthService, useValue: authService },
        { provide: AdminService, useValue: adminService },
        { provide: Router, useValue: router },
      ],
    });
  });

  it('loads entities on init', () => {
    const fixture = createComponent();
    const component = fixture.componentInstance;

    expect(adminService.listEntities).toHaveBeenCalledWith(undefined);
    expect(component.entities).toEqual([entity]);
    expect(component.loadingEntities).toBe(false);
  });

  it('debounces search input before reloading entities', done => {
    const fixture = createComponent();
    const component = fixture.componentInstance;
    adminService.listEntities.calls.reset();

    component.searchQuery = 'acme';
    component.onSearch();

    // Immediately after typing, no new call yet — it is debounced.
    expect(adminService.listEntities).not.toHaveBeenCalled();

    setTimeout(() => {
      expect(adminService.listEntities).toHaveBeenCalledWith('acme');
      done();
    }, 350);
  });

  describe('selectEntity()', () => {
    const impersonateResponse: ImpersonateResponse = {
      token: 'impersonation-tok',
      tokenType: 'Bearer',
      expiresAt: '2026-01-01T00:00:00Z',
      entityId: 'ent-1',
      entityName: 'Acme GmbH',
    };

    it('impersonates via AdminService then enters impersonation via AuthService, navigating on success', () => {
      const fixture = createComponent();
      const component = fixture.componentInstance;
      adminService.impersonate.and.returnValue(of(impersonateResponse));
      authService.enterImpersonation.and.returnValue(of(void 0));

      component.selectEntity(entity);

      expect(adminService.impersonate).toHaveBeenCalledWith('ent-1');
      expect(authService.enterImpersonation).toHaveBeenCalledWith(
        'impersonation-tok',
        'ent-1',
        'Acme GmbH'
      );
      expect(router.navigate).toHaveBeenCalledWith(['/dashboard']);
    });

    it('shows an error and resets selecting state when AuthService.enterImpersonation() fails', () => {
      const fixture = createComponent();
      const component = fixture.componentInstance;
      adminService.impersonate.and.returnValue(of(impersonateResponse));
      authService.enterImpersonation.and.returnValue(
        throwError(() => ({ error: { message: 'Impersonation session exchange failed' } }))
      );

      component.selectEntity(entity);

      expect(component.selecting).toBeNull();
      expect(router.navigate).not.toHaveBeenCalled();
      expect(snackBarOpenSpy).toHaveBeenCalledWith(
        'Impersonation session exchange failed',
        'Dismiss',
        { duration: 6000 }
      );
    });

    it('shows an error when AdminService.impersonate() itself fails', () => {
      const fixture = createComponent();
      const component = fixture.componentInstance;
      adminService.impersonate.and.returnValue(throwError(() => ({ error: { message: 'Forbidden' } })));

      component.selectEntity(entity);

      expect(authService.enterImpersonation).not.toHaveBeenCalled();
      expect(component.selecting).toBeNull();
      expect(snackBarOpenSpy).toHaveBeenCalledWith('Forbidden', 'Dismiss', { duration: 6000 });
    });

    it('ignores a second click while a selection is already in progress', () => {
      const fixture = createComponent();
      const component = fixture.componentInstance;
      adminService.impersonate.and.returnValue(of(impersonateResponse));
      authService.enterImpersonation.and.returnValue(of(void 0));

      component.selecting = 'ent-1';
      component.selectEntity(entity);

      expect(adminService.impersonate).not.toHaveBeenCalled();
    });
  });

  it('logout() delegates to AuthService', () => {
    const fixture = createComponent();
    fixture.componentInstance.logout();
    expect(authService.logout).toHaveBeenCalled();
  });
});
