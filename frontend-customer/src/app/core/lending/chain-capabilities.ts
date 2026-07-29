import { Chain } from '../models';

export type ChainMaturity = 'PRODUCTION' | 'PLACEHOLDER' | 'PROFILE_GATED';

export interface ChainLendingCapability {
  /** Whether `EwpgRepoMarket`-style repo/lending can exist for this chain at all today. */
  lendingSupported: boolean;
  /** Whether ERC-4337 sponsored (gasless) transactions are available on this chain. */
  sponsoredTxSupported: boolean;
  maturity: ChainMaturity;
  /** Short, honest explanation shown in the UI when an action isn't offered — never silence. */
  note: string;
}

/**
 * Static, capability-honest matrix mirroring the platform's own documented chain maturity
 * (`docs/blockchains/index.md`, `backend/.../blockchain/internal/CorrectionCapabilityService`)
 * — repo/lending (`contracts/src/lending/EwpgRepoMarket.sol`) and ERC-4337 sponsored
 * transactions (`contracts/src/ecosystem/EwpgPaymaster.sol`) are EVM-only today. This drives
 * the frontend so a trader on a Solana/Starknet/Stellar/Canton holding sees an honest
 * explanation instead of a "Pledge & borrow" button that could never actually work — see
 * `features/positions/positions.component.ts`.
 *
 * This is deliberately a frontend-only static constant, not a backend-served capability
 * endpoint: it reflects fixed platform/contract architecture, not data that changes per
 * deployment or at runtime (the same reasoning already applied to other static reference
 * matrices in this app, e.g. `TradingVenueCode` display mapping).
 */
export const CHAIN_LENDING_CAPABILITIES: Record<Chain, ChainLendingCapability> = {
  ETHEREUM: evm(),
  POLYGON: evm(),
  BASE: evm(),
  ARBITRUM: evm(),
  AVALANCHE: evm(),
  OPTIMISM: evm(),
  FHENIX: {
    lendingSupported: false,
    sponsoredTxSupported: true,
    maturity: 'PRODUCTION',
    note: 'Confidential EVM: repo/lending markets aren’t deployed for confidential tokens yet — trade via the Trading Desk instead.',
  },
  INCO: {
    lendingSupported: false,
    sponsoredTxSupported: true,
    maturity: 'PRODUCTION',
    note: 'Confidential EVM: repo/lending markets aren’t deployed for confidential tokens yet — trade via the Trading Desk instead.',
  },
  SOLANA: {
    lendingSupported: false,
    sponsoredTxSupported: false,
    maturity: 'PRODUCTION',
    note: 'Repo/lending is EVM-only today. Solana admin corrections are also still Phase-4 (not yet implemented) — trade this holding via the Trading Desk.',
  },
  STARKNET: {
    lendingSupported: false,
    sponsoredTxSupported: false,
    maturity: 'PLACEHOLDER',
    note: 'Starknet support is a placeholder pending a real class hash — this holding has no lending market and limited on-chain tooling yet.',
  },
  STELLAR: {
    lendingSupported: false,
    sponsoredTxSupported: false,
    maturity: 'PLACEHOLDER',
    note: 'Stellar support is a placeholder pending trustline/indexer work — this holding has no lending market yet.',
  },
  CANTON: {
    lendingSupported: false,
    sponsoredTxSupported: false,
    maturity: 'PROFILE_GATED',
    note: 'Canton/DAML support requires the operator’s -Pcanton build profile and has no EVM-style repo/lending market — trade via the Trading Desk.',
  },
};

function evm(): ChainLendingCapability {
  return {
    lendingSupported: true,
    sponsoredTxSupported: true,
    maturity: 'PRODUCTION',
    note: '',
  };
}

export function lendingCapabilityFor(chain: Chain | null): ChainLendingCapability {
  if (!chain) {
    return {
      lendingSupported: false,
      sponsoredTxSupported: false,
      maturity: 'PLACEHOLDER',
      note: 'This holding has no known deployment chain yet.',
    };
  }
  return CHAIN_LENDING_CAPABILITIES[chain];
}
