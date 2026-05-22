package de.makibytes.registerwerk.blockchain.internal;

import de.makibytes.registerwerk.deployment.api.AssetDeployment;
import de.makibytes.registerwerk.deployment.api.AssetDeploymentRepository;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.blockchain.api.BlockchainTransactionService;
import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainConfig;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.shared.EntityNotFoundException;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.AccountMeta;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.core.TransactionInstruction;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Registry-operator administrative controls for SPL Token-2022 mints with extension presets.
 *
 * <p>Uses the Token-2022 program's {@code PermanentDelegate} extension to exercise
 * eWpG regulatory powers (§24 Berichtigung, §26 Einziehung) without relying on the
 * holder's cooperation. The Permanent Delegate is always set to the registry payer.
 *
 * <p>Instruction discriminators reference Token-2022 program v0.6.x:
 * <ul>
 *   <li>Discriminator 32 — {@code PermanentDelegate::Transfer} (TransferChecked with permanent delegate)</li>
 *   <li>Discriminator 33 — {@code PermanentDelegate::Burn} (BurnChecked with permanent delegate)</li>
 *   <li>Discriminator 10 — {@code FreezeAccount} (via freeze authority, same as classic SPL)</li>
 *   <li>Discriminator 11 — {@code ThawAccount}</li>
 * </ul>
 */
@Service
@Transactional
public class SolanaTokenAdminService {

    private static final Logger log = LoggerFactory.getLogger(SolanaTokenAdminService.class);

    private static final String TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

    // Token-2022 instruction discriminators
    private static final byte IX_TRANSFER_CHECKED              = 12;  // also works for permanent delegate
    private static final byte IX_BURN_CHECKED                  = 15;  // also works for permanent delegate
    private static final byte IX_FREEZE_ACCOUNT                = 10;
    private static final byte IX_THAW_ACCOUNT                  = 11;

    private final AssetDeploymentRepository deploymentRepository;
    private final BlockchainClientRegistry clientRegistry;
    private final WalletSigner walletSigner;
    private final ChainConfigRepository chainConfigRepository;

    public SolanaTokenAdminService(
            AssetDeploymentRepository deploymentRepository,
            BlockchainClientRegistry clientRegistry,
            WalletSigner walletSigner,
            ChainConfigRepository chainConfigRepository) {
        this.deploymentRepository = deploymentRepository;
        this.clientRegistry       = clientRegistry;
        this.walletSigner         = walletSigner;
        this.chainConfigRepository = chainConfigRepository;
    }

    // ── Forced transfer (eWpG §24) via Permanent Delegate ────────────────────

    /**
     * Transfers tokens from {@code fromTokenAccount} to {@code toTokenAccount} using the
     * Token-2022 Permanent Delegate extension. Bypasses the holder's authority.
     *
     * <p>Legal basis: eWpG §24 Berichtigung.
     *
     * @param deploymentId       deployment of the SPL_2022_BOND or SPL_2022_CONFIDENTIAL mint
     * @param fromTokenAccount   base58 address of the source token account
     * @param toTokenAccount     base58 address of the destination token account
     * @param amount             token amount in smallest units (raw, without decimals)
     * @param decimals           token decimals (typically 6 for SPL mints)
     * @return future resolving to the transaction signature
     */
    public CompletableFuture<String> permanentDelegateTransfer(
            UUID deploymentId, String fromTokenAccount, String toTokenAccount,
            BigInteger amount, int decimals) {
        log.info("SPL PermanentDelegate forced transfer: deploymentId={} from={} to={} amount={}",
                deploymentId, fromTokenAccount, toTokenAccount, amount);

        AssetDeployment dep = requireDeployment(deploymentId);
        return CompletableFuture.supplyAsync(() -> {
            RpcClient client = solanaClient(dep);
            Account payer = loadPayerAccount();
            String mintAddress = dep.getContractAddress();

            // TransferChecked with permanent delegate authority
            // Accounts: [source (writable), mint (readonly), dest (writable), delegate (signer)]
            List<AccountMeta> accounts = List.of(
                    new AccountMeta(new PublicKey(fromTokenAccount), false, true),
                    new AccountMeta(new PublicKey(mintAddress), false, false),
                    new AccountMeta(new PublicKey(toTokenAccount), false, true),
                    new AccountMeta(payer.getPublicKey(), true, false)
            );

            byte[] data = buildTransferCheckedData(amount.longValueExact(), (byte) decimals);
            TransactionInstruction ix = new TransactionInstruction(new PublicKey(TOKEN_2022_PROGRAM_ID), accounts, data);

            return sendSingleIx(client, payer, ix);
        });
    }

    // ── Force burn (eWpG §26 Einziehung) via Permanent Delegate ──────────────

