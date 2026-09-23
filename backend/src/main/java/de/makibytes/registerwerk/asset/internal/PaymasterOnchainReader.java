package de.makibytes.registerwerk.asset.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import org.springframework.stereotype.Component;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.Type;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.request.Transaction;
import org.web3j.protocol.core.methods.response.EthCall;

import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * Raw {@code eth_call} reads of one policy on {@code EwpgPaymaster} for the operator's gas
 * sponsorship panel (same hand-built {@code Function} pattern as {@code RepoMarketOnchainReader};
 * no generated wrapper exists). Read-only.
 */
@Component
class PaymasterOnchainReader {

    private final BlockchainClientRegistry clientRegistry;

    PaymasterOnchainReader(BlockchainClientRegistry clientRegistry) {
        this.clientRegistry = clientRegistry;
    }

    record PolicyState(String funder, String signer, boolean active, BigInteger balanceWei,
                       BigInteger reservedWei, BigInteger orgBudgetCapWei) {
        boolean registered() {
            return funder != null && new BigInteger(funder.substring(2), 16).signum() != 0;
        }
    }

    PolicyState read(String chainIdentifier, String paymaster, byte[] policyId) {
        Web3j web3j = clientRegistry.getEvmClientByIdentifier(chainIdentifier);
        return new PolicyState(
                call(web3j, paymaster, "funder", policyId, new TypeReference<Address>() {}).toString(),
                call(web3j, paymaster, "policySigner", policyId, new TypeReference<Address>() {}).toString(),
                (Boolean) call(web3j, paymaster, "policyActive", policyId, new TypeReference<Bool>() {}).getValue(),
                (BigInteger) call(web3j, paymaster, "policyBalance", policyId, new TypeReference<Uint256>() {}).getValue(),
                (BigInteger) call(web3j, paymaster, "policyReserved", policyId, new TypeReference<Uint256>() {}).getValue(),
                (BigInteger) call(web3j, paymaster, "orgBudgetCap", policyId, new TypeReference<Uint256>() {}).getValue());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Type call(Web3j web3j, String paymaster, String name, byte[] policyId, TypeReference<?> output) {
        Function fn = new Function(name, List.of(new Bytes32(policyId)), List.of((TypeReference) output));
        try {
            EthCall response = web3j.ethCall(
                    Transaction.createEthCallTransaction(null, paymaster, FunctionEncoder.encode(fn)),
                    DefaultBlockParameterName.LATEST).send();
            if (response.hasError()) {
                throw new IllegalStateException("eth_call " + name + " failed: " + response.getError().getMessage());
            }
            List<Type> decoded = FunctionReturnDecoder.decode(response.getValue(), fn.getOutputParameters());
            if (decoded.isEmpty()) {
                throw new IllegalStateException("Empty response calling " + name + " on " + paymaster);
            }
            return decoded.get(0);
        } catch (IOException e) {
            throw new IllegalStateException("eth_call " + name + " failed: " + e.getMessage(), e);
        }
    }
}
