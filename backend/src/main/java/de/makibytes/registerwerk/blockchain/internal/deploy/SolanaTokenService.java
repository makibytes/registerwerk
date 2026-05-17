package de.makibytes.registerwerk.blockchain.internal.deploy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import org.p2p.solanaj.core.Account;
import org.p2p.solanaj.core.PublicKey;
import org.p2p.solanaj.core.Transaction;
import org.p2p.solanaj.programs.MemoProgram;
import org.p2p.solanaj.programs.SystemProgram;
import org.p2p.solanaj.rpc.RpcClient;
import org.p2p.solanaj.rpc.RpcException;
import de.makibytes.registerwerk.blockchain.api.BlockchainClientRegistry;
import de.makibytes.registerwerk.wallet.api.WalletSigner;
import de.makibytes.registerwerk.chain.api.ChainConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import de.makibytes.registerwerk.chain.api.Chain;
import de.makibytes.registerwerk.chain.api.ChainDescriptor;
import de.makibytes.registerwerk.chain.api.Network;

/**
 * Creates and administers SPL token mints on Solana.
 *
 * <p>Implementation sends on-chain transactions to create a new SPL or SPL Token-2022 mint account
 * and provides regulatory admin controls aligned with eWpG and MiCAR requirements.
 *
 * <p>Admin powers implemented:
 * <ul>
 *   <li>{@link #freezeTokenAccount}  — AWG §17, GwG §40: freeze a token account (SPL FreezeAccount)</li>
 *   <li>{@link #thawTokenAccount}    — lift a previous freeze (SPL ThawAccount)</li>
 * </ul>
 *
 * <p>Note: The SPL Token program's freeze authority is set to the registry payer wallet
 * at mint creation time so that these admin operations are possible.
 *
 * <p>forcedTransfer and forceBurn are not natively available in SPL Token. To perform
 * a forced transfer under eWpG §24, the operator must: freeze the source account,
 * use a delegate-based mechanism (set authority to registry), transfer, then thaw the
 * destination. For full Solana regulatory control, consider the Token-2022 program's
 * permanent delegate extension, which allows unconditional transfers by the authority.
 */
@Service
public class SolanaTokenService {

    private static final Logger log = LoggerFactory.getLogger(SolanaTokenService.class);

    /** Default decimals for new SPL token mints. */
    private static final int DEFAULT_DECIMALS = 6;

    /** SPL Token program address. */
    private static final String TOKEN_PROGRAM_ID = "TokenkegQfeZyiNwAJbNbGKPFXCWuBvf9Ss623VQ5DA";
    /** SPL Token-2022 program address. */
    private static final String TOKEN_2022_PROGRAM_ID = "TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb";

    // SPL Token instruction discriminators
    private static final byte IX_FREEZE_ACCOUNT = 10;
    private static final byte IX_THAW_ACCOUNT   = 11;

    private final BlockchainClientRegistry blockchainClientRegistry;
    private final WalletSigner             walletSigner;
    private final ChainConfigRepository    chainConfigRepository;

    public SolanaTokenService(BlockchainClientRegistry blockchainClientRegistry,
                               WalletSigner walletSigner,
                               ChainConfigRepository chainConfigRepository) {
        this.blockchainClientRegistry = blockchainClientRegistry;
        this.walletSigner             = walletSigner;
        this.chainConfigRepository    = chainConfigRepository;
    }

    /**
     * Creates a new SPL token mint for the given asset on the specified Solana network.
     *
     * <p>The freeze authority is set to the registry payer wallet so that
     * {@link #freezeTokenAccount} / {@link #thawTokenAccount} can be invoked later.
     *
     * @param assetId      ID of the asset being tokenised (attached as memo)
     * @param network      MAINNET or TESTNET (DEVNET)
     * @param ownerAddress base58 public key of the mint authority
     * @return future resolving to the transaction signature
     */
    public CompletableFuture<String> createSplToken(UUID assetId, Network network, String ownerAddress) {
        return createSplToken(assetId, network, ownerAddress, TOKEN_PROGRAM_ID, "SPL");
    }

    /**
     * Creates a new SPL Token-2022 mint without extensions for the given asset.
     *
     * <p>This enables the Token-2022 mint path in the issuance wizard while keeping the
     * extension set empty for now. Additional extensions can be layered in later without
     * changing the basic deployment flow.
     */
    public CompletableFuture<String> createSplToken2022(UUID assetId, Network network, String ownerAddress) {
        return createSplToken(assetId, network, ownerAddress, TOKEN_2022_PROGRAM_ID, "SPL_2022");
    }

