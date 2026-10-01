package de.makibytes.registerwerk.lending.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Collections;
import java.util.List;

/**
 * Raw {@code eth_call} reads against a deployed {@code EwpgRepoMarket} — the only thing this
 * module ever does on-chain (no writes: pledging/borrowing/repaying happen from the customer's
 * own wallet, never routed through the backend). Mirrors the manual
 * {@code Function}/{@code FunctionEncoder}/{@code FunctionReturnDecoder} pattern already used by
 * {@code asset.internal.TermSheetOnChainFetchService} — there is no generated Java contract
 * wrapper for {@code EwpgRepoMarket} (no web3j codegen step wired for it), so calls are built by
 * hand exactly like every other reference to a bespoke Ewpg* contract in this codebase.
 */
@Component
class RepoMarketOnchainReader {

    private final BlockchainClientRegistry clientRegistry;

    RepoMarketOnchainReader(BlockchainClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    /**
     * Scope of a block-pinned read (P4B-8): every {@code eth_call} of this thread between
     * {@link #pinBlock} and {@code close()} runs at one block number obtained once, so a multi-call
     * quote or position refresh cannot mix values from different nodes/heights. Nested pins restore the
     * outer one on close.
     */
    interface Pin extends AutoCloseable {
        @Override void close();
    }

    private static final ThreadLocal<BigInteger> PINNED_BLOCK = new ThreadLocal<>();

    Pin pinBlock(String chainIdentifier) {
        BigInteger previous = PINNED_BLOCK.get();
        try {
            BigInteger head = clientRegistry.getEvmClientByIdentifier(chainIdentifier)
                    .ethBlockNumber().send().getBlockNumber();
            PINNED_BLOCK.set(head);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read the block number of " + chainIdentifier, e);
        }
        return () -> {
            if (previous == null) PINNED_BLOCK.remove(); else PINNED_BLOCK.set(previous);
        };
    }

    private static DefaultBlockParameter readBlock() {
        BigInteger pinned = PINNED_BLOCK.get();
        return pinned != null ? DefaultBlockParameter.valueOf(pinned) : DefaultBlockParameterName.LATEST;
    }

    /** {@code EwpgRepoMarket.debtOf(address) returns (uint256)} — actual, index-scaled debt. */
    BigInteger debtOf(String chainIdentifier, String marketAddress, String wallet) {
        return callUint256(chainIdentifier, marketAddress, "debtOf", List.of(new Address(wallet)));
    }

    /**
     * {@code EwpgRepoMarket.healthFactor(address) returns (uint256 factor, bool priceReliable)}
     * — WAD-scaled; {@code type(uint256).max} when the wallet has no debt. Never reverts
     * — {@code priceReliable} is false when the collateral is unpriced or
     * the mark is older than {@code maxPriceAgeSeconds}, and callers must not treat {@code factor}
     * as trustworthy in that case.
     */
    HealthFactorReading healthFactor(String chainIdentifier, String marketAddress, String wallet) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("healthFactor",
                List.of(new Address(wallet)),
                List.of(new TypeReference<Uint256>() {}, new TypeReference<Bool>() {}));
        List<Type> decoded = call(web3j, marketAddress, fn);
        if (decoded.size() < 2) {
            throw new IllegalStateException("Empty response calling healthFactor on " + marketAddress);
        }
        // The contract's own flag AND a node that is at most one block behind: a price mark read from a
        // lagging node may already be stale on the chain, so it must not be reported as reliable.
        boolean nodeCurrent = clientRegistry.routedNodeLag(chainIdentifier).map(lag -> lag <= 1).orElse(true);
        return new HealthFactorReading((BigInteger) decoded.get(0).getValue(),
                (Boolean) decoded.get(1).getValue() && nodeCurrent);
    }

    record HealthFactorReading(BigInteger factor, boolean priceReliable) {}

