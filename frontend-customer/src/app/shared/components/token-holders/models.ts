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
  /** Decimal string of base units (no precision loss above 2^53). */
  amount: string;
  /** Authenticator (TOTP) code for built-in sign-in; omitted under Entra (claims challenge instead). */
  totpCode?: string;
  /** The approved `ISSUER_MINT` request in the approval queue; the host claims its single-use token when it submits. */
  approvalRequestId: string;
}

/**
 * Burn from a holder wallet (T3-01): needs the issuer's ASSET_TOKEN_ADMIN grant plus step-up and a
 * registry administrator's approval token scoped to `ISSUER_BURN_EWG26`.
 */
export interface BurnAction {
  /** Decimal string of base units (no precision loss above 2^53). */
  amount: string;
  fromWallet: string;
  /** Authenticator (TOTP) code for built-in sign-in; omitted under Entra (claims challenge instead). */
  totpCode?: string;
  /** The approved `ISSUER_BURN_EWG26` request in the approval queue. */
  approvalRequestId: string;
}

export interface ForceTransferAction {
  fromWallet: string;
  toWallet: string;
  /** Decimal string of base units (no precision loss above 2^53). */
  amount: string;
  legalBasis: string;
  /** Authenticator (TOTP) code for built-in sign-in; omitted under Entra (claims challenge instead). */
  totpCode?: string;
  /** The approved `ISSUER_FORCED_TRANSFER_EWG24` request in the approval queue. */
  approvalRequestId: string;
}

export interface ForceApproveAction {
  ownerWallet: string;
  spenderWallet: string;
  /** Decimal string of base units (no precision loss above 2^53). */
  amount: string;
  legalBasis: string;  /** Authenticator (TOTP) code for built-in sign-in; omitted under Entra (claims challenge instead). */
  totpCode?: string;
  /** The approved `ISSUER_FORCED_APPROVE_OVERRIDE` request in the approval queue. */
  approvalRequestId: string;
}
