package de.makibytes.registerwerk.indexer.api;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetLookupPort;
import de.makibytes.registerwerk.deployment.api.TokenStandard;
import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RegisterAsOfQuery fail-closed rules")
class RegisterAsOfQueryTest {

    private static final Instant CUTOFF = Instant.parse("2025-06-27T22:00:00Z");
    private static final String A = "0x00000000000000000000000000000000000000aa";
    private static final String B = "0x00000000000000000000000000000000000000bb";
    private static final String ZERO = "0x0000000000000000000000000000000000000000";

    private final UUID assetId = UUID.randomUUID();
    private final AssetDeploymentRepository deployments = mock(AssetDeploymentRepository.class);
    private final AssetLookupPort lookup = mock(AssetLookupPort.class);
    private final EntityManager em = mock(EntityManager.class);
    private final RegisterAsOfQuery query = new RegisterAsOfQuery(deployments, lookup);

    @SuppressWarnings("unchecked")
    private void arrange(TokenStandard standard, List<Object[]> rows) {
        ReflectionTestUtils.setField(query, "entityManager", em);
        AssetDeployment d = new AssetDeployment();
        ReflectionTestUtils.setField(d, "id", UUID.randomUUID());
        when(deployments.findByAssetId(assetId)).thenReturn(List.of(d));
        AssetLookupPort.AssetInfo info = mock(AssetLookupPort.AssetInfo.class);
        when(info.tokenStandard()).thenReturn(standard);
        when(lookup.findById(assetId)).thenReturn(Optional.of(info));
        TypedQuery<Object[]> rowQuery = mock(TypedQuery.class);
        when(rowQuery.setParameter(anyString(), any())).thenReturn(rowQuery);
        when(rowQuery.getResultList()).thenReturn(new ArrayList<>(rows));
        TypedQuery<Long> countQuery = mock(TypedQuery.class);
        when(countQuery.setParameter(anyString(), any())).thenReturn(countQuery);
        when(countQuery.getSingleResult()).thenReturn(0L);
        when(em.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(Object[].class))).thenReturn(rowQuery);
        when(em.createQuery(anyString(), org.mockito.ArgumentMatchers.eq(Long.class))).thenReturn(countQuery);
    }

    @Test
    @DisplayName("ERC-3525 and Starknet ERC-3525 are unsupported: no netting of token ids")
    void erc3525Unsupported() {
        for (TokenStandard s : List.of(TokenStandard.ERC3525, TokenStandard.STARKNET_ERC3525)) {
            arrange(s, List.<Object[]>of(new Object[]{ZERO, A, null}));
            var result = query.balancesAsOf(assetId, CUTOFF);
            assertThat(result.unsupportedReason()).contains(s.name());
            assertThat(result.balances()).isEmpty();
        }
    }

    @Test
    @DisplayName("a null-amount transfer on ERC-20 is unsupported, not one unit")
    void nullAmountUnsupportedOnErc20() {
        arrange(TokenStandard.ERC20, List.<Object[]>of(new Object[]{ZERO, A, new BigDecimal("5")}, new Object[]{A, B, null}));
        var result = query.balancesAsOf(assetId, CUTOFF);
        assertThat(result.unsupportedReason()).contains("without amount");
    }

    @Test
    @DisplayName("ERC-721 null amount still counts as one unit; ERC-20 amounts net normally")
    void supportedCases() {
        arrange(TokenStandard.ERC721, List.<Object[]>of(new Object[]{ZERO, A, null}));
        var nft = query.balancesAsOf(assetId, CUTOFF);
        assertThat(nft.unsupportedReason()).isNull();
        assertThat(nft.balances().get(A)).isEqualByComparingTo("1");

        arrange(TokenStandard.ERC20, List.<Object[]>of(new Object[]{ZERO, A, new BigDecimal("5")}, new Object[]{A, B, new BigDecimal("2")}));
        var ft = query.balancesAsOf(assetId, CUTOFF);
        assertThat(ft.unsupportedReason()).isNull();
        assertThat(ft.balances().get(A)).isEqualByComparingTo("3");
        assertThat(ft.balances().get(B)).isEqualByComparingTo("2");
    }
}