    /**
     * First field of the public {@code positions(address)} mapping getter — the pledged
     * collateral amount. Solidity auto-generates one return value per struct field for a public
     * mapping-to-struct getter, in declaration order (collateralAmount, scaledDebt); only the
     * first is useful here since {@link #debtOf} already returns the correctly-scaled real debt.
     */
    BigInteger positionCollateralAmount(String chainIdentifier, String marketAddress, String wallet) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("positions",
                List.of(new Address(wallet)),
                List.of(new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {}));
        List<Type> decoded = call(web3j, marketAddress, fn);
        if (decoded.isEmpty()) return BigInteger.ZERO;
        return (BigInteger) decoded.get(0).getValue();
    }

    /** {@code EwpgRepoMarket.balanceOf(address) returns (uint256)} — a lender's current claim. */
    BigInteger supplyBalanceOf(String chainIdentifier, String marketAddress, String wallet) {
        return callUint256(chainIdentifier, marketAddress, "balanceOf", List.of(new Address(wallet)));
    }

    /** {@code EwpgRepoMarket.utilization() returns (uint256)} — WAD-scaled. */
    BigInteger utilization(String chainIdentifier, String marketAddress) {
        return callUint256(chainIdentifier, marketAddress, "utilization", Collections.emptyList());
    }

    /** {@code EwpgRepoMarket.borrowRate() returns (uint256)} — annualized, WAD-scaled. */
    BigInteger borrowRate(String chainIdentifier, String marketAddress) {
        return callUint256(chainIdentifier, marketAddress, "borrowRate", Collections.emptyList());
    }

    /** {@code EwpgRepoMarket.reserveFactorBps() returns (uint256)} — the current, operator-mutable value. */
    BigInteger reserveFactorBps(String chainIdentifier, String marketAddress) {
        return callUint256(chainIdentifier, marketAddress, "reserveFactorBps", Collections.emptyList());
    }

    /** Reads and validates all immutable values used by backend quotes and market discovery. */
    MarketParameters marketParameters(String chainIdentifier, String marketAddress) {
        if (!hasContractCode(chainIdentifier, marketAddress)) {
            throw new IllegalArgumentException("No contract is deployed at market address " + marketAddress);
        }
        String loanToken = callAddress(chainIdentifier, marketAddress, "loanToken");
        String collateralToken = callAddress(chainIdentifier, marketAddress, "collateralToken");
        String priceOracle = callAddress(chainIdentifier, marketAddress, "priceOracle");
        int loanTokenDecimals = callUint256(chainIdentifier, loanToken, "decimals", Collections.emptyList())
                .intValueExact();
        if (loanTokenDecimals < 0 || loanTokenDecimals > 36) {
            throw new IllegalArgumentException("Loan token decimals must be between 0 and 36");
        }
        // Markets predating the instance binding (operatorOrg/treasury) or an oracle predating
        // its quote denomination revert here — they are not registrable any more.
        String operatorOrg = callAddress(chainIdentifier, marketAddress, "operatorOrg");
        String treasury = callAddress(chainIdentifier, marketAddress, "treasury");
        String oracleQuoteToken = oracleQuoteToken(chainIdentifier, priceOracle);
        BigInteger oracleMaxDeviationBps = oracleMaxDeviationBps(chainIdentifier, priceOracle);
        return new MarketParameters(
                loanToken,
                collateralToken,
                priceOracle,
                callUint256(chainIdentifier, marketAddress, "maxLtvBps", Collections.emptyList()).intValueExact(),
                callUint256(chainIdentifier, marketAddress, "lltvBps", Collections.emptyList()).intValueExact(),
                callUint256(chainIdentifier, marketAddress, "liquidationBonusBps", Collections.emptyList()).intValueExact(),
                callUint256(chainIdentifier, marketAddress, "baseRateWad", Collections.emptyList()),
                callUint256(chainIdentifier, marketAddress, "slopeWad", Collections.emptyList()),
                callUint256(chainIdentifier, marketAddress, "maxPriceAgeSeconds", Collections.emptyList()),
                callUint256(chainIdentifier, marketAddress, "liquidationGracePeriodSeconds", Collections.emptyList()),
                loanTokenDecimals,
                operatorOrg,
                treasury,
                oracleQuoteToken,
                oracleMaxDeviationBps);
    }

    record MarketParameters(
            String loanTokenAddress,
            String collateralTokenAddress,
            String priceOracleAddress,
            int maxLtvBps,
            int lltvBps,
            int liquidationBonusBps,
            BigInteger baseRateWad,
            BigInteger slopeWad,
            BigInteger maxPriceAgeSeconds,
            BigInteger liquidationGracePeriodSeconds,
            int loanTokenDecimals,
            String operatorOrg,
            String treasury,
            String oracleQuoteToken,
            BigInteger oracleMaxDeviationBps) {}

    /** {@code EwpgRepoMarket.operatorOrg() returns (address)} — the org operating the instance. */
    String operatorOrg(String chainIdentifier, String marketAddress) {
        return callAddress(chainIdentifier, marketAddress, "operatorOrg");
    }

    /** {@code EwpgRepoMarket.treasury() returns (address)} — the only reserve-withdrawal recipient. */
    String treasury(String chainIdentifier, String marketAddress) {
        return callAddress(chainIdentifier, marketAddress, "treasury");
    }

    /** {@code IRepoOracle.quoteToken() returns (address)} — the token every mark is quoted in. */
    String oracleQuoteToken(String chainIdentifier, String oracleAddress) {
        return callAddress(chainIdentifier, oracleAddress, "quoteToken");
    }

    /**
     * {@code IRepoOracle.maxDeviationBps() returns (uint256)} — the oracle's per-window move
     * tolerance; {@code type(uint256).max} means the oracle opts out of the deviation concept.
     */
    BigInteger oracleMaxDeviationBps(String chainIdentifier, String oracleAddress) {
        return callUint256(chainIdentifier, oracleAddress, "maxDeviationBps", Collections.emptyList());
    }

    /**
     * {@code EwpgRepoMarket.surplusOf(address) returns (uint256)} — loan-token cash a
     * liquidation credited to the borrower, claimable via {@code claimLiquidationSurplus()}.
     */
    BigInteger liquidationSurplus(String chainIdentifier, String marketAddress, String wallet) {
        return callUint256(chainIdentifier, marketAddress, "surplusOf", List.of(new Address(wallet)));
    }

    BigInteger availableLiquidity(String chainIdentifier, String marketAddress, String loanTokenAddress) {
        return callUint256(chainIdentifier, loanTokenAddress, "balanceOf", List.of(new Address(marketAddress)));
    }

    /**
     * {@code EwpgRepoMarket.borrowPaused() returns (bool)} — the on-chain emergency-pause flag
     * (see {@code EwpgRepoMarket.setBorrowPaused}). Primitive {@code boolean}, not {@code Boolean}:
     * callers treat a read failure as "unknown, assume not paused" via a caught exception, never
     * a null return.
     */
    boolean borrowPaused(String chainIdentifier, String marketAddress) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("borrowPaused", Collections.emptyList(), List.of(new TypeReference<Bool>() {}));
        List<Type> decoded = call(web3j, marketAddress, fn);
        if (decoded.isEmpty()) return false;
        return (Boolean) decoded.get(0).getValue();
    }

    /**
     * {@code IRepoOracle.price(address) returns (uint256 pricePerUnit, uint256 updatedAt)} —
     * called directly against the market's own {@code priceOracleAddress}, not the market
     * itself, since price marks are formalized in {@code RegisterwerkNavOracle} (see
     * {@code contracts/src/lending/oracle/}).
     */
    PriceMark price(String chainIdentifier, String oracleAddress, String collateralTokenAddress) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("price",
                List.of(new Address(collateralTokenAddress)),
                List.of(new TypeReference<Uint256>() {}, new TypeReference<Uint256>() {}));
        List<Type> decoded = call(web3j, oracleAddress, fn);
        if (decoded.size() < 2) return new PriceMark(BigInteger.ZERO, BigInteger.ZERO);
        return new PriceMark((BigInteger) decoded.get(0).getValue(), (BigInteger) decoded.get(1).getValue());
    }

    record PriceMark(BigInteger pricePerUnit, BigInteger updatedAt) {}

    /** {@code EwpgRepoMarketFactory.isMarket(address) returns (bool)}. */
    boolean isFactoryMarket(String chainIdentifier, String factoryAddress, String marketAddress) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("isMarket", List.of(new Address(marketAddress)),
                List.of(new TypeReference<Bool>() {}));
        List<Type> decoded = call(web3j, factoryAddress, fn);
        return !decoded.isEmpty() && (Boolean) decoded.get(0).getValue();
    }

    /** {@code EwpgRepoMarket.totalCollateral() returns (uint256)}. */
    BigInteger totalCollateral(String chainIdentifier, String marketAddress) {
        return callUint256(chainIdentifier, marketAddress, "totalCollateral", Collections.emptyList());
    }

    /** ERC-20 {@code balanceOf(holder)} of an arbitrary token. */
    BigInteger tokenBalanceOf(String chainIdentifier, String tokenAddress, String holder) {
        return callUint256(chainIdentifier, tokenAddress, "balanceOf", List.of(new Address(holder)));
    }

    /** ERC-20 {@code decimals()} of an arbitrary token. */
    int tokenDecimals(String chainIdentifier, String tokenAddress) {
        return callUint256(chainIdentifier, tokenAddress, "decimals", Collections.emptyList()).intValueExact();
    }

    /** True when the address holds contract code (fails with an exception on transport errors). */
    boolean hasCode(String chainIdentifier, String address) {
        return hasContractCode(chainIdentifier, address);
    }

    /** keccak256 of the runtime code at {@code address} ({@code EXTCODEHASH}), or null if none. */
    String codeHash(String chainIdentifier, String address) {
        try {
            String code = clientRegistry.getEvmClientByIdentifier(chainIdentifier)
                    .ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
            if (code == null || code.equals("0x") || code.equals("0x0")) return null;
            return org.web3j.crypto.Hash.sha3(code);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bytecode at " + address, e);
        }
    }

    /**
     * Whether the deployed market exposes {@code surplusOf(address)}: a clean read (any value)
     * means supported; a revert or empty return means the function is absent (legacy market).
     * Transport errors propagate so registration fails closed instead of guessing.
     */
    boolean surplusSupported(String chainIdentifier, String marketAddress) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function("surplusOf", List.of(new Address("0x0000000000000000000000000000000000000000")),
                List.of(new TypeReference<Uint256>() {}));
        try {
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, marketAddress, FunctionEncoder.encode(fn)),
                    readBlock()).send();
            if (response.isReverted() || response.hasError()) return false;
            return !FunctionReturnDecoder.decode(response.getValue(), fn.getOutputParameters()).isEmpty();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to probe surplusOf on " + marketAddress, e);
        }
    }

    private boolean hasContractCode(String chainIdentifier, String contractAddress) {
        try {
            var response = clientRegistry.getEvmClientByIdentifier(chainIdentifier)
                    .ethGetCode(contractAddress, DefaultBlockParameterName.LATEST).send();
            String code = response.getCode();
            return code != null && !code.equals("0x") && !code.equals("0x0");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read bytecode at " + contractAddress, e);
        }
    }

    private String callAddress(String chainIdentifier, String contractAddress, String functionName) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function(functionName, Collections.emptyList(),
                List.of(new TypeReference<Address>() {}));
        List<Type> decoded = call(web3j, contractAddress, fn);
        if (decoded.isEmpty()) {
            throw new IllegalStateException("Empty response calling " + functionName + " on " + contractAddress);
        }
        return decoded.get(0).getValue().toString();
    }

    private BigInteger callUint256(String chainIdentifier, String contractAddress, String functionName,
                                    List<Type> inputs) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        Function fn = new Function(functionName, inputs, List.of(new TypeReference<Uint256>() {}));
        List<Type> decoded = call(web3j, contractAddress, fn);
        if (decoded.isEmpty()) {
            throw new IllegalStateException("Empty response calling " + functionName + " on " + contractAddress);
        }
        return (BigInteger) decoded.get(0).getValue();
    }

    private List<Type> call(Web3j web3j, String contractAddress, Function fn) {
        try {
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, contractAddress, FunctionEncoder.encode(fn)),
                    readBlock()).send();

            if (response.isReverted()) {
                throw new IllegalStateException(
                        "Call to " + fn.getName() + " on " + contractAddress + " reverted: "
                                + response.getRevertReason());
            }
            return FunctionReturnDecoder.decode(response.getValue(), fn.getOutputParameters());
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to call " + fn.getName() + " on " + contractAddress, e);
        }
    }
}
