import { describe, expect, it } from 'vitest';
import { routes } from '../../app.routes';

describe('approvals route', () => {
  it('is reserved for the roles that can act as second approvers', () => {
    const shell = routes.find(r => r.children)!;
    const route = shell.children!.find(r => r.path === 'approvals');
    expect(route).toBeDefined();
    expect(route!.canActivate?.length).toBe(1);
    expect(route!.data).toEqual({ roles: ['REGISTRY_ADMIN', 'COMPLIANCE_OFFICER'] });
  });
});
