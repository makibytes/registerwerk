import { describe, expect, it } from 'vitest';
import { custodyLabel, isExportableCustody } from '../models';
import { KMS_KEY_VERSION_PATTERN } from '../../features/wallets/dialogs/attach-kms-dialog.component';

describe('wallet custody', () => {
  it('labels every custody type and only software keystores are exportable', () => {
    expect(custodyLabel('KMS')).toBe('Cloud KMS (non-exportable)');
    expect(custodyLabel('PKCS11')).toBe('PKCS#11 HSM (non-exportable)');
    expect(custodyLabel('SOFTWARE')).toBe('Encrypted software keystore');
    expect(isExportableCustody('SOFTWARE')).toBe(true);
    expect(isExportableCustody('KMS')).toBe(false);
    expect(isExportableCustody('PKCS11')).toBe(false);
  });

  it('accepts a Cloud KMS key version resource name and nothing else', () => {
    expect(KMS_KEY_VERSION_PATTERN.test('projects/p/locations/europe-west3/keyRings/r/cryptoKeys/k/cryptoKeyVersions/1')).toBe(true);
    expect(KMS_KEY_VERSION_PATTERN.test('projects/p/locations/l/keyRings/r/cryptoKeys/k')).toBe(false);
    expect(KMS_KEY_VERSION_PATTERN.test('0x1234')).toBe(false);
  });
});
