import { ConfidentialTokenDeployed } from '../generated/EwpgConfidentialFactory/EwpgConfidentialFactory'
import { ConfidentialERC20 } from '../generated/templates'
import { ConfidentialToken } from '../generated/schema'

const EVENT_DERIVED = 'EVENT_DERIVED'

/**
 * Handles EwpgConfidentialFactory.ConfidentialTokenDeployed(assetId, tokenType, tokenAddress) —
 * observes provisional factory-reported metadata for confidential tokens (finding #8, Phase 9).
 * It does not verify deployment, runtime code, or database linkage. ConfidentialERC3643 inherits
 * ConfidentialERC20 and emits the identical Transfer/Mint/Burn events, so both token types share
 * the one ConfidentialERC20 template — no separate template needed per type.
 */
export function handleConfidentialTokenDeployed(event: ConfidentialTokenDeployed): void {
  let tokenAddress = event.params.tokenAddress

  let token = new ConfidentialToken(tokenAddress.toHexString())
  token.projectionStatus = EVENT_DERIVED
  token.factoryReportedAssetId = event.params.assetId
  token.tokenType = event.params.tokenType
  token.observedAtBlock = event.block.number
  token.observedAtTimestamp = event.block.timestamp
  token.observedInTx = event.transaction.hash
  token.save()

  ConfidentialERC20.create(tokenAddress)
}
