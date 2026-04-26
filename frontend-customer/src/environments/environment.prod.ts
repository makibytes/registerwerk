export const environment = {
  production: true,
  testEnvironment: false,
  apiUrl: '/api/v1',
  msalConfig: {
    auth: {
      clientId: 'YOUR_CLIENT_ID',
      authority: 'https://login.microsoftonline.com/YOUR_TENANT_ID'
    }
  }
};
