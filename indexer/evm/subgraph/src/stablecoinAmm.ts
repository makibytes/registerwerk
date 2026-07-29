import {
  LiquidityAdded as LiquidityAddedEvent,
  LiquidityRemoved as LiquidityRemovedEvent,
  Swapped as SwappedEvent,
} from '../generated/StablecoinAmm/StablecoinAmm'
import { StablecoinAmmEvent } from '../generated/schema'
import { Address } from '@graphprotocol/graph-ts'

/**
 * Lifecycle-event ingestion for StablecoinAmm (finding #6, Phase 8) — previously this
 * contract's swaps and liquidity moves were invisible to the backend, unlike
 * backend-initiated forceTransfer paths which go through Travel Rule evaluation and the
 * audit trail. One pool per stablecoin pair is deployed ad hoc (no on-chain factory spawns
 * them), so this is a static data source with a known address per deployed pool.
 */
function newEvent(poolAddress: Address, txHash: string, logIndex: string, eventType: string): StablecoinAmmEvent {
  let id = txHash + '-' + logIndex
  let e = new StablecoinAmmEvent(id)
  e.pool = poolAddress
  e.eventType = eventType
  e.projectionStatus = 'EVENT_DERIVED'
  return e
}

export function handleLiquidityAdded(event: LiquidityAddedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'LIQUIDITY_ADDED')
  e.actor = event.params.provider
  e.amountA = event.params.amountA
  e.amountB = event.params.amountB
  e.shares = event.params.shares
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleLiquidityRemoved(event: LiquidityRemovedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'LIQUIDITY_REMOVED')
  e.actor = event.params.provider
  e.amountA = event.params.amountA
  e.amountB = event.params.amountB
  e.shares = event.params.shares
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleSwapped(event: SwappedEvent): void {
  let e = newEvent(event.address, event.transaction.hash.toHexString(), event.logIndex.toString(), 'SWAPPED')
  e.actor = event.params.trader
  e.tokenIn = event.params.tokenIn
  e.amountA = event.params.amountIn
  e.amountB = event.params.amountOut
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
