package de.makibytes.registerwerk.indexer.web;

import de.makibytes.registerwerk.indexer.api.TokenTransfer;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.indexer.web.dto.TokenTransferResponse;
import de.makibytes.registerwerk.shared.SecurityUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.core.Authentication;
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
 * <p>{@code finalityLabel} is resolved from {@code authentication} here, not left to the
 * frontend, per the portfolio plan's "two vocabularies, one model, resolved server-side by
 * principal role so the frontend cannot violate it" — previously this mapper shipped the raw
 * {@code FinalityLevel} enum name to every caller regardless of role, including customers, who
 * have no reason to know what PROVISIONAL/SAFE/FINALIZED/ORPHANED mean.
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
     * {@code chainIdentifier} via a repository lookup on {@code chainConfigId} and
     * {@code finalityLabel} from the caller's role.
     */
    public TokenTransferResponse toResponse(TokenTransfer transfer, Authentication authentication) {
        String identifier = chainConfigRepository.findById(transfer.getChainConfigId())
                .map(c -> c.getIdentifier())
                .orElse(transfer.getChainConfigId().toString());

        boolean technical = SecurityUtils.isTechnicalRole(authentication);
        String finalityLabel = technical
                ? transfer.getFinalityStatus().name()
                : transfer.getFinalityStatus().plainLabel();

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
                identifier,
                transfer.getFinalityStatus().name(),
                finalityLabel
        );
    }
}
