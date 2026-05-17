@org.springframework.modulith.ApplicationModule(
        displayName = "Blockchain",
        // TODO: asset dependency creates a cycle with asset→blockchain; resolve via TokenDeploymentPort
        allowedDependencies = {"shared", "audit", "chain", "asset", "wallet", "erc3643", "indexer"}
)
package de.makibytes.registerwerk.blockchain;
