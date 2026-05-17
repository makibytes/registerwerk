package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.indexer.web.dto.TokenTransferResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Mapper for {@link TokenTransfer} → {@link TokenTransferResponse} conversions.
 *
 * <p>Because the response record carries {@code chainIdentifier} — a field that does not
 * exist on the entity and requires a database lookup — we implement this mapper as a plain
 * Spring {@code @Component} rather than a MapStruct interface. This avoids the complexity of
 * {@code @AfterMapping} with an immutable record target while keeping the same API contract
 * expected by controllers.
 *
 * <p>The primary entry point for controllers is {@link #toResponse(TokenTransfer)}, which
 * resolves the chain identifier from {@link ChainConfigRepository}.
 */
@Component
public class TokenTransferMapper {

    private final ChainConfigRepository chainConfigRepository;

    @Autowired
    public TokenTransferMapper(ChainConfigRepository chainConfigRepository) {
        this.chainConfigRepository = chainConfigRepository;
    }

    /**
     * Maps a {@link TokenTransfer} entity to a {@link TokenTransferResponse}, resolving the
     * {@code chainIdentifier} via a repository lookup on {@code chainConfigId}.
     */
    public TokenTransferResponse toResponse(TokenTransfer transfer) {
        String identifier = chainConfigRepository.findById(transfer.getChainConfigId())
                .map(c -> c.getIdentifier())
                .orElse(transfer.getChainConfigId().toString());

        return new TokenTransferResponse(
                transfer.getId(),
                transfer.getContractAddress(),
                transfer.getFromAddress(),
                transfer.getToAddress(),
                transfer.getTokenId(),
                transfer.getAmount(),
                transfer.getEventType().name(),
                transfer.getTxHash(),
                transfer.getBlockNumber(),
                transfer.getOccurredAt(),
                transfer.getExplorerTxUrl(),
                identifier
        );
    }

    /**
     * Alias kept for call-site compatibility with the original spec that used
     * {@code toResponseWithChain}.
     */
    public TokenTransferResponse toResponseWithChain(TokenTransfer transfer) {
        return toResponse(transfer);
    }
}