    /**
     * Burns tokens from {@code tokenAccount} using the Permanent Delegate extension.
     * The holder does not need to consent. Equivalent to eWpG §26 Einziehung.
     *
     * @param deploymentId  deployment of the SPL_2022_BOND or SPL_2022_CONFIDENTIAL mint
     * @param tokenAccount  base58 address of the token account to burn from
     * @param amount        token amount in smallest units
     * @param decimals      token decimals
     * @return future resolving to the transaction signature
     */
    public CompletableFuture<String> permanentDelegateBurn(
            UUID deploymentId, String tokenAccount, BigInteger amount, int decimals) {
        log.info("SPL PermanentDelegate force burn: deploymentId={} account={} amount={}",
                deploymentId, tokenAccount, amount);

        AssetDeployment dep = requireDeployment(deploymentId);
        return CompletableFuture.supplyAsync(() -> {
            RpcClient client = solanaClient(dep);
            Account payer = loadPayerAccount();
            String mintAddress = dep.getContractAddress();

            // BurnChecked with permanent delegate authority
            // Accounts: [token_account (writable), mint (writable), delegate (signer)]
            List<AccountMeta> accounts = List.of(
                    new AccountMeta(new PublicKey(tokenAccount), false, true),
                    new AccountMeta(new PublicKey(mintAddress), false, true),
                    new AccountMeta(payer.getPublicKey(), true, false)
            );

            byte[] data = buildBurnCheckedData(amount.longValueExact(), (byte) decimals);
            TransactionInstruction ix = new TransactionInstruction(new PublicKey(TOKEN_2022_PROGRAM_ID), accounts, data);

            return sendSingleIx(client, payer, ix);
        });
    }

    // ── Freeze / Thaw (via freeze authority) ─────────────────────────────────

    public CompletableFuture<String> freezeTokenAccount(
            UUID deploymentId, String tokenAccount) {
        AssetDeployment dep = requireDeployment(deploymentId);
        return CompletableFuture.supplyAsync(() -> {
            RpcClient client = solanaClient(dep);
            Account payer = loadPayerAccount();
            return sendSingleIx(client, payer, buildFreezeOrThaw(
                    IX_FREEZE_ACCOUNT, new PublicKey(tokenAccount),
                    new PublicKey(dep.getContractAddress()), payer.getPublicKey()));
        });
    }

    public CompletableFuture<String> thawTokenAccount(
            UUID deploymentId, String tokenAccount) {
        AssetDeployment dep = requireDeployment(deploymentId);
        return CompletableFuture.supplyAsync(() -> {
            RpcClient client = solanaClient(dep);
            Account payer = loadPayerAccount();
            return sendSingleIx(client, payer, buildFreezeOrThaw(
                    IX_THAW_ACCOUNT, new PublicKey(tokenAccount),
                    new PublicKey(dep.getContractAddress()), payer.getPublicKey()));
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private AssetDeployment requireDeployment(UUID deploymentId) {
        AssetDeployment dep = deploymentRepository.findById(deploymentId)
                .orElseThrow(() -> new EntityNotFoundException("AssetDeployment", deploymentId));
        if (dep.getContractAddress() == null) {
            throw new IllegalStateException("SPL mint not yet deployed: deploymentId=" + deploymentId);
        }
        return dep;
    }

    private RpcClient solanaClient(AssetDeployment dep) {
        return clientRegistry.getSolanaClient(new ChainDescriptor(Chain.SOLANA, dep.getNetwork()));
    }

    private Account loadPayerAccount() {
        return chainConfigRepository.findByChainTypeAndEnabledTrue(ChainConfig.ChainType.SOLANA).stream()
                .findFirst()
                .map(c -> walletSigner.solanaAccountForChain(c.getId()))
                .orElseThrow(() -> new IllegalStateException("No Solana wallet configured."));
    }

    private String sendSingleIx(RpcClient client, Account payer, TransactionInstruction ix) {
        try {
            Transaction tx = new Transaction();
            tx.addInstruction(ix);
            String blockhash = client.getApi().getRecentBlockhash();
            tx.setRecentBlockHash(blockhash);
            tx.sign(List.of(payer));
            return client.getApi().sendTransaction(tx, List.of(payer), blockhash);
        } catch (RpcException e) {
            throw new RuntimeException("SPL Token-2022 admin tx failed: " + e.getMessage(), e);
        }
    }

    /**
     * Encodes a {@code TransferChecked} instruction data payload.
     *
     * <p>Layout: [12] [amount: u64 LE] [decimals: u8]
     */
    private static byte[] buildTransferCheckedData(long amount, byte decimals) {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(IX_TRANSFER_CHECKED);
        buf.putLong(amount);
        buf.put(decimals);
        return buf.array();
    }

    /**
     * Encodes a {@code BurnChecked} instruction data payload.
     *
     * <p>Layout: [15] [amount: u64 LE] [decimals: u8]
     */
    private static byte[] buildBurnCheckedData(long amount, byte decimals) {
        ByteBuffer buf = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN);
        buf.put(IX_BURN_CHECKED);
        buf.putLong(amount);
        buf.put(decimals);
        return buf.array();
    }

    private static TransactionInstruction buildFreezeOrThaw(
            byte discriminator, PublicKey tokenAccount, PublicKey mint, PublicKey freezeAuthority) {
        return new TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                List.of(
                        new AccountMeta(tokenAccount, false, true),
                        new AccountMeta(mint, false, false),
                        new AccountMeta(freezeAuthority, true, false)
                ),
                new byte[]{discriminator}
        );
    }
}
