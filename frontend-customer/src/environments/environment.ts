export const environment = {
  production: false,
  testEnvironment: true,
  apiUrl: 'http://localhost:8000/api/v1',
  operatorUrl: 'http://localhost:4200',
  // Repo/lending is on for local development so the feature is exercisable; the prod and
  // testnet environment files keep it off until an approved release record exists. The
  // backend gate (registerwerk.lending.*) is independent — both sides must be enabled.
  lendingEnabled: true,
  // ERC-4337 bundler endpoint for SponsoredTxService — unset by default since no bundler
  // ships in docker-compose; point at a bundler provider (Pimlico/Stackup/Alchemy) or a
  // self-hosted one to enable sponsored transactions. See core/wallet/sponsored-tx.service.ts.
  bundlerUrl: ''
};
