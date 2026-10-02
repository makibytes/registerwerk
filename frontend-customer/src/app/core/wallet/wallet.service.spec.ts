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

describe('WalletService chain/account tracking', () => {
  type Handler = (arg: unknown) => void;
  let handlers: Record<string, Handler>;
  let chain: number;
  let requests: string[];
  let service: WalletService;

  beforeEach(() => {
    handlers = {};
    chain = 1;
    requests = [];
    const provider = {
      request: vi.fn(async ({ method, params }: { method: string; params?: unknown[] }) => {
        requests.push(method);
        if (method === 'eth_requestAccounts') return [OWNER];
        if (method === 'eth_chainId') return '0x' + chain.toString(16);
        if (method === 'wallet_switchEthereumChain') {
          if (switchAllowed) chain = Number.parseInt((params![0] as { chainId: string }).chainId, 16);
          else throw new Error('user rejected');
          return null;
        }
        throw new Error('unexpected ' + method);
      }),
      on: vi.fn((event: string, h: Handler) => { handlers[event] = h; }),
      removeListener: vi.fn((event: string) => { delete handlers[event]; }),
    };
    (globalThis as unknown as { window: { ethereum: unknown } }).window ??= { ethereum: provider };
    (window as unknown as { ethereum: unknown }).ethereum = provider;
    switchAllowed = true;
    service = new WalletService();
  });
  let switchAllowed = true;

  it('follows accountsChanged / chainChanged and stops after disconnect', async () => {
    await service.connect();
    expect(service.address()).toBe(OWNER);
    handlers['accountsChanged']([TOKEN]);
    expect(service.address()).toBe(TOKEN);
    handlers['chainChanged']('0x89');
    expect(service.chainId()).toBe(137);
    handlers['accountsChanged']([]);
    expect(service.address()).toBeNull();
    service.disconnect();
    expect(handlers['accountsChanged']).toBeUndefined();
  });

  it('ensureChain switches the wallet when it is on another chain', async () => {
    await service.connect();
    await service.ensureChain(11155111, 'Sepolia');
    expect(requests).toContain('wallet_switchEthereumChain');
    expect(service.chainId()).toBe(11155111);
  });

  it('ensureChain throws a clear message when the wallet stays on the wrong chain', async () => {
    await service.connect();
    switchAllowed = false;
    await expect(service.ensureChain(137, 'Polygon')).rejects.toThrow('Switch your wallet to Polygon (id 137)');
  });

  it('ensureChain fails closed for a market without a chain id', async () => {
    await service.connect();
    await expect(service.ensureChain(undefined)).rejects.toThrow(/unknown/);
    await expect(service.ensureChain(null)).rejects.toThrow(/unknown/);
  });

  it('writeContract-style callers never reach approve/simulate when ensureChain throws first', async () => {
    await service.connect();
    switchAllowed = false;
    const write = vi.spyOn(service, 'writeContract');
    const action = async () => { await service.ensureChain(137, 'Polygon'); await service.writeContract({ address: TOKEN, abi: [], functionName: 'approve' }); };
    await expect(action()).rejects.toThrow('Switch your wallet');
    expect(write).not.toHaveBeenCalled();
  });
});
