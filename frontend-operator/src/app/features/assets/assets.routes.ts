import { Routes } from '@angular/router';

export const ASSET_ROUTES: Routes = [
  {
    path: '',
    loadComponent: () =>
      import('./asset-list/asset-list.component').then(
        (m) => m.AssetListComponent
      ),
  },
  {
    path: ':id',
    loadComponent: () =>
      import('./asset-detail/asset-detail.component').then(
        (m) => m.AssetDetailComponent
      ),
  },
  {
    path: ':id/edit',
    loadComponent: () =>
      import('./asset-edit/asset-edit.component').then(
        (m) => m.AssetEditComponent
      ),
  },
  {
    path: ':id/wizards/bond-issuance',
    loadComponent: () =>
      import('./wizards/bond-issuance/bond-issuance-wizard.component').then(
        (m) => m.BondIssuanceWizardComponent
      ),
    title: 'Bond Issuance Wizard',
  },
  {
    path: ':id/wizards/vault-setup',
    loadComponent: () =>
      import('./wizards/vault-setup/vault-setup-wizard.component').then(
        (m) => m.VaultSetupWizardComponent
      ),
    title: 'Vault Setup Wizard',
  },
];
