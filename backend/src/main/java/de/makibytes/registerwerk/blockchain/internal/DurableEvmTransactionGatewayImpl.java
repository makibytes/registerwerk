package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.api.DurableEvmSubmissionPort;
import de.makibytes.registerwerk.blockchain.api.DurableEvmTransactionGateway;
import de.makibytes.registerwerk.shared.AfterCommit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.web3j.abi.datatypes.Function;

import java.util.Map;
import java.util.UUID;

@Service
class DurableEvmTransactionGatewayImpl implements DurableEvmTransactionGateway {

    private final DurableEvmSubmissionPort submissions;

    DurableEvmTransactionGatewayImpl(DurableEvmSubmissionPort submissions) {
        this.submissions = submissions;
    }

    @Override
    @Transactional
    public String submit(UUID chainConfigId, String contractAddress,
            Function function, Map<String, Object> params) {
        DurableEvmSubmissionPort.PreparedSubmission prepared =
                submissions.prepare(chainConfigId, contractAddress, function, params);
        AfterCommit.run(() -> {
            try {
                submissions.dispatch(prepared.id());
            } catch (RuntimeException ignored) {
                // PREPARED is the durable retry signal. The fleet-single dispatcher will send
                // these exact signed bytes; never turn an after-commit RPC failure into a false
                // rollback signal for the already committed business intent.
            }
        });
        return prepared.txHash();
    }
}
