import {
  Transfer as TransferEvent,
  AddressFrozen,
  TokensFrozen,
  Paused,
  Unpaused,
  IdentityRegistered as IdentityRegisteredEvent,
  IdentityRemoved as IdentityRemovedEvent,
  ComplianceAdded,
} from '../generated/templates/EwpgERC3643/EwpgERC3643'
import {
  Token, Transfer, WhitelistChange, HolderBalance,
  IdentityRegistered, ComplianceChange,
} from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE   = 6  // ERC-3643 (T-REX) — deployed via TREXFactory, outside the numeric range of AssetTokenFactory types

function getOrCreateHolderBalance(tokenAddress: string, holder: Bytes): HolderBalance {
  let id = tokenAddress + '-' + holder.toHexString()
  let hb = HolderBalance.load(id)
  if (hb == null) {
    hb = new HolderBalance(id)
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
  if (token == null) return

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO
  let eventType = isMint ? 'MINT' : (isBurn ? 'BURN' : 'TRANSFER')

  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let transfer = new Transfer(id)
  transfer.token           = tokenAddress
  transfer.tokenType       = TOKEN_TYPE
  transfer.from            = event.params.from
  transfer.to              = event.params.to
  transfer.amount          = event.params.value
  transfer.eventType       = eventType
  transfer.blockNumber     = event.block.number
  transfer.blockTimestamp  = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex        = event.logIndex
  transfer.save()

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

  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

/** Wallet-level address freeze / unfreeze (compliance action). */
export function handleAddressFrozen(event: AddressFrozen): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.addr
  change.added           = !event.params.frozen
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

/** Partial token freeze (locks a specific amount, not the whole address). */
export function handleTokensFrozen(event: TokensFrozen): void {
  // Record as whitelist change; amount info lives in the event log but not stored separately
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.addr
  change.added           = false   // frozen = not freely tradeable
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

/** Token paused — all transfers blocked. Stored as a zero-address WhitelistChange marker. */
export function handlePaused(event: Paused): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.account
  change.added           = false
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleUnpaused(event: Unpaused): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.account
  change.added           = true
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleIdentityRegistered(event: IdentityRegisteredEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let entry = new IdentityRegistered(id)
  entry.token            = event.address.toHexString()
  entry.investorAddress  = event.params.investorAddress
  entry.identity         = event.params.identity
  entry.eventKind        = 'REGISTERED'
  entry.blockNumber      = event.block.number
  entry.blockTimestamp   = event.block.timestamp
  entry.transactionHash  = event.transaction.hash
  entry.save()
}

export function handleIdentityRemoved(event: IdentityRemovedEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let entry = new IdentityRegistered(id)
  entry.token            = event.address.toHexString()
  entry.investorAddress  = event.params.investorAddress
  entry.identity         = event.params.identity
  entry.eventKind        = 'REMOVED'
  entry.blockNumber      = event.block.number
  entry.blockTimestamp   = event.block.timestamp
  entry.transactionHash  = event.transaction.hash
  entry.save()
}

export function handleComplianceAdded(event: ComplianceAdded): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new ComplianceChange(id)
  change.token           = event.address.toHexString()
  change.compliance      = event.params.compliance
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
