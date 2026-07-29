import { Transfer as TransferEvent, Whitelisted, RemovedFromWhitelist } from '../generated/templates/EwpgERC721/EwpgERC721'
import { Token, Transfer, WhitelistChange, HolderBalance } from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const EVENT_DERIVED = 'EVENT_DERIVED'

export function handleTransfer(event: TransferEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO

  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let transfer = new Transfer(id)
  transfer.projectionStatus = EVENT_DERIVED
  transfer.token           = tokenAddress
  transfer.tokenType       = 1
  transfer.from            = event.params.from
  transfer.to              = event.params.to
  transfer.tokenId         = event.params.tokenId
  transfer.amount          = null  // ERC-721 has no amount
  transfer.eventType       = isMint ? 'MINT' : (isBurn ? 'BURN' : 'TRANSFER')
  transfer.blockNumber     = event.block.number
  transfer.blockTimestamp  = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex        = event.logIndex
  transfer.save()

  // ERC-721: balance is either 0 or 1 per token id;
  // track overall NFT count per holder for convenience
  if (!isMint) {
    let fromId = tokenAddress + '-' + event.params.from.toHexString()
    let fromBalance = HolderBalance.load(fromId)
    if (fromBalance != null) {
      fromBalance.balance = fromBalance.balance.minus(BigInt.fromI32(1))
      fromBalance.lastUpdatedBlock     = event.block.number
      fromBalance.lastUpdatedTimestamp = event.block.timestamp
      fromBalance.save()
    }
  }
  if (!isBurn) {
    let toId = tokenAddress + '-' + event.params.to.toHexString()
    let toBalance = HolderBalance.load(toId)
    if (toBalance == null) {
      toBalance = new HolderBalance(toId)
      toBalance.projectionStatus = EVENT_DERIVED
      toBalance.token   = tokenAddress
      toBalance.holder  = event.params.to
      toBalance.balance = BigInt.fromI32(0)
      toBalance.lastUpdatedBlock     = event.block.number
      toBalance.lastUpdatedTimestamp = event.block.timestamp
    }
    toBalance.balance = toBalance.balance.plus(BigInt.fromI32(1))
    toBalance.lastUpdatedBlock     = event.block.number
    toBalance.lastUpdatedTimestamp = event.block.timestamp
    toBalance.save()
  }

  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

export function handleWhitelisted(event: Whitelisted): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.projectionStatus = EVENT_DERIVED
  change.token           = event.address.toHexString()
  change.account         = event.params.account
  change.added           = true
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleRemovedFromWhitelist(event: RemovedFromWhitelist): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.projectionStatus = EVENT_DERIVED
  change.token           = event.address.toHexString()
  change.account         = event.params.account
  change.added           = false
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
