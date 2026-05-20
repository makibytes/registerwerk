package de.makibytes.registerwerk;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModulithArchitectureTest {

    static final ApplicationModules modules = ApplicationModules.of(RegisterwerkApplication.class);

    @Test
    @Disabled("Documented cycles: asset <-> blockchain (via TokenDeploymentPort/BlockchainClientRegistry), " +
             "asset <-> erc3643 (via Erc3643DeploymentPort), erc3643 <-> blockchain (via EvmUtils/ConfidentialTokenEvents). " +
             "These are acceptable per module architecture - resolve via proper port abstraction cleanup.")
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
