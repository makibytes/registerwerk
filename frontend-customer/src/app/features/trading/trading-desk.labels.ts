import { TradeExecution } from '../../core/models';

/** Disputing moves the trade to PAYMENT_UNRESOLVED; the reservation is NOT released (8A-07). */
export const PAYMENT_DISPUTED_TOAST =
  "Payment disputed. The trade is now 'payment unresolved'; the units stay reserved until a registry operator resolves it.";

/** A FAILED trade with a declared payment was not auto-released: say so instead of "units were released". */
export function failedExplanation(trade: Pick<TradeExecution, 'paymentReference' | 'paymentDeclaredAt'>): string {
  return trade.paymentReference || trade.paymentDeclaredAt
    ? 'A payment was declared; the units were not automatically released - contact the registry operator.'
    : 'The trade did not go through. The units were released.';
}
