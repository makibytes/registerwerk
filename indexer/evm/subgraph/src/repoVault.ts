import {
  Allocated as AllocatedEvent,
  CapIncreaseSubmitted as CapIncreaseSubmittedEvent,
  Deallocated as DeallocatedEvent,
  Deposit as DepositEvent,
  MarketAddSubmitted as MarketAddSubmittedEvent,
  MarketAdded as MarketAddedEvent,
  MarketCapUpdated as MarketCapUpdatedEvent,
  MarketRemoved as MarketRemovedEvent,
  PendingRevoked as PendingRevokedEvent,
  Transfer as TransferEvent,
  Withdraw as WithdrawEvent,
} from '../generated/EwpgRepoVault/EwpgRepoVault'
import { RepoVault, RepoVaultEvent, RepoVaultMarketAllocation } from '../generated/schema'
import { Address, BigInt, Bytes, ethereum } from '@graphprotocol/graph-ts'

const EVENT_DERIVED = 'EVENT_DERIVED'
const INCOMPLETE = 'INCOMPLETE'

function getOrCreateVault(event: ethereum.Event): RepoVault {
  let id = event.address.toHexString()
  let vault = RepoVault.load(id)
  if (vault == null) {
    vault = new RepoVault(id)
    vault.netAllocationCashFlow = BigInt.zero()
    vault.totalDepositedAssets = BigInt.zero()
    vault.totalWithdrawnAssets = BigInt.zero()
    // A configured static address does not prove that indexing began at deployment.
    vault.projectionStatus = INCOMPLETE
    vault.firstSeenBlock = event.block.number
  }
  vault.lastUpdatedBlock = event.block.number
  vault.lastUpdatedTimestamp = event.block.timestamp
  return vault as RepoVault
}

function allocationId(vault: string, market: Address): string {
  return vault + '-' + market.toHexString()
}

