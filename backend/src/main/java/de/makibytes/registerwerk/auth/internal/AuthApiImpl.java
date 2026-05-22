package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.auth.AuthApi;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.AppUserActionToken;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenRepository;
import de.makibytes.registerwerk.auth.api.AppUserActionTokenType;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
import de.makibytes.registerwerk.auth.api.AppUserRole;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
class AuthApiImpl implements AuthApi {

    private final AppUserRepository userRepository;
    private final AppUserActionTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    AuthApiImpl(AppUserRepository userRepository,
                AppUserActionTokenRepository tokenRepository,
                PasswordEncoder passwordEncoder,
                ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> findActiveUser(UUID id) {
        return userRepository.findById(id).filter(AppUser::isEnabled);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUser> findUserByEmail(String email) {
        return userRepository.findByEmailIgnoreCase(email);
    }

    @Override
    public AppUser createInitialCompanyAdmin(UUID legalEntityId, String email, String name, String password, boolean entraEnabled) {
        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            throw new IllegalArgumentException("A user with this email already exists");
        }
        AppUser user = new AppUser();
        user.setEmail(email.trim());
        user.setFullName(name.trim());
        user.setLegalEntityId(legalEntityId);
        user.setRoles(Set.of(AppUserRole.COMPANY_ADMIN));
        user.setEnabled(true);
        user.setAuthProvider(entraEnabled ? UserAuthProvider.ENTRA : UserAuthProvider.LOCAL);
        if (!entraEnabled) {
            if (password == null || password.isBlank()) throw new IllegalArgumentException("password is required");
            user.setPasswordHash(passwordEncoder.encode(password));
        }
        return userRepository.save(user);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AppUserActionToken> findValidActionToken(String cleartext, AppUserActionTokenType type) {
        return tokenRepository.findByTokenHash(sha256Hex(cleartext))
                .filter(t -> t.getTokenType() == type && t.getConsumedAt() == null && t.getExpiresAt().isAfter(java.time.Instant.now()));
    }

    private static String sha256Hex(String input) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
