import {
  Transfer as TransferEvent,
  TransferValue as TransferValueEvent,
  SlotChanged as SlotChangedEvent,
  Whitelisted,
  AddressFrozen,
} from '../generated/templates/EwpgERC3525/EwpgERC3525'
import { Token, Transfer, ValueTransfer, SlotTransfer, WhitelistChange, HolderBalance } from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE   = 3  // ERC-3525

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

/**
 * ERC-721-style transfer of an ERC-3525 token ID between owners.
 * Mint: from == address(0). Burn: to == address(0).
 */
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
  transfer.tokenId         = event.params.tokenId
  transfer.eventType       = eventType
  transfer.blockNumber     = event.block.number
  transfer.blockTimestamp  = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex        = event.logIndex
  transfer.save()

  // For ERC-3525 ownership tracking, treat each tokenId as a 1-unit balance
  if (!isMint) {
    let fromBalance = getOrCreateHolderBalance(tokenAddress, event.params.from)
    fromBalance.balance = fromBalance.balance.minus(BigInt.fromI32(1))
    fromBalance.lastUpdatedBlock     = event.block.number
    fromBalance.lastUpdatedTimestamp = event.block.timestamp
    fromBalance.save()
  }
  if (!isBurn) {
    let toBalance = getOrCreateHolderBalance(tokenAddress, event.params.to)
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

/**
 * Value transfer between two token IDs within the same slot.
 * Represents partial/full coupon/principal value movements.
 */
export function handleTransferValue(event: TransferValueEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let vt = new ValueTransfer(id)
  vt.token           = event.address.toHexString()
  vt.fromTokenId     = event.params.fromTokenId
  vt.toTokenId       = event.params.toTokenId
  vt.value           = event.params.value
  vt.blockNumber     = event.block.number
  vt.blockTimestamp  = event.block.timestamp
  vt.transactionHash = event.transaction.hash
  vt.save()
}

/**
 * A token ID moves from one slot to another (e.g. bond maturity reclassification).
 */
export function handleSlotChanged(event: SlotChangedEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let st = new SlotTransfer(id)
  st.token           = event.address.toHexString()
  st.tokenId         = event.params.tokenId
  st.oldSlot         = event.params.oldSlot
  st.newSlot         = event.params.newSlot
  st.blockNumber     = event.block.number
  st.blockTimestamp  = event.block.timestamp
  st.transactionHash = event.transaction.hash
  st.save()
}

export function handleWhitelisted(event: Whitelisted): void {
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

export function handleAddressFrozen(event: AddressFrozen): void {
  // Freeze/unfreeze is surfaced as a WhitelistChange: added=false means frozen.
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.addr
  change.added           = !event.params.frozen   // added=true means "trading enabled"
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
