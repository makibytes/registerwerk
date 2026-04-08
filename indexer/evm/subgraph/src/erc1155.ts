import {
  TransferSingle as TransferSingleEvent,
  TransferBatch  as TransferBatchEvent,
  Whitelisted, RemovedFromWhitelist
} from '../generated/templates/EwpgERC1155/EwpgERC1155'
import { Token, Transfer, WhitelistChange, HolderBalance } from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'

function updateBalance(tokenAddress: string, holder: Bytes, tokenId: BigInt,
                       delta: BigInt, block: BigInt, ts: BigInt): void {
  let id = tokenAddress + '-' + holder.toHexString() + '-' + tokenId.toString()
  let hb = HolderBalance.load(id)
  if (hb == null) {
    hb = new HolderBalance(id)
    hb.token   = tokenAddress
    hb.holder  = holder
    hb.balance = BigInt.fromI32(0)
    hb.lastUpdatedBlock     = block
    hb.lastUpdatedTimestamp = ts
  }
  hb.balance = hb.balance.plus(delta)
  hb.lastUpdatedBlock     = block
  hb.lastUpdatedTimestamp = ts
  hb.save()
}

function recordTransfer(tokenAddress: string, from: Bytes, to: Bytes,
                        tokenId: BigInt, amount: BigInt,
                        txHash: Bytes, logIndex: BigInt,
                        block: BigInt, ts: BigInt, suffix: string): void {
  let isMint = from.toHexString() == ADDRESS_ZERO
  let isBurn = to.toHexString()   == ADDRESS_ZERO

  let id = txHash.toHexString() + '-' + logIndex.toString() + suffix
  let transfer = new Transfer(id)
  transfer.token           = tokenAddress
  transfer.tokenType       = 2
  transfer.from            = from
  transfer.to              = to
  transfer.tokenId         = tokenId
  transfer.amount          = amount
  transfer.eventType       = isMint ? 'MINT' : (isBurn ? 'BURN' : 'TRANSFER')
  transfer.blockNumber     = block
  transfer.blockTimestamp  = ts
  transfer.transactionHash = txHash
  transfer.logIndex        = logIndex
  transfer.save()

  if (!isMint) updateBalance(tokenAddress, from, tokenId, amount.neg(), block, ts)
  if (!isBurn) updateBalance(tokenAddress, to,   tokenId, amount,       block, ts)
}

export function handleTransferSingle(event: TransferSingleEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  recordTransfer(
    tokenAddress,
    event.params.from, event.params.to,
    event.params.id, event.params.value,
    event.transaction.hash, event.logIndex,
    event.block.number, event.block.timestamp,
    ''
  )

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO
  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

export function handleTransferBatch(event: TransferBatchEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  let ids    = event.params.ids
  let values = event.params.values

  for (let i = 0; i < ids.length; i++) {
    recordTransfer(
      tokenAddress,
      event.params.from, event.params.to,
      ids[i], values[i],
      event.transaction.hash, event.logIndex,
      event.block.number, event.block.timestamp,
      '-' + i.toString()
    )
  }

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO
  let count  = BigInt.fromI32(ids.length)
  token.totalTransfers = token.totalTransfers.plus(count)
  if (isMint) token.totalMints = token.totalMints.plus(count)
  if (isBurn) token.totalBurns = token.totalBurns.plus(count)
  token.save()
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

export function handleRemovedFromWhitelist(event: RemovedFromWhitelist): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.account
  change.added           = false
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
