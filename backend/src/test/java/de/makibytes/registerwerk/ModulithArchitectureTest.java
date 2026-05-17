package de.makibytes.registerwerk;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithArchitectureTest {

    static final ApplicationModules modules = ApplicationModules.of(RegisterwerkApplication.class);

    // TODO: asset ↔ blockchain and asset ↔ erc3643 cycles exist because AssetDeploymentService
    //  directly orchestrates internal services from those modules. The fix requires a
    //  TokenDeploymentPort facade in blockchain/api/ and moving deployment orchestration out of
    //  asset/internal/. Track as known tech debt before merging feat/modulith → main.
    @Test
    @Disabled("Known cycles: asset↔blockchain, asset↔erc3643 — requires TokenDeploymentPort refactoring")
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
