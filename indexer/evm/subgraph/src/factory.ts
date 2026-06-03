import { TokenDeployed } from '../generated/AssetTokenFactory/AssetTokenFactory'
import { EwpgERC20, EwpgERC721, EwpgERC1155, EwpgERC3525, EwpgERC4626, EwpgERC7540 } from '../generated/templates'
import { Token } from '../generated/schema'
import { Address, BigInt } from '@graphprotocol/graph-ts'

/**
 * Handles AssetTokenFactory.TokenDeployed(assetId, tokenType, tokenAddress).
 *
 * This is the auto-registration hook: every token deployed through the factory
 * is automatically picked up here and added as a dynamic data source.
 * No manual subgraph redeployment is needed.
 *
 * tokenType: 0 = ERC-20, 1 = ERC-721, 2 = ERC-1155
 */
export function handleTokenDeployed(event: TokenDeployed): void {
  let tokenAddress = event.params.tokenAddress
  let tokenType    = event.params.tokenType
  let assetId      = event.params.assetId

  // Create the Token entity
  let token = new Token(tokenAddress.toHexString())
  token.assetId             = assetId
  token.tokenType           = tokenType
  token.deployedAtBlock     = event.block.number
  token.deployedAtTimestamp = event.block.timestamp
  token.deployedByTx        = event.transaction.hash
  token.totalTransfers      = BigInt.fromI32(0)
  token.totalMints          = BigInt.fromI32(0)
  token.totalBurns          = BigInt.fromI32(0)
  token.save()

  // Instantiate the appropriate template — this starts indexing that contract.
  // tokenType mapping mirrors AssetTokenFactory.deployToken (0-3) and deployVault (4-5).
  if (tokenType == 0) {
    EwpgERC20.create(tokenAddress)
  } else if (tokenType == 1) {
    EwpgERC721.create(tokenAddress)
  } else if (tokenType == 2) {
    EwpgERC1155.create(tokenAddress)
  } else if (tokenType == 3) {
    EwpgERC3525.create(tokenAddress)
  } else if (tokenType == 4) {
    EwpgERC4626.create(tokenAddress)
  } else if (tokenType == 5) {
    EwpgERC7540.create(tokenAddress)
  }
}
