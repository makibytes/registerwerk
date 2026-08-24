// SPDX-License-Identifier: MIT
pragma solidity ^0.8.36;

import {AssetTokenFactory} from "./AssetTokenFactory.sol";
import {
    IAssetTokenDeployer,
    Erc20TokenDeployer,
    Erc721TokenDeployer,
    Erc1155TokenDeployer,
    Erc3525TokenDeployer,
    Erc4626TokenDeployer,
    Erc7540TokenDeployer
} from "./AssetTokenDeployers.sol";

/// @dev Deployment helper used by scripts/tests; it is never deployed on-chain itself.
library AssetTokenFactoryBootstrap {
    function configure(AssetTokenFactory factory, address registry) internal {
        IAssetTokenDeployer[6] memory modules = [
            IAssetTokenDeployer(address(new Erc20TokenDeployer(registry))),
            IAssetTokenDeployer(address(new Erc721TokenDeployer(registry))),
            IAssetTokenDeployer(address(new Erc1155TokenDeployer(registry))),
            IAssetTokenDeployer(address(new Erc3525TokenDeployer(registry))),
            IAssetTokenDeployer(address(new Erc4626TokenDeployer(registry))),
            IAssetTokenDeployer(address(new Erc7540TokenDeployer(registry)))
        ];
        for (uint8 i = 0; i < modules.length; i++) {
            factory.configureDeployer(i, address(modules[i]));
            modules[i].bindFactory(address(factory));
        }
    }
}
