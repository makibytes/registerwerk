import { MarketCreated } from '../generated/EwpgRepoMarketFactory/EwpgRepoMarketFactory'
import { EwpgRepoMarket } from '../generated/templates'
import { RepoMarket } from '../generated/schema'

const EVENT_DERIVED = 'EVENT_DERIVED'

/**
 * Handles EwpgRepoMarketFactory.MarketCreated(market, loanToken, collateralToken, lltvBps,
 * priceOracle) — the auto-registration hook for isolated repo/lending markets (finding #7,
 * Phase 7). The emitted address/configuration is provisional event context, not verification
 * of deployment success or runtime code identity.
 */
export function handleMarketCreated(event: MarketCreated): void {
  let marketAddress = event.params.market

  let market = new RepoMarket(marketAddress.toHexString())
  market.projectionStatus = EVENT_DERIVED
  market.factoryReportedLoanToken = event.params.loanToken
  market.factoryReportedCollateralToken = event.params.collateralToken
  market.factoryReportedLltvBps = event.params.lltvBps
  market.factoryReportedPriceOracle = event.params.priceOracle
  market.observedAtBlock = event.block.number
  market.observedAtTimestamp = event.block.timestamp
  market.observedInTx = event.transaction.hash
  market.save()

  EwpgRepoMarket.create(marketAddress)
}
