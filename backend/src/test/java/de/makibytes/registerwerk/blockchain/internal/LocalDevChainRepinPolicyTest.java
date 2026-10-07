package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.chain.api.ChainConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LocalDevChainRepinPolicy")
class LocalDevChainRepinPolicyTest {

    private static final String DEMO_RPC = "http://anvil:8545";

    private static ChainConfig chain(Long chainId, String rpcUrl) {
        ChainConfig c = new ChainConfig();
        c.setChainId(chainId);
        c.setRpcUrl(rpcUrl);
        return c;
    }

    @Test
    @DisplayName("the demo anvil slot (Sepolia chain id on the Compose anvil / loopback) may re-pin with demo seeding on")
    void demoAnvilMayRepin() {
        LocalDevChainRepinPolicy policy = new LocalDevChainRepinPolicy(true, false, DEMO_RPC);
        assertThat(policy.allowsRepin(chain(11155111L, "http://anvil:8545"))).isTrue();
        assertThat(policy.allowsRepin(chain(11155111L, "http://localhost:48545"))).isTrue();
        assertThat(policy.allowsRepin(chain(11155111L, "http://127.0.0.1:8545"))).isTrue();
        assertThat(policy.allowsRepin(chain(31337L, "https://anything.example"))).isTrue();
        assertThat(policy.allowsRepin(chain(1337L, null))).isTrue();
    }

    @Test
    @DisplayName("real chains never re-pin, even with demo seeding on")
    void realChainsNeverRepin() {
        LocalDevChainRepinPolicy policy = new LocalDevChainRepinPolicy(true, false, DEMO_RPC);
        assertThat(policy.allowsRepin(chain(1L, "https://eth.llamarpc.com"))).isFalse();
        assertThat(policy.allowsRepin(chain(11155111L, "https://rpc.sepolia.org"))).isFalse();
        assertThat(policy.allowsRepin(chain(1L, null))).isFalse();
        assertThat(policy.allowsRepin(null)).isFalse();
    }

    @Test
    @DisplayName("never without demo seeding, never in production mode")
    void needsDemoSeedingAndNonProduction() {
        assertThat(new LocalDevChainRepinPolicy(false, false, DEMO_RPC)
                .allowsRepin(chain(31337L, "http://anvil:8545"))).isFalse();
        assertThat(new LocalDevChainRepinPolicy(true, true, DEMO_RPC)
                .allowsRepin(chain(31337L, "http://anvil:8545"))).isFalse();
    }

    @Test
    @DisplayName("is instantiable by Spring (two constructors: the container one must be marked) and reads its properties")
    void springCanInstantiateIt() {
        try (var ctx = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            ctx.getEnvironment().getPropertySources().addFirst(new org.springframework.core.env.MapPropertySource("t",
                    java.util.Map.of("registerwerk.seed-demo-data", "true")));
            ctx.register(LocalDevChainRepinPolicy.class);
            ctx.refresh();
            assertThat(ctx.getBean(LocalDevChainRepinPolicy.class).allowsRepin(chain(11155111L, "http://anvil:8545")))
                    .isTrue();
        }
    }
}
