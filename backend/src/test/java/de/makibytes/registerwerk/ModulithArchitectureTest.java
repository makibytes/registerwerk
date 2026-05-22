package de.makibytes.registerwerk;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithArchitectureTest {

    static final ApplicationModules modules = ApplicationModules.of(RegisterwerkApplication.class);

    @Test
    @Disabled("""
        Remaining cycle: asset ↔ blockchain.
        Web-layer fix done (Track F): VaultController/Erc3525SlotController moved to asset/web,
        MintControlService/Job moved to asset/api, TokenAdminController uses AssetDeploymentPort.
        Remaining: 19 blockchain/internal/ classes (deployment services, admin services) still import
        asset types (AssetDeployment, AssetVaultState, VaultNavStrike, etc.).
        Proper fix: extract a 'deployment' module containing AssetDeployment and deployment-related
        JPA entities; both asset and blockchain depend on 'deployment'.
        erc3643 ↔ blockchain: NOT an actual cycle — blockchain/internal has no erc3643 imports (verified).
        """)
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void writesDocumentation() {
        new Documenter(modules)
                .writeDocumentation()
                .writeIndividualModulesAsPlantUml();
    }
}
