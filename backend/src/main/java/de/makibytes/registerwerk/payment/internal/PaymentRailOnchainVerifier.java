package de.makibytes.registerwerk.payment.internal;

import de.makibytes.registerwerk.blockchain.api.EvmContractService;
import de.makibytes.registerwerk.payment.api.PaymentRailType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Uint8;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Save-time sanity check of a chain-bound rail against the chain: the address must hold code
 * and, for stablecoin rails, the token's {@code decimals()} must equal the declared decimals
 * (an operator typo would otherwise mis-scale every amount a dApp settles through the rail).
 * Fails closed: an unreachable node rejects the save rather than accepting unchecked input.
 */
@Component
class PaymentRailOnchainVerifier {

    private static final Logger log = LoggerFactory.getLogger(PaymentRailOnchainVerifier.class);

    private final EvmContractService evm;
    private final boolean enabled;

    PaymentRailOnchainVerifier(EvmContractService evm,
                               @Value("${registerwerk.payment.onchain-verify:true}") boolean enabled) {
        this.evm = evm;
        this.enabled = enabled;
    }

    void verify(PaymentRailType railType, Integer declaredDecimals, Map<UUID, String> chainAddresses) {
        if (!enabled || railType == null || !railType.isChainBound()
                || chainAddresses == null || chainAddresses.isEmpty()) {
            return;
        }
        for (Map.Entry<UUID, String> entry : chainAddresses.entrySet()) {
            String address = entry.getValue();
            try {
                Web3j web3j = evm.evmClient(entry.getKey());
                String code = web3j.ethGetCode(address, DefaultBlockParameterName.LATEST).send().getCode();
                if (code == null || code.equals("0x") || code.equals("0x0")) {
                    throw new IllegalArgumentException("Address " + address + " has no contract code on chain "
                            + entry.getKey());
                }
                if (railType == PaymentRailType.STABLECOIN) {
                    Function fn = new Function("decimals", List.of(), List.of(new TypeReference<Uint8>() {}));
                    List<Type> out = evm.call(web3j, address, fn);
                    if (out.isEmpty()) {
                        throw new IllegalStateException("Token " + address + " returned no decimals()");
                    }
                    BigInteger onchain = (BigInteger) out.get(0).getValue();
                    if (declaredDecimals == null || onchain.intValueExact() != declaredDecimals) {
                        throw new IllegalArgumentException("Declared decimals " + declaredDecimals
                                + " differ from the token's decimals() " + onchain + " at " + address);
                    }
                }
            } catch (IllegalArgumentException | IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Payment-rail on-chain verification failed for {}: {}", address, e.toString());
                throw new IllegalStateException("Could not verify token " + address
                        + " on chain (node unreachable or not a token contract); rail not saved", e);
            }
        }
    }
}