    private CompletableFuture<String> createSplToken(
            UUID assetId, Network network, String ownerAddress, String programId, String tokenFamily) {
        log.info("Creating SPL token mint: assetId={}, network={}, mintAuthority={}", assetId, network,
                ownerAddress);

        return CompletableFuture.supplyAsync(() -> {
            ChainDescriptor descriptor = new ChainDescriptor(Chain.SOLANA, network);
            RpcClient client = blockchainClientRegistry.getSolanaClient(descriptor);

            Account payer = loadPayerAccount();
            Account mintAccount = new Account(); // fresh keypair for the new mint

            try {
                // Rent-exempt balance for an 82-byte mint account
                long rentExemptLamports = 2_039_280L;

                Transaction tx = new Transaction();

                // 1. Create the mint account funded with rent-exempt SOL
                tx.addInstruction(SystemProgram.createAccount(
                        payer.getPublicKey(),
                        mintAccount.getPublicKey(),
                        rentExemptLamports,
                        82L,               // mint account size
                        new PublicKey(programId)
                ));

                // 2. Add a memo identifying the asset (best-effort; non-critical)
                tx.addInstruction(MemoProgram.writeUtf8(
                        payer.getPublicKey(),
                        "registerwerk-asset:" + assetId
                ));

                // 3. InitializeMint: set mint_authority + freeze_authority = registry payer
                //    Freeze authority enables FreezeAccount / ThawAccount for regulatory control.
                PublicKey mintAuthority = new PublicKey(ownerAddress.isBlank()
                        ? payer.getPublicKey().toBase58() : ownerAddress);
                // Freeze authority is always the registry payer so admin ops are available.
                byte[] initMintData = buildInitializeMintInstruction(
                        DEFAULT_DECIMALS, mintAuthority, payer.getPublicKey());

                org.p2p.solanaj.core.AccountMeta mintMeta =
                        new org.p2p.solanaj.core.AccountMeta(mintAccount.getPublicKey(), false, true);
                org.p2p.solanaj.core.AccountMeta rentSysvar =
                        new org.p2p.solanaj.core.AccountMeta(
                                new PublicKey("SysvarRent111111111111111111111111111111111"), false, false);

                tx.addInstruction(new org.p2p.solanaj.core.TransactionInstruction(
                        new PublicKey(programId),
                        java.util.List.of(mintMeta, rentSysvar),
                        initMintData
                ));

                String recentBlockhash = client.getApi().getRecentBlockhash();
                tx.setRecentBlockHash(recentBlockhash);
                tx.sign(java.util.List.of(payer, mintAccount));

                String signature = client.getApi().sendTransaction(
                        tx,
                        java.util.List.of(payer, mintAccount),
                        recentBlockhash
                );
                log.info("{} token mint created: assetId={} mintAddress={} signature={}",
                        tokenFamily, assetId, mintAccount.getPublicKey().toBase58(), signature);
                return signature;

            } catch (RpcException e) {
                throw new RuntimeException(tokenFamily + " token creation failed for assetId=" + assetId
                        + ": " + e.getMessage(), e);
            }
        });
    }

    // ── Regulatory admin controls ─────────────────────────────────────────────

    /**
     * Freezes a specific SPL token account, blocking the holder from transferring tokens.
     *
     * <p>Legal basis: AWG §17 (sanctions list enforcement); GwG §40 (AML freeze);
     * MiCAR Art. 36 (competent-authority asset freeze).
     *
     * <p>Uses the SPL Token {@code FreezeAccount} instruction (discriminator 10).
     * The registry payer must hold the freeze authority for the given mint.
     *
     * @param tokenAccountAddress Base58 address of the token account (not the wallet!) to freeze
     * @param mintAddress         Base58 address of the mint
     * @param network             MAINNET or TESTNET
     * @return transaction signature
     */
    public CompletableFuture<String> freezeTokenAccount(
            String tokenAccountAddress, String mintAddress, Network network) {
        log.info("ADMIN freeze SPL token account={} mint={} network={}", tokenAccountAddress, mintAddress, network);

        return CompletableFuture.supplyAsync(() -> {
            ChainDescriptor descriptor = new ChainDescriptor(Chain.SOLANA, network);
            RpcClient client = blockchainClientRegistry.getSolanaClient(descriptor);
            Account payer = loadPayerAccount(); // freeze authority

            try {
                Transaction tx = new Transaction();
                tx.addInstruction(buildFreezeOrThawInstruction(
                        IX_FREEZE_ACCOUNT,
                        new PublicKey(tokenAccountAddress),
                        new PublicKey(mintAddress),
                        payer.getPublicKey()
                ));

                String blockhash = client.getApi().getRecentBlockhash();
                tx.setRecentBlockHash(blockhash);
                tx.sign(java.util.List.of(payer));

                String sig = client.getApi().sendTransaction(tx, java.util.List.of(payer), blockhash);
                log.info("SPL FreezeAccount succeeded: account={} sig={}", tokenAccountAddress, sig);
                return sig;
            } catch (RpcException e) {
                throw new RuntimeException("SPL FreezeAccount failed for account=" + tokenAccountAddress
                        + ": " + e.getMessage(), e);
            }
        });
    }

