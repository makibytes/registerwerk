import { formatUnits, parseUnits } from 'viem';

/** Exact decimal string of a base-unit amount (no floating point), trailing zeros trimmed. */
export function formatTokenAmount(units: bigint, decimals: number): string {
  return formatUnits(units, decimals);
}

/** `formatTokenAmount` with thousands separators on the integer part (display only, still exact). */
export function formatTokenAmountGrouped(units: bigint, decimals: number): string {
  const [int, frac] = formatUnits(units, decimals).split('.');
  const grouped = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
  return frac ? `${grouped}.${frac}` : grouped;
}

/**
 * Parses a user-entered decimal string into base units. Returns null for anything that is not a plain
 * non-negative decimal with at most `decimals` fraction digits (no exponent notation, no sign).
 */
export function parseTokenAmount(text: string, decimals: number): bigint | null {
  const t = (text ?? '').trim();
  if (!/^\d+(\.\d+)?$/.test(t)) return null;
  const frac = t.split('.')[1];
  if (frac && frac.length > decimals) return null;
  return parseUnits(t, decimals);
}

/**
 * Amount to pass as `repay(requested)` for a full repayment. Debt accrues between reading it and the
 * transaction mining; a request below the then-current debt takes the partial branch and leaves residual
 * debt and pledged collateral. `repay` caps the request at the current debt and burns every share, so we
 * request the debt plus a safety margin: the larger of `bufferBps` of the debt and one day of interest at
 * the market's maximum annualized rate (base + slope, WAD), rounded up. Never unlimited.
 */
export function fullRepayRequest(
  debt: bigint,
  rates: { baseRateWad?: string | null; slopeWad?: string | null },
  bufferBps = 100n,
): bigint {
  if (debt <= 0n) return 0n;
  const pctBuffer = (debt * bufferBps + 9_999n) / 10_000n;
  const maxRateWad = BigInt(rates.baseRateWad || '0') + BigInt(rates.slopeWad || '0');
  const WAD = 10n ** 18n;
  const dayInterest = (debt * maxRateWad + WAD * 365n - 1n) / (WAD * 365n);
  const buffer = pctBuffer > dayInterest ? pctBuffer : dayInterest;
  return debt + (buffer > 0n ? buffer : 1n);
}
