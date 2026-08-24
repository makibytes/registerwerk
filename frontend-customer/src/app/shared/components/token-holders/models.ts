/**
 * Live holder data from blockchain
 */
export interface LiveHolder {
  walletAddress: string;
  tokenBalance: number;
  lastUpdated?: Date;
  isWhitelisted?: boolean;
}

export interface MintAction {
  recipient: string;
  amount: number;
}

export interface BurnAction {
  amount: number;
  fromWallet?: string;
}

export interface ForceTransferAction {
  fromWallet: string;
  toWallet: string;
  amount: number;
  legalBasis: string;
}

export interface ForceApproveAction {
  ownerWallet: string;
  spenderWallet: string;
  amount: number;
  legalBasis: string;
}
