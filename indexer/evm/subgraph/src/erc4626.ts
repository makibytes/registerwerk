import {
  Transfer as TransferEvent,
  Deposit as DepositEvent,
  Withdraw as WithdrawEvent,
  AddressFrozen,
} from '../generated/templates/EwpgERC4626/EwpgERC4626'
import { Token, Transfer, VaultDeposit, VaultWithdraw, WhitelistChange, HolderBalance } from '../generated/schema'
import { BigInt, Bytes } from '@graphprotocol/graph-ts'

const ADDRESS_ZERO = '0x0000000000000000000000000000000000000000'
const TOKEN_TYPE   = 4  // ERC-4626 sync vault

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

/** ERC-20-style share token transfer (includes mint/burn from deposits/withdrawals). */
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

/** Investor deposits underlying assets, receives shares immediately. */
export function handleDeposit(event: DepositEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let deposit = new VaultDeposit(id)
  deposit.token           = event.address.toHexString()
  deposit.caller          = event.params.caller
  deposit.owner           = event.params.owner
  deposit.assets          = event.params.assets
  deposit.shares          = event.params.shares
  deposit.blockNumber     = event.block.number
  deposit.blockTimestamp  = event.block.timestamp
  deposit.transactionHash = event.transaction.hash
  deposit.save()
}

/** Investor redeems shares for underlying assets. */
export function handleWithdraw(event: WithdrawEvent): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let withdraw = new VaultWithdraw(id)
  withdraw.token           = event.address.toHexString()
  withdraw.caller          = event.params.caller
  withdraw.receiver        = event.params.receiver
  withdraw.owner           = event.params.owner
  withdraw.assets          = event.params.assets
  withdraw.shares          = event.params.shares
  withdraw.blockNumber     = event.block.number
  withdraw.blockTimestamp  = event.block.timestamp
  withdraw.transactionHash = event.transaction.hash
  withdraw.save()
}

export function handleAddressFrozen(event: AddressFrozen): void {
  let id = event.transaction.hash.toHexString() + '-' + event.logIndex.toString()
  let change = new WhitelistChange(id)
  change.token           = event.address.toHexString()
  change.account         = event.params.addr
  change.added           = !event.params.frozen
  change.blockNumber     = event.block.number
  change.blockTimestamp  = event.block.timestamp
  change.transactionHash = event.transaction.hash
  change.save()
}
