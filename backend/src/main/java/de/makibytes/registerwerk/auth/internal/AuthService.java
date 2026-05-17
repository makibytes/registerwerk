package de.makibytes.registerwerk.auth.internal;

import de.makibytes.registerwerk.shared.InvalidCredentialsException;
import de.makibytes.registerwerk.shared.LoginDisabledException;
import de.makibytes.registerwerk.auth.api.RegisterwerkAuthProperties;
import de.makibytes.registerwerk.auth.api.AppUser;
import de.makibytes.registerwerk.auth.api.UserAuthProvider;
import de.makibytes.registerwerk.auth.api.AppUserRepository;
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
            .filter(found -> found.getAuthProvider() == UserAuthProvider.LOCAL)
            .orElseThrow(InvalidCredentialsException::new);
        if (user.getPasswordHash() == null || !encoder.matches(rawPassword, user.getPasswordHash())) {
            throw new InvalidCredentialsException();
        }
        user.setLastLoginAt(Instant.now());
        users.save(user);
        String token = minter.mint(user);
        return new LoginResult(
            token,
            user.getId(),
            user.getRoles().stream().map(Enum::name).toList(),
            props.getTokenTtlSeconds()
        );
    }

    public record LoginResult(String token, UUID userId, List<String> roles, long ttlSeconds) {}
}
