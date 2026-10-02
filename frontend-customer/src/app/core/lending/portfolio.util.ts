import type { LendingMarket, LendingQuote } from '../models';
import { formatTokenAmountGrouped } from './token-amount.util';

export interface CurrencyTotal { currency: string | null; total: number }

/**
 * Nominal totals per currency. Different currencies are never added together; holdings without a currency
 * are reported separately (currency null) instead of being folded into another total.
 */
export function nominalTotalsByCurrency(rows: readonly { currency: string | null; nominalAmount: number }[]): CurrencyTotal[] {
  const totals = new Map<string | null, number>();
  for (const r of rows) {
    totals.set(r.currency, Math.round(((totals.get(r.currency) ?? 0) + r.nominalAmount) * 1e6) / 1e6);
  }
  return [...totals.entries()].map(([currency, total]) => ({ currency, total }));
}

export interface IndicativeMark { display: string; currency: string | null; asOf: string }

/**
 * Indicative oracle mark of `nominalAmount` collateral units in the market's loan token, or null when it
 * must not be shown: oracle flagged unreliable, price older than the market's `maxPriceAgeSeconds`
 * (or its timestamp unreadable), or the amount is not a whole number of collateral units. Computed with
 * BigInt and the market's own loan-token decimals (not a hard-coded 1e6) and never summed into a headline.
 */
export function indicativeMark(
  nominalAmount: number,
  quote: Pick<LendingQuote, 'pricePerUnit' | 'priceUpdatedAt' | 'oracleReliable'>,
  market: Pick<LendingMarket, 'loanTokenDecimals' | 'loanRailCode' | 'maxPriceAgeSeconds'>,
  now: number = Date.now(),
): IndicativeMark | null {
  if (!quote.oracleReliable) return null;
  if (!Number.isSafeInteger(nominalAmount) || nominalAmount < 0) return null;
  const updated = Date.parse(quote.priceUpdatedAt);
  if (!Number.isFinite(updated)) return null;
  const maxAgeMs = Number(market.maxPriceAgeSeconds ?? '0') * 1000;
  if (!(maxAgeMs > 0) || now - updated > maxAgeMs) return null;
  let value: bigint;
  try {
    value = BigInt(nominalAmount) * BigInt(quote.pricePerUnit);
  } catch {
    return null;
  }
  const decimals = market.loanTokenDecimals ?? 6;
  return {
    display: formatTokenAmountGrouped(value, decimals),
    currency: market.loanRailCode,
    asOf: new Date(updated).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }),
  };
}
