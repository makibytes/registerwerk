package de.makibytes.registerwerk.bootstrap;

import de.makibytes.registerwerk.wallet.api.OperatorWalletRepository;
import de.makibytes.registerwerk.wallet.api.WalletManagement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

/** Enrolls the Anvil operator fixture through PKCS#11 so the demo really signs via SoftHSM. */
@Component
@ConditionalOnProperty(name = "registerwerk.seed-demo-data", havingValue = "true")
public class DemoHsmWalletSeeder implements ApplicationRunner, Ordered {
    private static final Logger log = LoggerFactory.getLogger(DemoHsmWalletSeeder.class);
    private static final String NAME = "Demo Operator (SoftHSM)";
    private static final String ALIAS = "registerwerk-operator";
    private static final String ADDRESS = "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266";

    private final OperatorWalletRepository wallets;
    private final WalletManagement walletManagement;

    public DemoHsmWalletSeeder(OperatorWalletRepository wallets, WalletManagement walletManagement) {
        this.wallets = wallets;
        this.walletManagement = walletManagement;
    }

    @Override public int getOrder() { return 15; }

    @Override
    public void run(ApplicationArguments args) {
        if (wallets.findByName(NAME).isPresent()) {
            return;
        }
        walletManagement.attachHsm(NAME, ALIAS, ADDRESS, null, "DEMO_BOOTSTRAP");
        log.info("Demo operator wallet enrolled through SoftHSM: {}", ADDRESS);
    }
}
