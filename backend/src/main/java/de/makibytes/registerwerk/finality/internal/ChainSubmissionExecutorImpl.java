package de.makibytes.registerwerk.finality.internal;

import de.makibytes.registerwerk.finality.api.ChainQuarantinePort;
import de.makibytes.registerwerk.finality.api.ChainSubmissionExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

@Service
class ChainSubmissionExecutorImpl implements ChainSubmissionExecutor {

    private final ChainQuarantinePort quarantine;
    private final TransactionTemplate transactions;

    ChainSubmissionExecutorImpl(
            ChainQuarantinePort quarantine,
            PlatformTransactionManager transactionManager) {
        this.quarantine = quarantine;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> T execute(UUID chainConfigId, Supplier<T> submission) {
        Objects.requireNonNull(chainConfigId, "chainConfigId");
        Objects.requireNonNull(submission, "submission");
        return transactions.execute(status -> {
            quarantine.requireSubmissionAllowed(chainConfigId);
            return submission.get();
        });
    }
}
