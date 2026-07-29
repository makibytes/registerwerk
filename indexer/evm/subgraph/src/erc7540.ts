import {
  Transfer as TransferEvent,
  DepositRequested as DepositRequestedEvent,
  RedeemRequested as RedeemRequestedEvent,
  DepositRequestFulfilled as DepositRequestFulfilledEvent,
  RedeemRequestFulfilled as RedeemRequestFulfilledEvent,
  RequestCancelled as RequestCancelledEvent,
  AddressFrozen,
  AddressUnfrozen,
} from '../generated/templates/EwpgERC7540/EwpgERC7540'
import {
  Token,
  Transfer,
  AsyncVaultRequest,
  AsyncVaultRequestEvent,
  AddressFreezeChange,
  HolderBalance,
} from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE = 5
const EVENT_DERIVED = 'EVENT_DERIVED'
const INCOMPLETE = 'INCOMPLETE'

function requestId(vaultAddress: string, id: BigInt): string {
  return vaultAddress + '-' + id.toString()
}

function eventId(txHash: Bytes, logIndex: BigInt): string {
  return txHash.toHexString() + '-' + logIndex.toString()
}

function getOrCreateHolderBalance(tokenAddress: string, holder: Bytes): HolderBalance {
  let id = tokenAddress + '-' + holder.toHexString()
  let balance = HolderBalance.load(id)
  if (balance == null) {
    balance = new HolderBalance(id)
    balance.token = tokenAddress
    balance.holder = holder
    balance.balance = BigInt.zero()
    balance.projectionStatus = EVENT_DERIVED
    balance.lastUpdatedBlock = BigInt.zero()
    balance.lastUpdatedTimestamp = BigInt.zero()
  }
  return balance as HolderBalance
}

function createIncompleteRequest(vaultAddress: string, id: BigInt): AsyncVaultRequest {
  let request = new AsyncVaultRequest(requestId(vaultAddress, id))
  request.vault = vaultAddress
  request.requestId = id
  request.requestType = 'UNKNOWN'
  request.status = 'PENDING'
  request.projectionStatus = INCOMPLETE
  return request
}

function newLifecycleEvent(
  id: string,
  request: AsyncVaultRequest,
  eventKind: string,
  blockNumber: BigInt,
  blockTimestamp: BigInt,
  transactionHash: Bytes,
  logIndex: BigInt,
): AsyncVaultRequestEvent {
  let lifecycle = new AsyncVaultRequestEvent(id)
  lifecycle.request = request.id
  lifecycle.vault = request.vault
  lifecycle.requestId = request.requestId
  lifecycle.eventKind = eventKind
  lifecycle.requestType = request.requestType
  lifecycle.projectionStatus = request.projectionStatus
  lifecycle.blockNumber = blockNumber
  lifecycle.blockTimestamp = blockTimestamp
  lifecycle.transactionHash = transactionHash
  lifecycle.logIndex = logIndex
  return lifecycle
}

