package de.makibytes.registerwerk.application.auth;

import de.makibytes.registerwerk.application.exception.InvalidCredentialsException;
import de.makibytes.registerwerk.application.exception.LoginDisabledException;
import de.makibytes.registerwerk.config.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.domain.entity.AppUser;
import de.makibytes.registerwerk.infrastructure.persistence.jpa.AppUserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final JwtMintingService minter;
    private final RegisterwerkAuthProperties props;

    public AuthService(
            AppUserRepository users,
            PasswordEncoder encoder,
            JwtMintingService minter,
            RegisterwerkAuthProperties props) {
        this.users = users;
        this.encoder = encoder;
        this.minter = minter;
        this.props = props;
    }

    @Transactional
    public LoginResult login(String email, String rawPassword) {
        if (props.isEntraEnabled()) {
            throw new LoginDisabledException();
        }
        AppUser user = users.findByEmailIgnoreCase(email)
            .filter(AppUser::isEnabled)
            .orElseThrow(InvalidCredentialsException::new);
        if (!encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);
        String token = minter.mint(user);
        return new LoginResult(token, user.getId(), List.of(user.getRole()), props.getTokenTtlSeconds());
    }

    public record LoginResult(String token, UUID userId, List<String> roles, long ttlSeconds) {}
}
