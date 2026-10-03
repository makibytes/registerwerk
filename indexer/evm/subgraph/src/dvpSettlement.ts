import {
  LegLocked as LegLockedEvent,
  TradeSettled as TradeSettledEvent,
  TradeCancelled as TradeCancelledEvent,
  TradeForceCancelled as TradeForceCancelledEvent,
  ForceCancelProposed as ForceCancelProposedEvent,
  ForceCancelWithdrawn as ForceCancelWithdrawnEvent,
  Paused as PausedEvent,
  Unpaused as UnpausedEvent,
} from '../generated/DvpSettlement/DvpSettlement'
import { DvpSettlementEvent } from '../generated/schema'

/**
 * Lifecycle-event ingestion for the operator-provided DvpSettlement rail, including escrow,
 * settlement, expiry, counterparty cancellation, and operator force-cancellation under a legal
 * order. tradeId is the contract-derived, locker-namespaced id (see DvpSettlement.tradeIdFor).
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

/** Operator released the escrowed leg to a destination named in a legal order. */
export function handleTradeForceCancelled(event: TradeForceCancelledEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'TRADE_FORCE_CANCELLED')
  e.tradeId = event.params.tradeId
  e.forcedDestination = event.params.to
  e.legalBasis = event.params.legalBasis
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

/** A legal-order release to a destination outside the trade was proposed; executable from
 *  `executableAt` (timelock) unless withdrawn or mooted by settlement/cancellation first. */
export function handleForceCancelProposed(event: ForceCancelProposedEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'FORCE_CANCEL_PROPOSED')
  e.tradeId = event.params.tradeId
  e.forcedDestination = event.params.to
  e.executableAt = event.params.executableAt
  e.legalBasis = event.params.legalBasis
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleForceCancelWithdrawn(event: ForceCancelWithdrawnEvent): void {
  let e = newEvent(event.transaction.hash.toHexString(), event.logIndex.toString(), 'FORCE_CANCEL_WITHDRAWN')
  e.tradeId = event.params.tradeId
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
