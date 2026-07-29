import {
  Transfer as TransferEvent,
  TransferValue as TransferValueEvent,
  SlotChanged as SlotChangedEvent,
  Whitelisted,
  RemovedFromWhitelist,
  AddressFrozen,
  AddressUnfrozen,
} from '../generated/templates/EwpgERC3525/EwpgERC3525'
import {
  Token,
  Transfer,
  ValueTransfer,
  SlotTransfer,
  WhitelistChange,
  AddressFreezeChange,
  Erc3525Position,
  Erc3525OwnerSlotBalance,
} from '../generated/schema'
import { Address, BigInt, Bytes, ethereum } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE = 3
const EVENT_DERIVED = 'EVENT_DERIVED'
const INCOMPLETE = 'INCOMPLETE'

function positionId(tokenAddress: string, tokenId: BigInt): string {
  return tokenAddress + '-' + tokenId.toString()
}

function allocationId(tokenAddress: string, owner: Bytes, slot: BigInt): string {
  return tokenAddress + '-' + owner.toHexString() + '-' + slot.toString()
}

function createPosition(tokenAddress: string, tokenId: BigInt, status: string): Erc3525Position {
  let position = new Erc3525Position(positionId(tokenAddress, tokenId))
  position.token = tokenAddress
  position.tokenId = tokenId
  position.owner = Address.zero()
  position.slot = BigInt.zero()
  position.value = BigInt.zero()
  position.active = false
  position.slotAssigned = false
  position.projectionStatus = status
  position.lastUpdatedBlock = BigInt.zero()
  position.lastUpdatedTimestamp = BigInt.zero()
  return position
}

function loadOrCreateIncompletePosition(tokenAddress: string, tokenId: BigInt): Erc3525Position {
  let position = Erc3525Position.load(positionId(tokenAddress, tokenId))
  if (position == null) {
    position = createPosition(tokenAddress, tokenId, INCOMPLETE)
  }
  return position as Erc3525Position
}

function updateOwnerSlot(
  tokenAddress: string,
  owner: Bytes,
  slot: BigInt,
  valueDelta: BigInt,
  countDelta: BigInt,
  status: string,
  block: ethereum.Block,
): void {
  let id = allocationId(tokenAddress, owner, slot)
  let balance = Erc3525OwnerSlotBalance.load(id)
  if (balance == null) {
    balance = new Erc3525OwnerSlotBalance(id)
    balance.token = tokenAddress
    balance.owner = owner
    balance.slot = slot
    balance.value = BigInt.zero()
    balance.positionCount = BigInt.zero()
    balance.projectionStatus = status
  }

  balance.value = balance.value.plus(valueDelta)
  balance.positionCount = balance.positionCount.plus(countDelta)
  if (status == INCOMPLETE) balance.projectionStatus = INCOMPLETE
  if (balance.value.lt(BigInt.zero())) balance.projectionStatus = INCOMPLETE
  if (balance.positionCount.lt(BigInt.zero())) balance.projectionStatus = INCOMPLETE
  balance.lastUpdatedBlock = block.number
  balance.lastUpdatedTimestamp = block.timestamp
  balance.save()
}

function movePositionOutOfAggregate(
  tokenAddress: string,
  position: Erc3525Position,
  owner: Bytes,
  block: ethereum.Block,
): void {
  if (position.slotAssigned) {
    updateOwnerSlot(
      tokenAddress,
      owner,
      position.slot,
      BigInt.zero().minus(position.value),
      BigInt.fromI32(-1),
      position.projectionStatus,
      block,
    )
  }
}

function movePositionIntoAggregate(
  tokenAddress: string,
  position: Erc3525Position,
  owner: Bytes,
  block: ethereum.Block,
): void {
  if (position.slotAssigned) {
    updateOwnerSlot(
      tokenAddress,
      owner,
      position.slot,
      position.value,
      BigInt.fromI32(1),
      position.projectionStatus,
      block,
    )
  }
}

/**
 * ERC-3525 ownership transfer. Economic notional follows the token ID's stored value; this
 * handler never writes HolderBalance because one token ID is not one unit of notional.
 */
