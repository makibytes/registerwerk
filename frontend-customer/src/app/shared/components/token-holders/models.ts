/**
 * Live holder data from blockchain
 */
export interface LiveHolder {
  walletAddress: string;
  tokenBalance: number;
  lastUpdated?: Date;
  isWhitelisted?: boolean;
}

/**
 * Mint action emitted by TokenAdminPanel
 */
export interface MintAction {
  recipient: string;
  amount: number;
  chainId: number;
}

/**
 * Burn action emitted by TokenAdminPanel
 */
export interface BurnAction {
  amount: number;
  fromWallet?: string;
  chainId: number;
}

/**
 * Force transfer action emitted by TokenAdminPanel
 */
export interface ForceTransferAction {
  fromWallet: string;
  toWallet: string;
  amount: number;
  chainId: number;
}

/**
 * Force approve action emitted by TokenAdminPanel
 */
export interface ForceApproveAction {
  ownerWallet: string;
  spenderWallet: string;
  amount: number;
  chainId: number;
}
