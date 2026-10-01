import { describe, expect, it, vi } from 'vitest';
import { loadConfig } from '../src/config.js';
import { EnvVarKekProvider } from '../src/kekProvider.js';
import { toHex } from '../src/hex.js';

const OPERATOR_KEY = `0x${'1'.repeat(64)}`;
const API_KEY = 'test-relayer-api-key';
/** Every scenario below needs a valid RELAYER_API_KEY present so it can exercise what it's
 *  actually testing rather than failing on the (also required, ) API key check first. */
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

  // ── Envelope-encrypted operator-decrypt key ───────────────────────────
  describe('OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED', () => {
    const MASTER_KEY = 'test-kek-master-key';

    it('unwraps and resolves the operator decrypt private key', () => {
      const wrapped = toHex(new EnvVarKekProvider(MASTER_KEY).wrap(Buffer.from(OPERATOR_KEY.slice(2), 'hex')));

      const config = loadConfig(withApiKey({
        OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED: wrapped,
        RELAYER_KEK_MASTER_KEY: MASTER_KEY,
      }));

      expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
    });

    it('takes priority over a plaintext OPERATOR_DECRYPT_PRIVATE_KEY set at the same time', () => {
      const wrapped = toHex(new EnvVarKekProvider(MASTER_KEY).wrap(Buffer.from(OPERATOR_KEY.slice(2), 'hex')));
      const otherPlaintextKey = `0x${'2'.repeat(64)}`;

      const config = loadConfig(withApiKey({
        OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED: wrapped,
        RELAYER_KEK_MASTER_KEY: MASTER_KEY,
        OPERATOR_DECRYPT_PRIVATE_KEY: otherPlaintextKey,
      }));

      expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
    });

    it('requires RELAYER_KEK_MASTER_KEY when the wrapped key is set', () => {
      const wrapped = toHex(new EnvVarKekProvider(MASTER_KEY).wrap(Buffer.from(OPERATOR_KEY.slice(2), 'hex')));

      expect(() => loadConfig(withApiKey({ OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED: wrapped })))
        .toThrow(/RELAYER_KEK_MASTER_KEY/);
    });

    it('fails loudly when unwrapped with the wrong master key', () => {
      const wrapped = toHex(new EnvVarKekProvider(MASTER_KEY).wrap(Buffer.from(OPERATOR_KEY.slice(2), 'hex')));

      expect(() => loadConfig(withApiKey({
        OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED: wrapped,
        RELAYER_KEK_MASTER_KEY: 'wrong-master-key',
      }))).toThrow(/Failed to unwrap/);
    });

    it('warns when falling back to the legacy plaintext key', () => {
      const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

      const config = loadConfig(withApiKey({ OPERATOR_DECRYPT_PRIVATE_KEY: OPERATOR_KEY }));

      expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
      expect(warnSpy).toHaveBeenCalledWith(expect.stringMatching(/plaintext/));
      warnSpy.mockRestore();
    });
  });

  // ── RELAYER_API_KEY is required ───────────────────────────────────────
  it('requires RELAYER_API_KEY to be set', () => {
    expect(() => loadConfig({})).toThrow(/RELAYER_API_KEY/);
  });

  it('parses RELAYER_API_KEY onto config.apiKey', () => {
    const config = loadConfig({ RELAYER_API_KEY: API_KEY });
    expect(config.apiKey).toBe(API_KEY);
  });

  describe('RELAYER_PRODUCTION_MODE', () => {
    const wrappedEnv = () => {
      const wrapped = toHex(new EnvVarKekProvider('master').wrap(Buffer.from(OPERATOR_KEY.slice(2), 'hex')));
      return { OPERATOR_DECRYPT_PRIVATE_KEY_WRAPPED: wrapped, RELAYER_KEK_MASTER_KEY: 'master' };
    };
    const prod = (extra: Record<string, string | undefined> = {}) =>
      withApiKey({ RELAYER_PRODUCTION_MODE: 'true', OPERATOR_DECRYPT_DURATION_DAYS: '30', ...extra });

    it('refuses the ENV_VAR KEK provider unless explicitly allowed', () => {
      expect(() => loadConfig(prod(wrappedEnv()))).toThrow(/refuses the ENV_VAR KEK provider/);
    });

    it('refuses the plaintext operator key unless explicitly allowed', () => {
      expect(() => loadConfig(prod({ OPERATOR_DECRYPT_PRIVATE_KEY: OPERATOR_KEY })))
        .toThrow(/plaintext/);
    });

    it('accepts the env KEK with RELAYER_ALLOW_ENV_KEK=true and logs a warning', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const config = loadConfig(prod({ ...wrappedEnv(), RELAYER_ALLOW_ENV_KEK: 'true' }));
      expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
      expect(warn).toHaveBeenCalledWith(expect.stringContaining('RELAYER_ALLOW_ENV_KEK'));
      warn.mockRestore();
    });

    it('requires an explicit OPERATOR_DECRYPT_DURATION_DAYS', () => {
      expect(() => loadConfig(withApiKey({ RELAYER_PRODUCTION_MODE: 'true' })))
        .toThrow(/OPERATOR_DECRYPT_DURATION_DAYS/);
    });

    it('a KMS provider is a stub that throws "not configured"', () => {
      let error: Error | undefined;
      try {
        loadConfig(prod({ ...wrappedEnv(), RELAYER_KEK_PROVIDER: 'AWS_KMS' }));
      } catch (e) {
        error = e as Error;
      }
      expect((error?.cause as Error).message).toMatch(/AWS_KMS is not configured/);
    });

    it('does not touch non-production behaviour (NODE_ENV=production alone is not the switch)', () => {
      const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
      const config = loadConfig(withApiKey({ NODE_ENV: 'production', ...wrappedEnv() }));
      expect(config.operatorDecryptPrivateKey).toBe(OPERATOR_KEY);
      expect(config.operatorDecryptDurationDays).toBe(365);
      warn.mockRestore();
    });
  });
});
