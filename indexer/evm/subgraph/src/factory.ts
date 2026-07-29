import { TokenDeployed, VaultDeployed } from '../generated/AssetTokenFactory/AssetTokenFactory'
import { EwpgERC20, EwpgERC721, EwpgERC1155, EwpgERC3525, EwpgERC4626, EwpgERC7540 } from '../generated/templates'
import { Token } from '../generated/schema'
import { Address, BigInt, Bytes } from '@graphprotocol/graph-ts'

const EVENT_DERIVED = 'EVENT_DERIVED'

/**
 * Handles AssetTokenFactory.TokenDeployed(assetId, tokenType, tokenAddress).
 *
 * This observes the factory's address, type and asset-ID claims and creates a dynamic source.
 * It does not verify runtime code, deployment success, or a database record/link.
 * No manual subgraph redeployment is needed.
 *
 * tokenType: 0 = ERC-20, 1 = ERC-721, 2 = ERC-1155
 */
export function handleTokenDeployed(event: TokenDeployed): void {
  let tokenAddress = event.params.tokenAddress
  createToken(
    tokenAddress,
    event.params.tokenType,
    event.params.assetId,
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
  )

  // deployToken only emits types 0-3. Vault types are registered by handleVaultDeployed.
  createTemplate(tokenAddress, event.params.tokenType)
}

/** Observes ERC-4626/ERC-7540 vault metadata emitted by the configured factory. */
export function handleVaultDeployed(event: VaultDeployed): void {
  let vaultAddress = event.params.vaultAddress
  let token = createToken(
    vaultAddress,
    event.params.tokenType,
    event.params.assetId,
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
  )
  token.factoryReportedUnderlyingAsset = event.params.underlyingAsset
  token.save()

  createTemplate(vaultAddress, event.params.tokenType)
}

function createToken(
  tokenAddress: Address,
  tokenType: i32,
  factoryReportedAssetId: Bytes,
  observedAtBlock: BigInt,
  observedAtTimestamp: BigInt,
  observedInTx: Bytes,
): Token {
  let token = new Token(tokenAddress.toHexString())
  token.projectionStatus = EVENT_DERIVED
  token.factoryReportedAssetId = factoryReportedAssetId
  token.tokenType = tokenType
  token.observedAtBlock = observedAtBlock
  token.observedAtTimestamp = observedAtTimestamp
  token.observedInTx = observedInTx
  token.totalTransfers      = BigInt.fromI32(0)
  token.totalMints          = BigInt.fromI32(0)
  token.totalBurns          = BigInt.fromI32(0)
  token.save()
  return token
}

/** Instantiates a template for the address emitted by the configured factory. */
function createTemplate(tokenAddress: Address, tokenType: i32): void {
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