function getOrCreateIncompleteAllocation(
  vault: RepoVault,
  market: Address,
  event: ethereum.Event,
): RepoVaultMarketAllocation {
  let id = allocationId(vault.id, market)
  let allocation = RepoVaultMarketAllocation.load(id)
  if (allocation == null) {
    allocation = new RepoVaultMarketAllocation(id)
    allocation.vault = vault.id
    allocation.market = market
    allocation.enabled = false
    allocation.cap = BigInt.zero()
    allocation.netAllocationCashFlow = BigInt.zero()
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.lastUpdatedBlock = event.block.number
  allocation.lastUpdatedTimestamp = event.block.timestamp
  return allocation as RepoVaultMarketAllocation
}

function newEvent(event: ethereum.Event, vault: RepoVault, eventType: string): RepoVaultEvent {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let record = new RepoVaultEvent(id)
  record.vault = vault.id
  record.eventType = eventType
  record.projectionStatus = EVENT_DERIVED
  record.blockNumber = event.block.number
  record.blockTimestamp = event.block.timestamp
  record.transactionHash = event.transaction.hash
  record.logIndex = event.logIndex
  return record
}

function saveEvent(record: RepoVaultEvent, vault: RepoVault): void {
  vault.save()
  record.save()
}

function clearPending(allocation: RepoVaultMarketAllocation): void {
  allocation.pendingCap = null
  allocation.pendingValidAt = null
}

/** Curator proposed a new market; it becomes allocatable only via MarketAdded after the timelock. */
export function handleMarketAddSubmitted(event: MarketAddSubmittedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  if (allocation.enabled) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.pendingCap = event.params.capWad
  allocation.pendingValidAt = event.params.validAt
  allocation.save()

  let record = newEvent(event, vault, 'MARKET_ADD_SUBMITTED')
  record.market = event.params.market
  record.cap = event.params.capWad
  record.validAt = event.params.validAt
  saveEvent(record, vault)
}

/** Curator proposed a cap increase; it takes effect only via MarketCapUpdated after the timelock. */
export function handleCapIncreaseSubmitted(event: CapIncreaseSubmittedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  if (!allocation.enabled) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.pendingCap = event.params.capWad
  allocation.pendingValidAt = event.params.validAt
  allocation.save()

  let record = newEvent(event, vault, 'CAP_INCREASE_SUBMITTED')
  record.market = event.params.market
  record.cap = event.params.capWad
  record.validAt = event.params.validAt
  saveEvent(record, vault)
}

/** A pending addition or cap increase was withdrawn by the curator or operator org. */
export function handlePendingRevoked(event: PendingRevokedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  clearPending(allocation)
  allocation.save()

  let record = newEvent(event, vault, 'PENDING_REVOKED')
  record.market = event.params.market
  record.actor = event.params.by
  saveEvent(record, vault)
}

export function handleMarketAdded(event: MarketAddedEvent): void {
  let vault = getOrCreateVault(event)
  let id = allocationId(vault.id, event.params.market)
  let allocation = RepoVaultMarketAllocation.load(id)
  if (allocation == null) {
    allocation = new RepoVaultMarketAllocation(id)
    allocation.vault = vault.id
    allocation.market = event.params.market
    allocation.netAllocationCashFlow = BigInt.zero()
    // The first observed event does not establish deployment provenance or full history.
    allocation.projectionStatus = INCOMPLETE
  } else if (allocation.enabled || allocation.projectionStatus == INCOMPLETE) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.enabled = true
  allocation.cap = event.params.capWad
  clearPending(allocation)
  allocation.lastUpdatedBlock = event.block.number
  allocation.lastUpdatedTimestamp = event.block.timestamp
  allocation.save()

  let record = newEvent(event, vault, 'MARKET_ADDED')
  record.market = event.params.market
  record.cap = event.params.capWad
  saveEvent(record, vault)
}

export function handleMarketCapUpdated(event: MarketCapUpdatedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  if (!allocation.enabled) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.cap = event.params.capWad
  // Both acceptCapIncrease and a direct cap decrease delete any pending change on-chain.
  clearPending(allocation)
  allocation.save()

  let record = newEvent(event, vault, 'MARKET_CAP_UPDATED')
  record.market = event.params.market
  record.cap = event.params.capWad
  saveEvent(record, vault)
}

export function handleMarketRemoved(event: MarketRemovedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  if (!allocation.enabled) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.enabled = false
  clearPending(allocation)
  allocation.save()

  let record = newEvent(event, vault, 'MARKET_REMOVED')
  record.market = event.params.market
  saveEvent(record, vault)
}

export function handleAllocated(event: AllocatedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  if (!allocation.enabled) {
    allocation.projectionStatus = INCOMPLETE
    vault.projectionStatus = INCOMPLETE
  }
  allocation.netAllocationCashFlow = allocation.netAllocationCashFlow.plus(event.params.amount)
  vault.netAllocationCashFlow = vault.netAllocationCashFlow.plus(event.params.amount)
  allocation.save()

  let record = newEvent(event, vault, 'ALLOCATED')
  record.market = event.params.market
  record.amount = event.params.amount
  saveEvent(record, vault)
}

export function handleDeallocated(event: DeallocatedEvent): void {
  let vault = getOrCreateVault(event)
  let allocation = getOrCreateIncompleteAllocation(vault, event.params.market, event)
  // Allocated/Deallocated amounts are signed cash flows. Interest and losses can make the
  // cumulative value negative or positive; neither sign establishes an inconsistency.
  allocation.netAllocationCashFlow = allocation.netAllocationCashFlow.minus(event.params.amount)
  vault.netAllocationCashFlow = vault.netAllocationCashFlow.minus(event.params.amount)
  allocation.save()

  let record = newEvent(event, vault, 'DEALLOCATED')
  record.market = event.params.market
  record.amount = event.params.amount
  saveEvent(record, vault)
}

export function handleDeposit(event: DepositEvent): void {
  let vault = getOrCreateVault(event)
  vault.totalDepositedAssets = vault.totalDepositedAssets.plus(event.params.assets)
  let record = newEvent(event, vault, 'DEPOSIT')
  record.actor = event.params.sender
  record.owner = event.params.owner
  record.assets = event.params.assets
  record.shares = event.params.shares
  saveEvent(record, vault)
}

export function handleWithdraw(event: WithdrawEvent): void {
  let vault = getOrCreateVault(event)
  vault.totalWithdrawnAssets = vault.totalWithdrawnAssets.plus(event.params.assets)
  let record = newEvent(event, vault, 'WITHDRAW')
  record.actor = event.params.sender
  record.receiver = event.params.receiver
  record.owner = event.params.owner
  record.assets = event.params.assets
  record.shares = event.params.shares
  saveEvent(record, vault)
}

export function handleTransfer(event: TransferEvent): void {
  let vault = getOrCreateVault(event)
  let record = newEvent(event, vault, 'SHARE_TRANSFER')
  record.from = event.params.from
  record.to = event.params.to
  record.amount = event.params.value
  saveEvent(record, vault)
}