/** ERC-20-style share transfer. HolderBalance represents vault shares, not underlying assets. */
export function handleTransfer(event: TransferEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString() == ADDRESS_ZERO
  let transfer = new Transfer(eventId(event.transaction.hash, event.logIndex))
  transfer.token = tokenAddress
  transfer.tokenType = TOKEN_TYPE
  transfer.from = event.params.from
  transfer.to = event.params.to
  transfer.amount = event.params.value
  transfer.eventType = isMint ? 'MINT' : isBurn ? 'BURN' : 'TRANSFER'
  transfer.projectionStatus = EVENT_DERIVED
  transfer.blockNumber = event.block.number
  transfer.blockTimestamp = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex = event.logIndex
  transfer.save()

  if (!isMint) {
    let fromBalance = getOrCreateHolderBalance(tokenAddress, event.params.from)
    fromBalance.balance = fromBalance.balance.minus(event.params.value)
    fromBalance.lastUpdatedBlock = event.block.number
    fromBalance.lastUpdatedTimestamp = event.block.timestamp
    fromBalance.save()
  }
  if (!isBurn) {
    let toBalance = getOrCreateHolderBalance(tokenAddress, event.params.to)
    toBalance.balance = toBalance.balance.plus(event.params.value)
    toBalance.lastUpdatedBlock = event.block.number
    toBalance.lastUpdatedTimestamp = event.block.timestamp
    toBalance.save()
  }

  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

export function handleDepositRequested(event: DepositRequestedEvent): void {
  let vaultAddress = event.address.toHexString()
  let id = requestId(vaultAddress, event.params.requestId)
  let request = AsyncVaultRequest.load(id)
  if (request == null) {
    request = new AsyncVaultRequest(id)
    request.vault = vaultAddress
    request.requestId = event.params.requestId
    request.requestType = 'DEPOSIT'
    request.status = 'PENDING'
    request.projectionStatus = EVENT_DERIVED
  } else {
    request.projectionStatus = INCOMPLETE
  }
  request.controller = event.params.controller
  request.owner = event.params.owner
  request.assets = event.params.assets
  request.requestedAtBlock = event.block.number
  request.requestedAtTimestamp = event.block.timestamp
  request.save()

  let lifecycle = newLifecycleEvent(
    eventId(event.transaction.hash, event.logIndex),
    request as AsyncVaultRequest,
    'REQUESTED',
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
    event.logIndex,
  )
  lifecycle.actor = event.params.controller
  lifecycle.owner = event.params.owner
  lifecycle.assets = event.params.assets
  lifecycle.save()
}

export function handleRedeemRequested(event: RedeemRequestedEvent): void {
  let vaultAddress = event.address.toHexString()
  let id = requestId(vaultAddress, event.params.requestId)
  let request = AsyncVaultRequest.load(id)
  if (request == null) {
    request = new AsyncVaultRequest(id)
    request.vault = vaultAddress
    request.requestId = event.params.requestId
    request.requestType = 'REDEEM'
    request.status = 'PENDING'
    request.projectionStatus = EVENT_DERIVED
  } else {
    request.projectionStatus = INCOMPLETE
  }
  request.controller = event.params.controller
  request.owner = event.params.owner
  request.shares = event.params.shares
  request.requestedAtBlock = event.block.number
  request.requestedAtTimestamp = event.block.timestamp
  request.save()

  let lifecycle = newLifecycleEvent(
    eventId(event.transaction.hash, event.logIndex),
    request as AsyncVaultRequest,
    'REQUESTED',
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
    event.logIndex,
  )
  lifecycle.actor = event.params.controller
  lifecycle.owner = event.params.owner
  lifecycle.shares = event.params.shares
  lifecycle.save()
}

export function handleDepositRequestFulfilled(event: DepositRequestFulfilledEvent): void {
  let vaultAddress = event.address.toHexString()
  let id = requestId(vaultAddress, event.params.requestId)
  let request = AsyncVaultRequest.load(id)
  if (request == null) request = createIncompleteRequest(vaultAddress, event.params.requestId)
  if (request.requestType == 'UNKNOWN') request.requestType = 'DEPOSIT'
  if (request.requestType != 'DEPOSIT' || request.status != 'PENDING') request.projectionStatus = INCOMPLETE
  request.status = 'FULFILLED'
  request.assets = event.params.assets
  request.shares = event.params.shares
  request.navAtFulfill = event.params.navAtFulfill
  request.completedAtBlock = event.block.number
  request.completedAtTimestamp = event.block.timestamp
  request.save()

  let lifecycle = newLifecycleEvent(
    eventId(event.transaction.hash, event.logIndex),
    request as AsyncVaultRequest,
    'FULFILLED',
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
    event.logIndex,
  )
  lifecycle.owner = request.owner
  lifecycle.assets = event.params.assets
  lifecycle.shares = event.params.shares
  lifecycle.navAtFulfill = event.params.navAtFulfill
  lifecycle.save()
}

export function handleRedeemRequestFulfilled(event: RedeemRequestFulfilledEvent): void {
  let vaultAddress = event.address.toHexString()
  let id = requestId(vaultAddress, event.params.requestId)
  let request = AsyncVaultRequest.load(id)
  if (request == null) request = createIncompleteRequest(vaultAddress, event.params.requestId)
  if (request.requestType == 'UNKNOWN') request.requestType = 'REDEEM'
  if (request.requestType != 'REDEEM' || request.status != 'PENDING') request.projectionStatus = INCOMPLETE
  request.status = 'FULFILLED'
  request.shares = event.params.shares
  request.assets = event.params.assets
  request.navAtFulfill = event.params.navAtFulfill
  request.completedAtBlock = event.block.number
  request.completedAtTimestamp = event.block.timestamp
  request.save()

  let lifecycle = newLifecycleEvent(
    eventId(event.transaction.hash, event.logIndex),
    request as AsyncVaultRequest,
    'FULFILLED',
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
    event.logIndex,
  )
  lifecycle.owner = request.owner
  lifecycle.assets = event.params.assets
  lifecycle.shares = event.params.shares
  lifecycle.navAtFulfill = event.params.navAtFulfill
  lifecycle.save()
}

/** RequestCancelled omits request type, so the prior provisional request supplies context when present. */
export function handleRequestCancelled(event: RequestCancelledEvent): void {
  let vaultAddress = event.address.toHexString()
  let id = requestId(vaultAddress, event.params.requestId)
  let request = AsyncVaultRequest.load(id)
  if (request == null) request = createIncompleteRequest(vaultAddress, event.params.requestId)
  if (request.status != 'PENDING') request.projectionStatus = INCOMPLETE
  request.status = 'CANCELLED'
  request.cancelledBy = event.params.by
  request.completedAtBlock = event.block.number
  request.completedAtTimestamp = event.block.timestamp
  request.save()

  let lifecycle = newLifecycleEvent(
    eventId(event.transaction.hash, event.logIndex),
    request as AsyncVaultRequest,
    'CANCELLED',
    event.block.number,
    event.block.timestamp,
    event.transaction.hash,
    event.logIndex,
  )
  lifecycle.actor = event.params.by
  lifecycle.owner = request.owner
  lifecycle.save()
}

export function handleAddressFrozen(event: AddressFrozen): void {
  let change = new AddressFreezeChange(eventId(event.transaction.hash, event.logIndex))
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
  let change = new AddressFreezeChange(eventId(event.transaction.hash, event.logIndex))
  change.token = event.address.toHexString()
  change.account = event.params.account
  change.frozen = false
  change.projectionStatus = EVENT_DERIVED
  change.blockNumber = event.block.number
  change.blockTimestamp = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
