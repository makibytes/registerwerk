package de.makibytes.registerwerk.wallet.api;

import de.makibytes.registerwerk.wallet.api.OperatorWallet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OperatorWalletRepository extends JpaRepository<OperatorWallet, UUID> {

    Optional<OperatorWallet> findByName(String name);

    List<OperatorWallet> findByType(OperatorWallet.WalletType type);

    boolean existsByType(OperatorWallet.WalletType type);
}