    /**
     * Thaws (unfreezes) a previously frozen SPL token account.
     *
     * <p>Uses the SPL Token {@code ThawAccount} instruction (discriminator 11).
     *
     * @param tokenAccountAddress Base58 address of the token account to thaw
     * @param mintAddress         Base58 address of the mint
     * @param network             MAINNET or TESTNET
     * @return transaction signature
     */
    public CompletableFuture<String> thawTokenAccount(
            String tokenAccountAddress, String mintAddress, Network network) {
        log.info("ADMIN thaw SPL token account={} mint={} network={}", tokenAccountAddress, mintAddress, network);

        return CompletableFuture.supplyAsync(() -> {
            ChainDescriptor descriptor = new ChainDescriptor(Chain.SOLANA, network);
            RpcClient client = blockchainClientRegistry.getSolanaClient(descriptor);
            Account payer = loadPayerAccount(); // freeze authority

            try {
                Transaction tx = new Transaction();
                tx.addInstruction(buildFreezeOrThawInstruction(
                        IX_THAW_ACCOUNT,
                        new PublicKey(tokenAccountAddress),
                        new PublicKey(mintAddress),
                        payer.getPublicKey()
                ));

                String blockhash = client.getApi().getRecentBlockhash();
                tx.setRecentBlockHash(blockhash);
                tx.sign(java.util.List.of(payer));

                String sig = client.getApi().sendTransaction(tx, java.util.List.of(payer), blockhash);
                log.info("SPL ThawAccount succeeded: account={} sig={}", tokenAccountAddress, sig);
                return sig;
            } catch (RpcException e) {
                throw new RuntimeException("SPL ThawAccount failed for account=" + tokenAccountAddress
                        + ": " + e.getMessage(), e);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Account loadPayerAccount() {
        // Resolve the Solana default wallet from any configured Solana chain
        return chainConfigRepository.findByChainTypeAndEnabledTrue(
                de.makibytes.registerwerk.chain.api.ChainConfig.ChainType.SOLANA).stream()
                .findFirst()
                .map(c -> walletSigner.solanaAccountForChain(c.getId()))
                .orElseThrow(() -> new IllegalStateException(
                        "No Solana wallet default configured. Add a Solana wallet via the Operator Portal → Wallets."));
    }

    /**
     * Encodes an SPL Token {@code InitializeMint} instruction payload.
     *
     * <p>Layout:
     * <pre>
     *   [0]      discriminator       = 0 (InitializeMint)
     *   [1]      decimals            = uint8
     *   [2..33]  mint_authority      = Pubkey (32 bytes)
     *   [34]     has_freeze_authority = COption::Some = 1
     *   [35..66] freeze_authority    = Pubkey (32 bytes)
     * </pre>
     */
    private static byte[] buildInitializeMintInstruction(
            int decimals, PublicKey mintAuthority, PublicKey freezeAuthority) {
        byte[] authorityBytes = mintAuthority.toByteArray();
        byte[] freezeBytes    = freezeAuthority.toByteArray();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(67);
        buf.put((byte) 0);                              // discriminator: InitializeMint
        buf.put((byte) decimals);
        buf.put(authorityBytes, 0, Math.min(authorityBytes.length, 32));
        buf.put((byte) 1);                              // COption::Some
        buf.put(freezeBytes, 0, Math.min(freezeBytes.length, 32));
        return buf.array();
    }

    /**
     * Encodes a {@code FreezeAccount} (discriminator 10) or {@code ThawAccount} (discriminator 11)
     * SPL Token instruction.
     *
     * <p>Accounts: [tokenAccount (writable), mint (readonly), freezeAuthority (signer)]
     * Data: [discriminator (1 byte)]
     */
    private static org.p2p.solanaj.core.TransactionInstruction buildFreezeOrThawInstruction(
            byte discriminator, PublicKey tokenAccount, PublicKey mint, PublicKey freezeAuthority) {
        java.util.List<org.p2p.solanaj.core.AccountMeta> accounts = java.util.List.of(
                new org.p2p.solanaj.core.AccountMeta(tokenAccount, false, true),
                new org.p2p.solanaj.core.AccountMeta(mint, false, false),
                new org.p2p.solanaj.core.AccountMeta(freezeAuthority, true, false)
        );
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_PROGRAM_ID),
                accounts,
                new byte[]{discriminator}
        );
    }
}
