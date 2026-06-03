import {
  Transfer as TransferEvent,
  DepositRequest as DepositRequestEvent,
  RedeemRequest as RedeemRequestEvent,
  DepositClaimable as DepositClaimableEvent,
  RedeemClaimable as RedeemClaimableEvent,
} from '../generated/templates/EwpgERC7540/EwpgERC7540'
import { Token, Transfer, DepositRequest, RedeemRequest, HolderBalance } from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE   = 5  // ERC-7540 async vault

function getOrCreateHolderBalance(tokenAddress: string, holder: Bytes): HolderBalance {
  let id = tokenAddress + '-' + holder.toHexString()
  let hb = HolderBalance.load(id)
  if (hb == null) {
    hb = new HolderBalance(id)
    hb.token   = tokenAddress
    hb.holder  = holder
    hb.balance = BigInt.fromI32(0)
    hb.lastUpdatedBlock     = BigInt.fromI32(0)
    hb.lastUpdatedTimestamp = BigInt.fromI32(0)
  }
  return hb as HolderBalance
}

/** ERC-20-style share token transfer (mint on deposit fulfillment, burn on redeem). */
export function handleTransfer(event: TransferEvent): void {
  let tokenAddress = event.address.toHexString()
  let token = Token.load(tokenAddress)
  if (token == null) return

  let isMint = event.params.from.toHexString() == ADDRESS_ZERO
  let isBurn = event.params.to.toHexString()   == ADDRESS_ZERO
  let eventType = isMint ? 'MINT' : (isBurn ? 'BURN' : 'TRANSFER')

  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let transfer = new Transfer(id)
  transfer.token           = tokenAddress
  transfer.tokenType       = TOKEN_TYPE
  transfer.from            = event.params.from
  transfer.to              = event.params.to
  transfer.amount          = event.params.value
  transfer.eventType       = eventType
  transfer.blockNumber     = event.block.number
  transfer.blockTimestamp  = event.block.timestamp
  transfer.transactionHash = event.transaction.hash
  transfer.logIndex        = event.logIndex
  transfer.save()

  if (!isMint) {
    let fromBalance = getOrCreateHolderBalance(tokenAddress, event.params.from)
    fromBalance.balance = fromBalance.balance.minus(event.params.value)
    fromBalance.lastUpdatedBlock     = event.block.number
    fromBalance.lastUpdatedTimestamp = event.block.timestamp
    fromBalance.save()
  }
  if (!isBurn) {
    let toBalance = getOrCreateHolderBalance(tokenAddress, event.params.to)
    toBalance.balance = toBalance.balance.plus(event.params.value)
    toBalance.lastUpdatedBlock     = event.block.number
    toBalance.lastUpdatedTimestamp = event.block.timestamp
    toBalance.save()
  }

  token.totalTransfers = token.totalTransfers.plus(BigInt.fromI32(1))
  if (isMint) token.totalMints = token.totalMints.plus(BigInt.fromI32(1))
  if (isBurn) token.totalBurns = token.totalBurns.plus(BigInt.fromI32(1))
  token.save()
}

/** Investor submits an async deposit request (assets locked, shares pending). */
export function handleDepositRequest(event: DepositRequestEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let req = new DepositRequest(id)
  req.token           = event.address.toHexString()
  req.controller      = event.params.controller
  req.owner           = event.params.owner
  req.requestId       = event.params.requestId
  req.assets          = event.params.assets
  req.requestKind     = 'DEPOSIT_REQUEST'
  req.blockNumber     = event.block.number
  req.blockTimestamp  = event.block.timestamp
  req.transactionHash = event.transaction.hash
  req.save()
}

/** Investor submits an async redeem request (shares locked, assets pending). */
export function handleRedeemRequest(event: RedeemRequestEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let req = new RedeemRequest(id)
  req.token           = event.address.toHexString()
  req.controller      = event.params.controller
  req.owner           = event.params.owner
  req.requestId       = event.params.requestId
  req.assets          = event.params.assets
  req.requestKind     = 'REDEEM_REQUEST'
  req.blockNumber     = event.block.number
  req.blockTimestamp  = event.block.timestamp
  req.transactionHash = event.transaction.hash
  req.save()
}

/** Vault operator fulfills a deposit request — shares now claimable. */
export function handleDepositClaimable(event: DepositClaimableEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let req = new DepositRequest(id)
  req.token           = event.address.toHexString()
  req.controller      = event.params.controller
  req.owner           = event.params.controller  // claimable events may not emit owner separately
  req.requestId       = event.params.requestId
  req.assets          = event.params.assets
  req.requestKind     = 'DEPOSIT_CLAIMABLE'
  req.blockNumber     = event.block.number
  req.blockTimestamp  = event.block.timestamp
  req.transactionHash = event.transaction.hash
  req.save()
}

/** Vault operator fulfills a redeem request — assets now claimable. */
export function handleRedeemClaimable(event: RedeemClaimableEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let req = new RedeemRequest(id)
  req.token           = event.address.toHexString()
  req.controller      = event.params.controller
  req.owner           = event.params.controller
  req.requestId       = event.params.requestId
  req.assets          = event.params.assets
  req.requestKind     = 'REDEEM_CLAIMABLE'
  req.blockNumber     = event.block.number
  req.blockTimestamp  = event.block.timestamp
  req.transactionHash = event.transaction.hash
  req.save()
}
