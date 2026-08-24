export const environment = {
  production: true,
  testEnvironment: false,
  apiUrl: '/api/v1',
  operatorUrl: '',
  bundlerUrl: '',
  // Optional build-time override of the sign-in config fetched from
  // GET /public/auth/config. Only for exercising MSAL locally without an Entra-configured
  // backend — leave unset otherwise so one image stays deployable against any tenant.
  authConfigOverride: undefined as Partial<import('../app/core/auth/auth-config').AuthConfig> | undefined
};
