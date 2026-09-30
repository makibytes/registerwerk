package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.blockchain.internal.tx.EvmSignedSubmissionRepository;
import de.makibytes.registerwerk.wallet.api.WalletUsagePort;
import org.springframework.stereotype.Component;

/** Answers {@link WalletUsagePort} from the durable-submission outbox ({@code evm_signed_submission.sender_address}). */
@Component
class WalletUsageAdapter implements WalletUsagePort {

    private final EvmSignedSubmissionRepository submissions;

    WalletUsageAdapter(EvmSignedSubmissionRepository submissions) {
        this.submissions = submissions;
    }

    @Override
    public boolean hasSignedOnChain(String address) {
        return address != null && submissions.existsBySenderAddressIgnoreCase(address);
    }
}
