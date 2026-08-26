export const environment = {
  production: true,
  testEnvironment: true,
  apiUrl: '/api/v1',
  customerUrl: '',
  chaincheckUrl: '',
  // See environment.ts for why this exists. Harmless for any other testnet deployment — the
  // lookup only fires when managementUrl's host is literally one of this repo's own
  // docker-compose demo service names, which a differently-hosted testnet will never have.
  chaincacheConsoleOriginOverrides: {
    'chaincache-sepolia:8080': 'http://localhost:48090',
    'chaincache-base:8080': 'http://localhost:48091'
  } as Record<string, string>
};