export function handleTransfer(event: TransferEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString() == ADDRESS_ZERO
  let eventType = isMint ? 'MINT' : isBurn ? 'BURN' : 'TRANSFER'

  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let transfer = new Transfer(id)
  transfer.token = tokenAddress
  transfer.tokenType = TOKEN_TYPE
  transfer.from = event.params.from
  transfer.to = event.params.to
  transfer.tokenId = event.params.tokenId
  transfer.eventType = eventType
  transfer.projectionStatus = EVENT_DERIVED
  transfer.blockNumber = event.block.number
  transfer.blockTimestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex = event.logIndex
  transfer.save()

  let idForPosition = positionId(tokenAddress, event.params.tokenId)
  let position = Erc3525Position.load(idForPosition)
  if (position == null) {
    position = createPosition(tokenAddress, event.params.tokenId, isMint ? EVENT_DERIVED : INCOMPLETE)
  }

  if (!isMint) {
    let recordedOwner = position.owner
    if (recordedOwner.toHexString() != event.params.from.toHexString()) position.projectionStatus = INCOMPLETE
    if (position.active) {
      movePositionOutOfAggregate(tokenAddress, position as Erc3525Position, event.params.from, event.block)
    }
  }

  if (isBurn) {
    position.active = false
    position.owner = event.params.to
  } else {
    position.owner = event.params.to
    position.active = true
    if (!isMint) {
      movePositionIntoAggregate(tokenAddress, position as Erc3525Position, event.params.to, event.block)
    }
  }
  position.lastUpdatedBlock = event.block.number
  position.lastUpdatedTimestamp = event.block.timestamp
  position.save()

  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

/** Value movement updates token-ID value first and owner-slot notional only once both owner and
 * slot are known. During mint the slot arrives in the following SlotChanged event. */
export function handleTransferValue(event: TransferValueEvent): void {
  let tokenAddress = event.address.toHexString()
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let vt = new ValueTransfer(id)
  vt.token = tokenAddress
  vt.fromTokenId = event.params.fromTokenId
  vt.toTokenId = event.params.toTokenId
  vt.value = event.params.value
  vt.projectionStatus = EVENT_DERIVED
  vt.blockNumber = event.block.number
  vt.blockTimestamp = event.block.timestamp
  vt.transactionHash = event.transaction.hash
  vt.save()

  if (!event.params.fromTokenId.equals(BigInt.zero())) {
    let fromPosition = loadOrCreateIncompletePosition(tokenAddress, event.params.fromTokenId)
    if (fromPosition.active && fromPosition.slotAssigned) {
      updateOwnerSlot(
        tokenAddress,
        fromPosition.owner,
        fromPosition.slot,
        BigInt.zero().minus(event.params.value),
        BigInt.zero(),
        fromPosition.projectionStatus,
        event.block,
      )
    }
    fromPosition.value = fromPosition.value.minus(event.params.value)
    if (fromPosition.value.lt(BigInt.zero())) fromPosition.projectionStatus = INCOMPLETE
    fromPosition.lastUpdatedBlock = event.block.number
    fromPosition.lastUpdatedTimestamp = event.block.timestamp
    fromPosition.save()
  }

  if (!event.params.toTokenId.equals(BigInt.zero())) {
    let toPosition = loadOrCreateIncompletePosition(tokenAddress, event.params.toTokenId)
    if (toPosition.active && toPosition.slotAssigned) {
      updateOwnerSlot(
        tokenAddress,
        toPosition.owner,
        toPosition.slot,
        event.params.value,
        BigInt.zero(),
        toPosition.projectionStatus,
        event.block,
      )
    }
    toPosition.value = toPosition.value.plus(event.params.value)
    toPosition.lastUpdatedBlock = event.block.number
    toPosition.lastUpdatedTimestamp = event.block.timestamp
    toPosition.save()
  }
}

/** SlotChanged is deliberately responsible for first aggregation at mint because the value event
 * precedes it. Later slot changes move the entire current notional between slot aggregates. */
export function handleSlotChanged(event: SlotChangedEvent): void {
  let tokenAddress = event.address.toHexString()
  let position = loadOrCreateIncompletePosition(tokenAddress, event.params.tokenId)
  let recordedSlot = position.slot

  if (position.active && position.slotAssigned) {
    if (!recordedSlot.equals(event.params.oldSlot)) position.projectionStatus = INCOMPLETE
    movePositionOutOfAggregate(tokenAddress, position, position.owner, event.block)
  }

  position.slot = event.params.newSlot
  position.slotAssigned = true
  if (position.active) movePositionIntoAggregate(tokenAddress, position, position.owner, event.block)
  position.lastUpdatedBlock = event.block.number
  position.lastUpdatedTimestamp = event.block.timestamp
  position.save()

  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let st = new SlotTransfer(id)
  st.token = tokenAddress
  st.tokenId = event.params.tokenId
  st.oldSlot = event.params.oldSlot
  st.newSlot = event.params.newSlot
  st.projectionStatus = EVENT_DERIVED
  st.blockNumber = event.block.number
  st.blockTimestamp = event.block.timestamp
  st.transactionHash = event.transaction.hash
  st.save()
}

export function handleWhitelisted(event: Whitelisted): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token = event.address.toHexString()
  change.account = event.params.account
  change.added = true
  change.projectionStatus = EVENT_DERIVED
  change.blockNumber = event.block.number
  change.blockTimestamp = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleRemovedFromWhitelist(event: RemovedFromWhitelist): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token = event.address.toHexString()
  change.account = event.params.account
  change.added = false
  change.projectionStatus = EVENT_DERIVED
  change.blockNumber = event.block.number
  change.blockTimestamp = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleAddressFrozen(event: AddressFrozen): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new AddressFreezeChange(id)
  change.token = event.address.toHexString()
  change.account = event.params.account
  change.frozen = true
  change.reason = event.params.reason
  change.projectionStatus = EVENT_DERIVED
  change.blockNumber = event.block.number
  change.blockTimestamp = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}

export function handleAddressUnfrozen(event: AddressUnfrozen): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new AddressFreezeChange(id)
  change.token = event.address.toHexString()
  change.account = event.params.account
  change.frozen = false
  change.projectionStatus = EVENT_DERIVED
  change.blockNumber = event.block.number
  change.blockTimestamp = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
