import {
  Supplied as SuppliedEvent,
  Withdrawn as WithdrawnEvent,
  Borrowed as BorrowedEvent,
  Repaid as RepaidEvent,
  Liquidated as LiquidatedEvent,
  BadDebtRecognized as BadDebtRecognizedEvent,
  CollateralReconciled as CollateralReconciledEvent,
} from '../generated/templates/EwpgRepoMarket/EwpgRepoMarket'
import { RepoMarket, RepoMarketEvent } from '../generated/schema'

/**
 * Lifecycle-event ingestion for {@link EwpgRepoMarket} instances  —
 * previously no event-log ingestion existed for this contract pair at all, so
 * {@code LendingPositionService.refreshPosition} could not distinguish a voluntarily-repaid
 * position from a liquidated one (both collapsed to the same on-chain read state once debt
 * reached zero). Every event here becomes one {@link RepoMarketEvent} row with an `eventType`
 * discriminator, mirroring the `Transfer`/`eventType` pattern already used for
 * ERC-20/4626/7540 in this subgraph.
 */
function newEvent(marketAddress: string, txHash: string, logIndex: string, eventType: string): RepoMarketEvent {
  let id = txHash + '-' + logIndex
  let e = new RepoMarketEvent(id)
  e.market = marketAddress
  e.eventType = eventType
  e.projectionStatus = 'EVENT_DERIVED'
  return e
}

export function handleSupplied(event: SuppliedEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'SUPPLIED')
  e.actor = event.params.lender
  e.amount = event.params.amount
  e.scaledAmount = event.params.scaledAmount
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleWithdrawn(event: WithdrawnEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'WITHDRAWN')
  e.actor = event.params.lender
  e.amount = event.params.amount
  e.scaledAmount = event.params.scaledAmount
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleBorrowed(event: BorrowedEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'BORROWED')
  e.actor = event.params.borrower
  e.amount = event.params.borrowAmount
  e.collateralAmount = event.params.collateralAmount
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

/** Also fired for a full repay to zero debt — the case {@code LendingPositionService} needs to
 *  distinguish from {@link handleLiquidated}'s zero-debt-via-liquidation case. */
export function handleRepaid(event: RepaidEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'REPAID')
  e.actor = event.params.borrower
  e.amount = event.params.repayAmount
  e.collateralAmount = event.params.collateralReturned
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleLiquidated(event: LiquidatedEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'LIQUIDATED')
  e.actor = event.params.borrower
  e.liquidator = event.params.liquidator
  e.amount = event.params.debtRepaid
  e.collateralAmount = event.params.collateralSeized
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

/** Fired when a borrower's collateral is fully exhausted and remaining debt is written off
 *  ( on-chain fix) — always immediately preceded, in the same
 *  transaction, by the LIQUIDATED or CollateralReconciled event that triggered it. */
export function handleBadDebtRecognized(event: BadDebtRecognizedEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'BAD_DEBT_RECOGNIZED')
  e.actor = event.params.borrower
  e.amount = event.params.writtenOffDebt
  e.collateralAmount = event.params.lossToDepositors
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleCollateralReconciled(event: CollateralReconciledEvent): void {
  let market = RepoMarket.load(event.address.toHexString())
  if (market == null) return

  let e = newEvent(market.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'COLLATERAL_RECONCILED')
  e.actor = event.params.borrower
  e.collateralAmount = event.params.newCollateral
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
