import { loadConfig } from '../src/config';

const OPERATOR_KEY = `0x${'1'.repeat(64)}`;
const API_KEY = 'test-relayer-api-key';
/** Every scenario below needs a valid RELAYER_API_KEY present so it can exercise what it's
 *  actually testing rather than failing on the (also required, finding #6) API key check first. */
const withApiKey = (env: Record<string, string | undefined> = {}) => ({ RELAYER_API_KEY: API_KEY, ...env });

describe('loadConfig', () => {
  it('defaults to the sepolia preset with sensible defaults', () => {
    const config = loadConfig(withApiKey());
    expect(config.preset).toBe('sepolia');
    expect(config.port).toBe(3001);
    expect(config.chainId).toBe(11155111);
    expect(config.operatorDecryptDurationDays).toBe(365);
    expect(config.operatorDecryptPrivateKey).toBeUndefined();
  });

  it('rejects an unknown ZAMA_CONFIG_PRESET value', () => {
    expect(() => loadConfig(withApiKey({ ZAMA_CONFIG_PRESET: 'mainnet-legacy' })))
      .toThrow(/must be "sepolia" or "custom"/);
  });

  it('requires the full custom.* block when preset=custom', () => {
    expect(() => loadConfig(withApiKey({ ZAMA_CONFIG_PRESET: 'custom' }))).toThrow(/NETWORK_RPC_URL/);
  });

  it('builds a complete custom config when all env vars are present', () => {
    const env = withApiKey({
      ZAMA_CONFIG_PRESET: 'custom',
      CHAIN_ID: '1',
      NETWORK_RPC_URL: 'https://mainnet.example.com',
      RELAYER_URL: 'https://relayer.example.com',
      GATEWAY_CHAIN_ID: '10901',
      ACL_CONTRACT_ADDRESS: '0xacl',
      KMS_CONTRACT_ADDRESS: '0xkms',
      INPUT_VERIFIER_CONTRACT_ADDRESS: '0xiv',
      VERIFYING_CONTRACT_ADDRESS_DECRYPTION: '0xvcd',
      VERIFYING_CONTRACT_ADDRESS_INPUT_VERIFICATION: '0xvciv',
    });
    const config = loadConfig(env);
    expect(config.custom).toEqual({
      networkUrl: 'https://mainnet.example.com',
      relayerUrl: 'https://relayer.example.com',
      chainId: 1,
      gatewayChainId: 10901,
      aclContractAddress: '0xacl',
      kmsContractAddress: '0xkms',
      inputVerifierContractAddress: '0xiv',
      verifyingContractAddressDecryption: '0xvcd',
      verifyingContractAddressInputVerification: '0xvciv',
    });
  });

  it('parses a valid operator decrypt private key', () => {
    const config = loadConfig(withApiKey({ OPERATOR_DECRYPT_PRIVATE_KEY: OPERATOR_KEY }));
    expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
  });

  it('rejects a malformed operator decrypt private key', () => {
    expect(() => loadConfig(withApiKey({ OPERATOR_DECRYPT_PRIVATE_KEY: '0xnothex' }))).toThrow(
      /32-byte hex private key/
    );
  });

  it('treats a blank operator decrypt private key as unset', () => {
    const config = loadConfig(withApiKey({ OPERATOR_DECRYPT_PRIVATE_KEY: '  ' }));
    expect(config.operatorDecryptPrivateKey).toBeUndefined();
  });

  // ── finding #6, Phase 9: RELAYER_API_KEY is required ─────────────────────────────────────
  it('requires RELAYER_API_KEY to be set', () => {
    expect(() => loadConfig({})).toThrow(/RELAYER_API_KEY/);
  });

  it('parses RELAYER_API_KEY onto config.apiKey', () => {
    const config = loadConfig({ RELAYER_API_KEY: API_KEY });
    expect(config.apiKey).toBe(API_KEY);
  });
});
