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
        return new HealthFactorReading((BigInteger) decoded.get(0).getValue(), (Boolean) decoded.get(1).getValue());
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
                    DefaultBlockParameterName.LATEST).send();

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
