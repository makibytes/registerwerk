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
     * extension set empty. Use {@link #createSplToken2022(UUID, Network, String, SplExtensionSet)}
     * to create a mint with security-grade extensions.
     */
    public CompletableFuture<String> createSplToken2022(UUID assetId, Network network, String ownerAddress) {
        return createSplToken2022(assetId, network, ownerAddress, SplExtensionSet.NONE);
    }

    /**
     * Creates a new SPL Token-2022 mint with the specified extension preset.
     *
     * <p>Extension-init instructions are prepended to the transaction in Token-2022 protocol order
     * (all extension inits MUST precede {@code InitializeMint}). The extension preset determines
     * which regulatory and privacy features are enabled on the mint:
     * <ul>
     *   <li>{@code BOND} — Interest-Bearing + Permanent Delegate + Transfer Hook + MintCloseAuthority</li>
     *   <li>{@code CONFIDENTIAL} — Confidential Transfers + Permanent Delegate + Transfer Hook</li>
     *   <li>{@code NONE} — no extensions (same as the no-arg overload)</li>
     * </ul>
     *
     * <p><b>Token-2022 extension instruction ordering (mandatory):</b>
     * The Solana Token-2022 program requires extension-initialization instructions to appear
     * BEFORE the {@code InitializeMint} instruction in the transaction. This is enforced by
     * the program and will revert if violated.
     *
     * <p><b>Reference:</b> Token-2022 program v0.6.x — spl_token_2022::instruction
     *
     * @param assetId      Registerwerk asset UUID (attached as memo and metadata)
     * @param network      MAINNET or TESTNET
     * @param ownerAddress base58 mint authority public key
     * @param extensions   which extension preset to initialize
     * @return future resolving to the transaction signature
     */
    public CompletableFuture<String> createSplToken2022(
            UUID assetId, Network network, String ownerAddress, SplExtensionSet extensions) {
        log.info("Creating SPL Token-2022 mint: assetId={} network={} extensions={}", assetId, network, extensions);

        return CompletableFuture.supplyAsync(() -> {
            ChainDescriptor descriptor = new ChainDescriptor(Chain.SOLANA, network);
            RpcClient client = blockchainClientRegistry.getSolanaClient(descriptor);

            Account payer = loadPayerAccount();
            Account mintAccount = new Account();

            try {
                long rentExemptLamports = computeRentExemptLamports(extensions);

                Transaction tx = new Transaction();

                // 1. Create the mint account (size depends on extensions)
                tx.addInstruction(SystemProgram.createAccount(
                        payer.getPublicKey(),
                        mintAccount.getPublicKey(),
                        rentExemptLamports,
                        mintAccountSize(extensions),
                        new PublicKey(TOKEN_2022_PROGRAM_ID)
                ));

                // 2. Extension-init instructions (MUST precede InitializeMint)
                PublicKey mintAuthority = new PublicKey(ownerAddress.isBlank()
                        ? payer.getPublicKey().toBase58() : ownerAddress);

                switch (extensions) {
                    case BOND -> {
                        // 2a. InitializeMintCloseAuthority (ix 25): registry payer closes at maturity
                        tx.addInstruction(buildMintCloseAuthorityIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                        // 2b. InitializePermanentDelegate (ix 35): registry payer can force-transfer/burn (eWpG §24/26)
                        tx.addInstruction(buildPermanentDelegateIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                        // 2c. InitializeTransferHook (ix 36): compliance hook for whitelist + AML checks
                        tx.addInstruction(buildTransferHookIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                        // 2d. InitializeInterestBearingMint (ix 33+subtype 0): bond coupon accrual
                        tx.addInstruction(buildInterestBearingMintIx(mintAccount.getPublicKey(), payer.getPublicKey(), (short) 0));
                    }
                    case CONFIDENTIAL -> {
                        // 2a. InitializePermanentDelegate: regulatory force-transfer in encrypted state
                        tx.addInstruction(buildPermanentDelegateIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                        // 2b. InitializeTransferHook: whitelist enforcement
                        tx.addInstruction(buildTransferHookIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                        // 2c. InitializeConfidentialTransferMint (ix 27+subtype 0): ZK-encrypted balances
                        tx.addInstruction(buildConfidentialTransferMintIx(mintAccount.getPublicKey(), payer.getPublicKey()));
                    }
                    case NONE -> { /* no extension-init instructions */ }
                }

                // 3. Memo identifying the asset
                tx.addInstruction(MemoProgram.writeUtf8(
                        payer.getPublicKey(),
                        "registerwerk-asset:" + assetId + ":ext=" + extensions.name()
                ));

                // 4. InitializeMint — MUST come after all extension-init instructions
                byte[] initMintData = buildInitializeMintInstruction(DEFAULT_DECIMALS, mintAuthority, payer.getPublicKey());
                tx.addInstruction(new org.p2p.solanaj.core.TransactionInstruction(
                        new PublicKey(TOKEN_2022_PROGRAM_ID),
                        java.util.List.of(
                                new org.p2p.solanaj.core.AccountMeta(mintAccount.getPublicKey(), false, true),
                                new org.p2p.solanaj.core.AccountMeta(
                                        new PublicKey("SysvarRent111111111111111111111111111111111"), false, false)
                        ),
                        initMintData
                ));

                String recentBlockhash = client.getApi().getLatestBlockhash().getValue().getBlockhash();
                tx.setRecentBlockHash(recentBlockhash);
                tx.sign(java.util.List.of(payer, mintAccount));

                String signature = client.getApi().sendTransaction(tx, java.util.List.of(payer, mintAccount), recentBlockhash);
                log.info("SPL Token-2022 mint created: assetId={} extensions={} mintAddress={} sig={}",
                        assetId, extensions, mintAccount.getPublicKey().toBase58(), signature);
                return signature;

            } catch (RpcException e) {
                throw new RuntimeException("SPL Token-2022 creation failed for assetId=" + assetId
                        + " extensions=" + extensions + ": " + e.getMessage(), e);
            }
        });
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

                String recentBlockhash = client.getApi().getLatestBlockhash().getValue().getBlockhash();
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

                String blockhash = client.getApi().getLatestBlockhash().getValue().getBlockhash();
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

                String blockhash = client.getApi().getLatestBlockhash().getValue().getBlockhash();
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

    // ── Token-2022 extension-init instruction builders ────────────────────────
    //
    // Byte layout references: Token-2022 program v0.6.x
    // https://github.com/solana-labs/solana-program-library/blob/master/token/program-2022/src/instruction.rs
    //
    // All extension-init instructions must precede InitializeMint in the transaction.
    // Account layout (shared): [mint_account (writable, signer = false)]

    /**
     * Builds an {@code InitializePermanentDelegate} (Token-2022 ix 35) instruction.
     *
     * <p>Layout: [35] [delegate: 32 bytes pubkey]
     *
     * <p>Legal basis: eWpG §24 Berichtigung, §26 Einziehung — the delegate can transfer
     * or burn any amount unconditionally, enabling court-ordered register corrections.
     */
    private org.p2p.solanaj.core.TransactionInstruction buildPermanentDelegateIx(
            PublicKey mint, PublicKey delegate) {
        byte[] delegateBytes = delegate.toByteArray();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 32);
        buf.put((byte) 35);  // InitializePermanentDelegate discriminator
        buf.put(delegateBytes, 0, Math.min(delegateBytes.length, 32));
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                java.util.List.of(new org.p2p.solanaj.core.AccountMeta(mint, false, true)),
                buf.array()
        );
    }

    /**
     * Builds an {@code InitializeMintCloseAuthority} (Token-2022 ix 25) instruction.
     *
     * <p>Layout: [25] [COption::Some = 1] [close_authority: 32 bytes]
     *
     * <p>Used for bond mints: allows the registry to close the mint account at maturity,
     * recovering rent. Corresponds to eWpG §26 Einziehung (compulsory cancellation).
     */
    private org.p2p.solanaj.core.TransactionInstruction buildMintCloseAuthorityIx(
            PublicKey mint, PublicKey closeAuthority) {
        byte[] caBytes = closeAuthority.toByteArray();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 1 + 32);
        buf.put((byte) 25);  // InitializeMintCloseAuthority discriminator
        buf.put((byte) 1);   // COption::Some
        buf.put(caBytes, 0, Math.min(caBytes.length, 32));
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                java.util.List.of(new org.p2p.solanaj.core.AccountMeta(mint, false, true)),
                buf.array()
        );
    }

    /**
     * Builds an {@code InterestBearingMint::Initialize} (Token-2022 ix 33, sub-ix 0) instruction.
     *
     * <p>Layout: [33] [0] [rate_authority COption::Some = 1] [32-byte rate_authority]
     *             [rate_bps: i16 little-endian]
     *
     * <p>The rate is set in basis points (100 bps = 1%). Actual rate is updated via
     * {@code UpdateRateAuthority} by the operator after deployment. Initial rate is 0.
     *
     * @param rateAuthority  The account that can update the interest rate (registry payer).
     * @param initialRateBps Initial interest rate in basis points (use 0; update post-deploy).
     */
    private org.p2p.solanaj.core.TransactionInstruction buildInterestBearingMintIx(
            PublicKey mint, PublicKey rateAuthority, short initialRateBps) {
        byte[] raBytes = rateAuthority.toByteArray();
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 1 + 1 + 32 + 2);
        buf.put((byte) 33);  // InterestBearingMintInstruction outer discriminator
        buf.put((byte) 0);   // Initialize sub-instruction
        buf.put((byte) 1);   // COption::Some for rate_authority
        buf.put(raBytes, 0, Math.min(raBytes.length, 32));
        buf.order(java.nio.ByteOrder.LITTLE_ENDIAN).putShort(initialRateBps);
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                java.util.List.of(new org.p2p.solanaj.core.AccountMeta(mint, false, true)),
                buf.array()
        );
    }

    /**
     * Builds an {@code TransferHookInstruction::Initialize} (Token-2022 ix 36, sub-ix 0) instruction.
     *
     * <p>Layout: [36] [0] [authority COption::Some = 1] [32-byte authority]
     *             [COption for hook program — Some = 1] [32-byte hook program id]
     *
     * <p>The hook program is the Registerwerk transfer-hook Anchor program (whitelist + AML).
     * Its program ID must be configured and deployed before bond mints can be created.
     * The hook program ID is read from {@code registerwerk.chains.solana.transfer-hook-program}.
     */
    private org.p2p.solanaj.core.TransactionInstruction buildTransferHookIx(
            PublicKey mint, PublicKey authority) {
        // Hook program ID — configured externally; use system program as placeholder until deployed.
        // In production: read from SolanaProperties.getTransferHookProgramId()
        String hookProgramId = "11111111111111111111111111111111"; // SystemProgram — placeholder only
        byte[] authBytes = authority.toByteArray();
        byte[] hookBytes = new PublicKey(hookProgramId).toByteArray();

        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 1 + 1 + 32 + 1 + 32);
        buf.put((byte) 36);   // TransferHookInstruction outer discriminator
        buf.put((byte) 0);    // Initialize sub-instruction
        buf.put((byte) 1);    // COption::Some for authority
        buf.put(authBytes, 0, Math.min(authBytes.length, 32));
        buf.put((byte) 1);    // COption::Some for hook program id
        buf.put(hookBytes, 0, Math.min(hookBytes.length, 32));
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                java.util.List.of(new org.p2p.solanaj.core.AccountMeta(mint, false, true)),
                buf.array()
        );
    }

    /**
     * Builds a {@code ConfidentialTransferInstruction::InitializeMint} (Token-2022 ix 27, sub-ix 0) instruction.
     *
     * <p>Layout: [27] [0]
     *             [auditor_elgamal_pubkey COption::Some = 1] [64-byte ElGamal pubkey]
     *             [withdraw_withheld_authority_elgamal_pubkey COption::Some = 1] [64-byte]
     *             [auto_approve_new_accounts: bool (1 byte)]
     *
     * <p>The auditor ElGamal public key is the registry's auditing key that can decrypt all transfers.
     * <b>Important:</b> The ElGamal key pair must be generated and its public key stored in
     * {@code SolanaProperties.getConfidentialTransferAuditorElgamalPubkey()}.
     * This is a deployment blocker — the key must be generated and operator policy signed before
     * any CONFIDENTIAL mint can be created.
     *
     * <p>This implementation uses a zeroed 64-byte key as a placeholder. Replace with the
     * actual ElGamal public key from operator configuration before mainnet use.
     */
    private org.p2p.solanaj.core.TransactionInstruction buildConfidentialTransferMintIx(
            PublicKey mint, PublicKey authority) {
        byte[] zeroElgamal = new byte[64]; // placeholder — must be replaced with real ElGamal pubkey
        java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(1 + 1 + 1 + 64 + 1 + 64 + 1);
        buf.put((byte) 27);   // ConfidentialTransferInstruction outer discriminator
        buf.put((byte) 0);    // InitializeMint sub-instruction
        buf.put((byte) 1);    // COption::Some for auditor key
        buf.put(zeroElgamal); // auditor ElGamal pubkey (64 bytes) — replace with real key
        buf.put((byte) 1);    // COption::Some for withdraw_withheld authority key
        buf.put(zeroElgamal); // withdraw withheld authority ElGamal pubkey
        buf.put((byte) 0);    // auto_approve_new_accounts = false (explicit approval required)
        return new org.p2p.solanaj.core.TransactionInstruction(
                new PublicKey(TOKEN_2022_PROGRAM_ID),
                java.util.List.of(new org.p2p.solanaj.core.AccountMeta(mint, false, true)),
                buf.array()
        );
    }

    /**
     * Computes the rent-exempt lamports needed for a Token-2022 mint with the given extension set.
     *
     * <p>Base mint size = 82 bytes. Each extension adds additional storage.
     * The rent-exempt threshold is approximately 2,039,280 lamports for 82 bytes (at the
     * current rent rate of ~1.461 lamports/byte/epoch × 200 epochs minimum).
     *
     * <p>Extension size additions (approximate, from Token-2022 source):
     * <ul>
     *   <li>PermanentDelegate: 40 bytes</li>
     *   <li>MintCloseAuthority: 36 bytes</li>
     *   <li>InterestBearingMint: 72 bytes</li>
     *   <li>TransferHook: 64 bytes</li>
     *   <li>ConfidentialTransferMint: 132 bytes</li>
     * </ul>
     */
    private static long computeRentExemptLamports(SplExtensionSet extensions) {
        long bytesPerLamport = 1461L;
        long size = mintAccountSize(extensions);
        // rent = lamports_per_byte_year * bytes * 2 (for 2 years minimum)
        return Math.max(2_039_280L, size * 2 * bytesPerLamport / 1000);
    }

    private static long mintAccountSize(SplExtensionSet extensions) {
        return switch (extensions) {
            case NONE         -> 82L;
            case BOND         -> 82L + 40L + 36L + 72L + 64L; // base + PD + MCA + IBMint + Hook = 294
            case CONFIDENTIAL -> 82L + 40L + 132L + 64L;       // base + PD + CTMint + Hook = 318
        };
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
