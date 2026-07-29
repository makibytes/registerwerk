import {
  BondSubscribed as BondSubscribedEvent,
  CouponPaid as CouponPaidEvent,
  BondRedeemed as BondRedeemedEvent,
  DeskPaused as DeskPausedEvent,
  DeskUnpaused as DeskUnpausedEvent,
} from '../generated/EwpgBondDesk/EwpgBondDesk'
import { BondDeskEvent } from '../generated/schema'
import { Address } from '@graphprotocol/graph-ts'

/**
 * Lifecycle-event ingestion for EwpgBondDesk (finding #6, Phase 8) — previously this
 * contract's primary-market subscription and coupon/redemption cash legs were invisible to
 * the backend, unlike backend-initiated forceTransfer paths which go through Travel Rule
 * evaluation and the audit trail. One EwpgBondDesk instance is deployed per bond issuance
 * (no on-chain factory spawns them), so this is a static data source with a known address —
 * a new bond desk needs its own data-source entry added at deploy time.
 */
function newEvent(deskAddress: Address, txHash: string, logIndex: string, eventType: string): BondDeskEvent {
  let id = txHash + '-' + logIndex
  let e = new BondDeskEvent(id)
  e.desk = deskAddress
  e.eventType = eventType
  e.projectionStatus = 'EVENT_DERIVED'
  return e
}

export function handleBondSubscribed(event: BondSubscribedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'SUBSCRIBED')
  e.actor = event.params.investor
  e.amount = event.params.amount
  e.paidOrPrincipal = event.params.paid
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleCouponPaid(event: CouponPaidEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'COUPON_PAID')
  e.actor = event.params.holder
  e.period = event.params.period
  e.paidOrPrincipal = event.params.amount
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleBondRedeemed(event: BondRedeemedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'REDEEMED')
  e.actor = event.params.holder
  e.amount = event.params.amount
  e.paidOrPrincipal = event.params.principal
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleDeskPaused(event: DeskPausedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'PAUSED')
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleDeskUnpaused(event: DeskUnpausedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'UNPAUSED')
  e.actor = event.params.by
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
