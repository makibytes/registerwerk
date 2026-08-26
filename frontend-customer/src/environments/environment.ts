export const environment = {
  production: false,
  testEnvironment: true,
  apiUrl: 'http://localhost:48000/api/v1',
  operatorUrl: 'http://localhost:44200',
  // ERC-4337 bundler endpoint for SponsoredTxService — unset by default since no bundler
  // ships in docker-compose; point at a bundler provider (Pimlico/Stackup/Alchemy) or a
  // self-hosted one to enable sponsored transactions. See core/wallet/sponsored-tx.service.ts.
  bundlerUrl: '',
  // Optional build-time override of the sign-in config fetched from
  // GET /public/auth/config. Only for exercising MSAL locally without an Entra-configured
  // backend — leave unset otherwise so one image stays deployable against any tenant.
  authConfigOverride: undefined as Partial<import('../app/core/auth/auth-config').AuthConfig> | undefined
};
