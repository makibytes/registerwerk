package de.makibytes.registerwerk.erc3643.internal;

import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.EvmFinalityResolver;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentity;
import de.makibytes.registerwerk.erc3643.api.OnchainIdentityRepository;
import de.makibytes.registerwerk.finality.api.ChainEffectDescriptor;
import de.makibytes.registerwerk.finality.api.ChainEffectRecorder;
import de.makibytes.registerwerk.finality.api.CompensationCategory;
import de.makibytes.registerwerk.shared.IsolatedTransactionExecutor;
import de.makibytes.registerwerk.finality.api.FinalityLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.jpa.repository.Query;
import org.springframework.scheduling.annotation.Scheduled;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.stereotype.Component;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.methods.response.EthGetTransactionReceipt;
import org.web3j.protocol.core.methods.response.TransactionReceipt;

import java.util.List;
import java.util.Optional;

/**
 * Polls pending ONCHAINID identity deployments and updates their address once
 * the {@code deployIdentityProxy} transaction is confirmed on-chain.
 *
 * A record is pending when its {@code identityAddress} starts with {@code 0x-PENDING-}
 * and {@code deployedByTx} is non-null. The deployed proxy address is read from
 * the transaction receipt's {@code contractAddress} field.
 */
@Component
class OnchainIdentityReceiptListener {

    private static final Logger log = LoggerFactory.getLogger(OnchainIdentityReceiptListener.class);

    private final OnchainIdentityRepository identityRepository;
    private final ChainConfigRepository chainConfigRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final EvmFinalityResolver finalityResolver;
    private final ChainEffectRecorder chainEffectRecorder;
    private final IsolatedTransactionExecutor isolatedTransactions;

    OnchainIdentityReceiptListener(
            OnchainIdentityRepository identityRepository,
            ChainConfigRepository chainConfigRepository,
            BlockchainClientRegistry clientRegistry,
            EvmFinalityResolver finalityResolver,
            ChainEffectRecorder chainEffectRecorder,
            IsolatedTransactionExecutor isolatedTransactions) {
        this.identityRepository = identityRepository;
        this.chainConfigRepository = chainConfigRepository;
        this.clientRegistry = clientRegistry;
        this.finalityResolver = finalityResolver;
        this.chainEffectRecorder = chainEffectRecorder;
        this.isolatedTransactions = isolatedTransactions;
    }

    @SchedulerLock(name = "onchainIdentityReceiptListener", lockAtMostFor = "PT1M", lockAtLeastFor = "PT20S")
    @Scheduled(fixedDelay = 30_000, initialDelay = 25_000)
    public void resolvePendingIdentities() {
        // Indexed prefix query — this poller runs every 30s; a full-table scan
        // with in-memory filtering would grow linearly with the identity count.
        List<OnchainIdentity> pending = identityRepository
                .findByIdentityAddressStartingWithAndDeployedByTxIsNotNull("0x-PENDING-");

        if (pending.isEmpty()) return;
        log.debug("Checking {} pending ONCHAINID deployments", pending.size());

        for (OnchainIdentity identity : pending) {
            try {
                isolatedTransactions.run(() -> resolveIdentity(identity));
            } catch (Exception e) {
                log.warn("Failed to resolve pending identity={}: {}", identity.getId(), e.getMessage());
            }
        }
    }

    private void resolveIdentity(OnchainIdentity identity) throws Exception {
        ChainConfig chainConfig = chainConfigRepository.findById(identity.getChainConfigId())
                .orElse(null);
        if (chainConfig == null) return;

        Web3j web3j;
        try {
            web3j = clientRegistry.getEvmClientByIdentifier(chainConfig.getIdentifier());
        } catch (Exception e) {
            log.debug("EVM client not available for chain={}", chainConfig.getIdentifier());
            return;
        }

        EthGetTransactionReceipt response = web3j
                .ethGetTransactionReceipt(identity.getDeployedByTx())
                .send();

        Optional<TransactionReceipt> receiptOpt = response.getTransactionReceipt();
        if (receiptOpt.isEmpty()) return; // not yet mined

        TransactionReceipt receipt = receiptOpt.get();
        if (!"0x1".equals(receipt.getStatus())) {
            log.error("deployIdentityProxy tx={} failed for identity={}; marking address as FAILED",
                    identity.getDeployedByTx(), identity.getId());
            identity.setIdentityAddress("0x-FAILED-" + identity.getDeployedByTx());
            identityRepository.save(identity);
            return;
        }

        // Consult the chain's configured FinalityModel rather than accepting the first mined
        // receipt: a reorg that un-mines this deployment must not leave the register asserting
        // an identity address the chain has since abandoned. Stay pending (recheck next tick)
        // rather than resolve on a shallow/unconfirmed receipt.
        long blockNumber = receipt.getBlockNumber().longValueExact();
        boolean isFinal = finalityResolver.levelOf(chainConfig.getIdentifier(), web3j, blockNumber)
                .atLeast(FinalityLevel.FINALIZED);
        if (!isFinal) {
            return;
        }

        String realAddress = extractIdentityAddress(receipt);
        if (realAddress == null) return;

        identity.setIdentityAddress(realAddress);
        identity.setDeployedBlockNumber(blockNumber);
        identity.setDeployedBlockHash(receipt.getBlockHash());
        identityRepository.save(identity);
        log.info("Resolved ONCHAINID identity={} address={} tx={}",
                identity.getId(), realAddress, identity.getDeployedByTx());

        chainEffectRecorder.recordFinalized(ChainEffectDescriptor.of(
                identity.getChainConfigId(), blockNumber, receipt.getBlockHash(), identity.getDeployedByTx(),
                "erc3643", OnchainIdentityRevertCompensator.EFFECT_TYPE, "OnchainIdentity", identity.getId(), null,
                CompensationCategory.INVERSE_FLIP));
    }

    private static String extractIdentityAddress(TransactionReceipt receipt) {
        if (receipt.getContractAddress() != null && !receipt.getContractAddress().isBlank()) {
            return receipt.getContractAddress();
        }
        if (receipt.getLogs() != null) {
            for (var log : receipt.getLogs()) {
                if (log.getData() != null && log.getData().length() >= 66) {
                    return "0x" + log.getData().substring(log.getData().length() - 40);
                }
            }
        }
        return null;
    }
}
