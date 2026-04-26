/**
 * Shared models for token holder visualization.
 */

export interface LiveHolder {
  walletAddress: string;
  balance: string | number;
  entityId?: string;
  entityName?: string;
  known: boolean;
  whitelisted: boolean;
}

export interface MintAction {
  toAddress: string;
  amount: string | number;
  reason?: string;
}

export interface BurnAction {
  fromAddress: string;
  amount: string | number;
}

export interface ForceTransferAction {
  from: string;
  to: string;
  value: string | number;
  legalBasis: string;
}
