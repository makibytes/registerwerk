import { inject } from '@angular/core';
import { CanMatchFn, Router } from '@angular/router';
import { PlatformCapabilitiesService } from './platform-capabilities';

export const repoDeskFeatureGuard: CanMatchFn = () =>
  inject(PlatformCapabilitiesService).repoDeskEnabled()
    ? true
    : inject(Router).createUrlTree(['/dashboard']);

