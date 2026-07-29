import {
  LegLocked as LegLockedEvent,
  TradeSettled as TradeSettledEvent,
  TradeCancelled as TradeCancelledEvent,
  Paused as PausedEvent,
  Unpaused as UnpausedEvent,
} from '../generated/DvpSettlement/DvpSettlement'
import { DvpSettlementEvent } from '../generated/schema'

/**
 * Lifecycle-event ingestion for the operator-provided DvpSettlement rail (finding #6,
 * Phase 8) — previously this contract's cash-leg transfers (escrow lock, same-transaction settlement,
 * expiry/counterparty cancellation) were invisible to the backend, unlike backend-initiated
 * forceTransfer paths which go through Travel Rule evaluation and the audit trail.
 */
function newEvent(txHash: string, logIndex: string, eventType: string): DvpSettlementEvent {
  let id = txHash + '-' + logIndex
  let e = new DvpSettlementEvent(id)
  e.eventType = eventType
  e.projectionStatus = 'EVENT_DERIVED'
  return e
}

export function handleLegLocked(event: LegLockedEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'LEG_LOCKED')
  e.tradeId = event.params.tradeId
  e.seller = event.params.seller
  e.buyer = event.params.buyer
  e.lockedLeg = event.params.lockedLeg == 0 ? 'ASSET' : 'PAYMENT'
  e.assetToken = event.params.assetToken
  e.assetAmount = event.params.assetAmount
  e.paymentToken = event.params.paymentToken
  e.paymentAmount = event.params.paymentAmount
  e.expiry = event.params.expiry
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleTradeSettled(event: TradeSettledEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'TRADE_SETTLED')
  e.tradeId = event.params.tradeId
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleTradeCancelled(event: TradeCancelledEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'TRADE_CANCELLED')
  e.tradeId = event.params.tradeId
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handlePaused(event: PausedEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'PAUSED')
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleUnpaused(event: UnpausedEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'UNPAUSED')
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
