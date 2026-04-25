export const environment = {
  production: false,
  apiUrl: 'http://localhost:8000/api/v1',
  entraEnabled: false,
  msalConfig: {
    auth: {
      clientId: 'YOUR_CLIENT_ID',
      authority: 'https://login.microsoftonline.com/YOUR_TENANT_ID'
    }
  }
};
