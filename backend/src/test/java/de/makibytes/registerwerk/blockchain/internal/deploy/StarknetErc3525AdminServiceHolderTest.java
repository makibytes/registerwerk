package de.makibytes.registerwerk.blockchain.internal.deploy;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.Network;
import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.deployment.api.AssetSlotRepository;
import de.makibytes.registerwerk.deployment.api.AssetTokenUnitRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Veto note 3: the compliant Cairo EwpgERC3525 exposes {@code whitelist}/{@code remove_from_whitelist}
 * /{@code freeze_address}/{@code unfreeze_address}; the calldata must match the Cairo ABI
 * ({@code ContractAddress} = one felt, {@code reason: felt252} = one short-string felt).
 */
@DisplayName("StarknetErc3525AdminService — holder whitelist/freeze calldata")
class StarknetErc3525AdminServiceHolderTest {

    private static final UUID DEP_ID = UUID.randomUUID();
    private static final String CONTRACT = "0x04a2be89cf7db5e2d698da900bd6e3dec83b7cf11a2be89cf7db5e2d698da90";
    private static final String HOLDER = "0x49d36570d4e46f48e99674bd3fcc84644ddd6b96f7c741b1562b82f9e004dc7";
    private static final BigInteger HOLDER_FELT = new BigInteger(HOLDER.substring(2), 16);

    private final AssetDeploymentRepository deploymentRepository = mock(AssetDeploymentRepository.class);
    private final StarknetTokenService starknet = mock(StarknetTokenService.class);
    private StarknetErc3525AdminService service;

    @BeforeEach
    void setUp() {
        AssetDeployment dep = new AssetDeployment();
        dep.setId(DEP_ID);
        dep.setAssetId(UUID.randomUUID());
        dep.setChain(Chain.STARKNET);
        dep.setNetwork(Network.TESTNET);
        dep.setContractAddress(CONTRACT);
        when(deploymentRepository.findById(DEP_ID)).thenReturn(Optional.of(dep));
        when(starknet.invokeContract(any(), anyString(), anyString(), anyList()))
                .thenReturn(CompletableFuture.completedFuture("0xtx"));
        when(starknet.shortStringToFeltPublic("sanctions")).thenReturn(BigInteger.valueOf(777));
        service = new StarknetErc3525AdminService(deploymentRepository, mock(AssetSlotRepository.class),
                mock(AssetTokenUnitRepository.class), starknet);
    }

    @Test
    @DisplayName("each holder control invokes the Cairo entry point with the felt-encoded account")
    void holderControlsEncodeCairoCalldata() {
        service.whitelist(DEP_ID, HOLDER).join();
        service.removeFromWhitelist(DEP_ID, HOLDER).join();
        service.freezeAddress(DEP_ID, HOLDER, "sanctions").join();
        service.unfreezeAddress(DEP_ID, HOLDER).join();

        verify(starknet).invokeContract(Network.TESTNET, CONTRACT, "whitelist", List.of(HOLDER_FELT));
        verify(starknet).invokeContract(Network.TESTNET, CONTRACT, "remove_from_whitelist", List.of(HOLDER_FELT));
        verify(starknet).invokeContract(eq(Network.TESTNET), eq(CONTRACT), eq("freeze_address"),
                eq(List.of(HOLDER_FELT, BigInteger.valueOf(777))));
        verify(starknet).invokeContract(Network.TESTNET, CONTRACT, "unfreeze_address", List.of(HOLDER_FELT));
    }
}
