import { Transfer as TransferEvent, Whitelisted, RemovedFromWhitelist } from '../generated/templates/EwpgERC20/EwpgERC20'
import { Token, Transfer, WhitelistChange, HolderBalance } from '../generated/schema'
import { Address, BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const EVENT_DERIVED = 'EVENT_DERIVED'

function getOrCreateHolderBalance(tokenAddress: string, holder: Bytes): HolderBalance {
  let id = tokenAddress + '-' + holder.toHexString()
  let hb = HolderBalance.load(id)
  if (hb == null) {
    hb = new HolderBalance(id)
    hb.projectionStatus = EVENT_DERIVED
    hb.token   = tokenAddress
    hb.holder  = holder
    hb.balance = BigInt.fromI32(0)
    hb.lastUpdatedBlock     = BigInt.fromI32(0)
    hb.lastUpdatedTimestamp = BigInt.fromI32(0)
  }
  return hb as HolderBalance
}

export function handleTransfer(event: TransferEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return  // safety guard

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO
  let eventType = isMint ? 'MINT' : (isBurn ? 'BURN' : 'TRANSFER')

  // Persist the Transfer entity
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let transfer = new Transfer(id)
  transfer.projectionStatus = EVENT_DERIVED
  transfer.token            = tokenAddress
  transfer.tokenType        = 0
  transfer.from             = event.params.from
  transfer.to               = event.params.to
  transfer.amount           = event.params.value
  transfer.eventType        = eventType
  transfer.blockNumber      = event.block.number
  transfer.blockTimestamp   = event.block.timestamp
  transfer.transactionHash  = event.transaction.hash
  transfer.logIndex         = event.logIndex
  transfer.save()

  // Update holder balances
  if (!isMint) {
    let fromBalance = getOrCreateHolderBalance(tokenAddress, event.params.from)
    fromBalance.balance = fromBalance.balance.minus(event.params.value)
    fromBalance.lastUpdatedBlock     = event.block.number
    fromBalance.lastUpdatedTimestamp = event.block.timestamp
    fromBalance.save()
  }
  if (!isBurn) {
    let toBalance = getOrCreateHolderBalance(tokenAddress, event.params.to)
    toBalance.balance = toBalance.balance.plus(event.params.value)
    toBalance.lastUpdatedBlock     = event.block.number
    toBalance.lastUpdatedTimestamp = event.block.timestamp
    toBalance.save()
  }

  // Update token counters
  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

export function handleWhitelisted(event: Whitelisted): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.projectionStatus = EVENT_DERIVED
  change.token            = event.address.toHexString()
  change.account          = event.params.account
  change.added            = true
  change.blockNumber      = event.block.number
  change.blockTimestamp   = event.block.timestamp
  change.transactionHash  = event.transaction.hash
  change.save()
}

export function handleRemovedFromWhitelist(event: RemovedFromWhitelist): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.projectionStatus = EVENT_DERIVED
  change.token            = event.address.toHexString()
  change.account          = event.params.account
  change.added            = false
  change.blockNumber      = event.block.number
  change.blockTimestamp   = event.block.timestamp
  change.transactionHash  = event.transaction.hash
  change.save()
}
