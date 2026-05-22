@org.springframework.modulith.ApplicationModule(
        displayName = "Blockchain",
        // blockchain → asset dependency exists in blockchain/internal/ (deployment services, admin services)
        // because blockchain IS the implementation layer for asset operations. Full resolution requires
        // extracting a 'deployment' module for AssetDeployment and related entities, which both asset
        // and blockchain would depend on. Partial fix done (Track F): web-layer controllers moved to
        // asset/web, MintControlService/Job moved to asset/api, AssetDeploymentPort created.
        // Remaining: 19 blockchain/internal files still use asset types.
        allowedDependencies = {"shared", "audit", "chain", "asset", "wallet", "erc3643", "indexer"}
)
package de.makibytes.registerwerk.blockchain;
