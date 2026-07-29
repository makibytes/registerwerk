import {
  ConfidentialTransfer as ConfidentialTransferEvent,
  ConfidentialMint as ConfidentialMintEvent,
  ConfidentialBurn as ConfidentialBurnEvent,
} from '../generated/templates/ConfidentialERC20/ConfidentialERC20'
import { ConfidentialToken, ConfidentialTokenEvent } from '../generated/schema'

/**
 * Lifecycle-event ingestion for confidential (Zama fhEVM) tokens (finding #8, Phase 9) —
 * previously no event-log ingestion existed for these contracts at all, so a holder-initiated
 * ConfidentialTransfer was invisible to the backend and could never be evaluated for Travel Rule.
 * `handle` stays an opaque FHE ciphertext handle here — only the backend's registered-viewer
 * operator-decrypt call (via the Zama Relayer) can ever turn it into a real amount.
 */
function newEvent(tokenId: string, txHash: string, logIndex: string, eventType: string): ConfidentialTokenEvent {
  let id = txHash + '-' + logIndex
  let e = new ConfidentialTokenEvent(id)
  e.token = tokenId
  e.eventType = eventType
  e.projectionStatus = 'EVENT_DERIVED'
  return e
}

export function handleConfidentialTransfer(event: ConfidentialTransferEvent): void {
  let token = ConfidentialToken.load(event.address.toHexString())
  if (token == null) return

  let e = newEvent(token.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'TRANSFER')
  e.from = event.params.from
  e.to = event.params.to
  e.handle = event.params.handle
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleConfidentialMint(event: ConfidentialMintEvent): void {
  let token = ConfidentialToken.load(event.address.toHexString())
  if (token == null) return

  let e = newEvent(token.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'MINT')
  e.to = event.params.to
  e.handle = event.params.handle
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}

export function handleConfidentialBurn(event: ConfidentialBurnEvent): void {
  let token = ConfidentialToken.load(event.address.toHexString())
  if (token == null) return

  let e = newEvent(token.id, event.transaction.hash.toHexString(), event.logIndex.toString(), 'BURN')
  e.from = event.params.from
  e.handle = event.params.handle
  e.blockNumber = event.block.number
  e.blockTimestamp = event.block.timestamp
  e.transactionHash = event.transaction.hash
  e.logIndex = event.logIndex
  e.save()
}
