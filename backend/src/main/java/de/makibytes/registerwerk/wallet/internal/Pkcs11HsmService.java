package de.makibytes.registerwerk.wallet.internal;

import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Provider;
import java.security.Security;
import java.security.Signature;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal PKCS#11 session facade. Private keys are returned by the provider only as opaque token
 * handles and never as encodable key material.
 */
@Service
public class Pkcs11HsmService {

    private final HsmProperties properties;
    private final Map<HsmProperties.Profile, Pkcs11Adapter> adapters;
    private volatile Session session;

    public Pkcs11HsmService(HsmProperties properties, List<Pkcs11Adapter> adapters) {
        this.properties = properties;
        this.adapters = new EnumMap<>(HsmProperties.Profile.class);
        adapters.forEach(adapter -> this.adapters.put(adapter.profile(), adapter));
    }

    public boolean isEnabled() {
        return properties.isEnabled();
    }

    public byte[] signDigest(String keyAlias, byte[] digest) {
        if (digest == null || digest.length != 32) {
            throw new IllegalArgumentException("PKCS#11 EVM digest must be exactly 32 bytes");
        }
        if (keyAlias == null || keyAlias.isBlank()) {
            throw new IllegalArgumentException("PKCS#11 key alias must be configured");
        }
        try {
            Session active = session();
            Key key = active.keyStore().getKey(keyAlias, properties.getPin().toCharArray());
            if (!(key instanceof PrivateKey privateKey)) {
                throw new IllegalStateException("No private key named '" + keyAlias + "' exists on the HSM token");
            }
            Signature signature = Signature.getInstance(active.algorithm(), active.provider());
            signature.initSign(privateKey);
            signature.update(digest);
            return signature.sign();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("PKCS#11 signing failed for alias '" + keyAlias + "': "
                    + e.getMessage(), e);
        }
    }

    private Session session() {
        Session current = session;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (session == null) {
                session = openSession();
            }
            return session;
        }
    }

    private Session openSession() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("PKCS#11 signer requested but REGISTERWERK_HSM_ENABLED is false");
        }
        String config = properties.getProviderConfig();
        if (config == null || config.isBlank() || !Files.isRegularFile(Path.of(config))) {
            throw new IllegalStateException("REGISTERWERK_HSM_PROVIDER_CONFIG must name a readable SunPKCS11 config file");
        }
        if (properties.getPin() == null || properties.getPin().isBlank()) {
            throw new IllegalStateException("REGISTERWERK_HSM_PIN must be set when PKCS#11 is enabled");
        }
        Pkcs11Adapter adapter = adapters.get(properties.getProfile());
        if (adapter == null) {
            throw new IllegalStateException("No PKCS#11 adapter for profile " + properties.getProfile());
        }
        try {
            Provider base = Security.getProvider("SunPKCS11");
            if (base == null) {
                throw new IllegalStateException("This JVM does not provide SunPKCS11");
            }
            Provider provider = base.configure(config);
            KeyStore keyStore = KeyStore.getInstance("PKCS11", provider);
            keyStore.load(null, properties.getPin().toCharArray());
            return new Session(provider, keyStore, adapter.digestSignatureAlgorithm(properties));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Could not open " + properties.getProfile()
                    + " PKCS#11 session: " + e.getMessage(), e);
        }
    }

    private record Session(Provider provider, KeyStore keyStore, String algorithm) {}
}
