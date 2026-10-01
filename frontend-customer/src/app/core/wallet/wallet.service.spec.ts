import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { Address, Hash } from 'viem';
import { WalletService } from './wallet.service';

const OWNER = '0x1111111111111111111111111111111111111111' as Address;
const TOKEN = '0x2222222222222222222222222222222222222222' as Address;
const SPENDER = '0x3333333333333333333333333333333333333333' as Address;

describe('WalletService.ensureAllowance', () => {
  let service: WalletService;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let read: any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let write: any;
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  let wait: any;

  beforeEach(() => {
    service = new WalletService();
    (service as unknown as { _address: { set(v: Address): void } })._address.set(OWNER);
    write = vi.spyOn(service, 'writeContract').mockImplementation(async () => '0xabc' as Hash);
    wait = vi.spyOn(service, 'waitForTransaction').mockResolvedValue({} as never);
    read = vi.spyOn(service, 'readContract');
  });

  const approveArgs = () => (write.mock.calls as unknown[][]).map((c) => (c[0] as { args: unknown[] }).args);

  it('sends nothing when the allowance already covers the amount', async () => {
    read.mockResolvedValue(10n);
    expect(await service.ensureAllowance(TOKEN, SPENDER, 10n)).toEqual([]);
    expect(write).not.toHaveBeenCalled();
  });

  it('approves the exact amount when there is no allowance', async () => {
    read.mockResolvedValue(0n);
    await service.ensureAllowance(TOKEN, SPENDER, 10n);
    expect(approveArgs()).toEqual([[SPENDER, 10n]]);
  });

  it('resets a stale non-zero allowance to 0 before approving the new amount (5D-01)', async () => {
    read.mockResolvedValue(5n);
    const hashes = await service.ensureAllowance(TOKEN, SPENDER, 10n);
    expect(approveArgs()).toEqual([[SPENDER, 0n], [SPENDER, 10n]]);
    expect(hashes).toHaveLength(2);
    expect(wait).toHaveBeenCalledTimes(2);
  });

  it('never grants an unlimited allowance', async () => {
    read.mockResolvedValue(1n);
    await service.ensureAllowance(TOKEN, SPENDER, 7n);
    expect(approveArgs().every((args: unknown[]) => (args[1] as bigint) <= 7n)).toBe(true);
  });

  it('skips a zero amount', async () => {
    expect(await service.ensureAllowance(TOKEN, SPENDER, 0n)).toEqual([]);
    expect(read).not.toHaveBeenCalled();
  });
});
