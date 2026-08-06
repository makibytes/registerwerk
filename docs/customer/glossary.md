---
title: Glossary
description: Every term used in this documentation, defined plainly.
---

# Glossary

Plain definitions. Where a term has a precise technical meaning that differs from everyday usage, the difference is stated.

---

## Finance

**Basis point (bps)**
: One hundredth of a percent. 100 bps = 1%. Used because "the rate rose by 1%" is ambiguous — from 4% to 5%, or from 4% to 4.04%? Basis points remove the ambiguity.

**Bond**
: A loan divided into equal pieces so that many lenders can each take one. The borrower pays interest and repays the face value at maturity.

**Collateral**
: Something valuable pledged to secure a loan. If the borrower does not repay, the lender may sell it.

**Corporate action**
: Anything an issuer does that affects holders as holders — paying a coupon, splitting units, repaying principal.

**Coupon**
: The interest a bond pays. The name survives from paper bonds, where you clipped a coupon off the certificate to claim each payment.

**Custodian**
: An institution that holds securities on behalf of others. In a collective register entry, the custodian is the recorded holder.

**Delivery versus payment (DvP)**
: Settling so that the security moves if and only if the payment moves. Removes the risk that one side performs and the other does not.

**Ex date**
: From this date a security trades *without* an upcoming payment. Buy after it and the payment belongs to the seller.

**Face value** (also *nominal*, *par*)
: The amount printed on the instrument — what gets repaid at maturity. **Not** the price. A €1,000 bond can trade at €960.

**Issuer**
: The organisation that creates a security and owes what it promises.

**Liquidity**
: How easily something can be turned into cash without moving its price. A security nobody will buy is illiquid.

**Loan-to-value (LTV)**
: How much you have borrowed as a percentage of your collateral's value. Borrow €50,000 against €100,000 of collateral and your LTV is 50%.

**LLTV**
: The LTV threshold above which a loan may be liquidated.

**Maturity**
: The date a bond ends and its face value is repaid.

**Nominal amount**
: The face value a holder holds. What the register records. Not market value.

**Primary market**
: The issuer selling to investors. Money reaches the issuer. Happens once.

**Record date**
: The instant the register is photographed to decide who is entitled to a payment. Hold on this date and the payment is yours even if you sell tomorrow.

**Redemption**
: Repaying a security's principal and retiring it.

**Repo** (*repurchase agreement*)
: A sale with an agreed buy-back at a higher price. Economically a secured loan; the price difference is the interest. Structured as a sale because outright title survives insolvency better than a security interest.

**Secondary market**
: Investors selling to each other. The issuer is not a party and receives nothing.

**Settlement**
: Completing a trade — the securities and the money actually changing hands. Distinct from agreeing it.

**Zero-coupon bond**
: A bond paying no interest, sold below face value instead. Buy at €800, receive €1,000 at maturity.

---

## Blockchain

**Blockchain**
: A shared ledger, maintained by many parties, where entries cannot be quietly altered once recorded.

**Burning**
: Destroying tokens. Supply decreases. Irreversible.

**Contract address**
: Where a smart contract lives on a chain. Public; anyone can inspect it.

**ERC-3643** (also *T-REX*)
: A token standard for regulated securities. Checks eligibility before every transfer and reverts non-compliant ones on-chain.

**ERC-20**
: The common fungible token standard. Simple and universally supported, with **no** concept of who may hold it.

**Gas**
: The fee paid to have a transaction processed.

**Mainnet / testnet**
: The real network, where value is real. And the practice network, where it is not.

**Minting**
: Creating tokens that did not exist. The opposite of burning.

**ONCHAINID**
: An on-chain identity contract holding a party's verified claims under ERC-3643.

**Private key**
: The secret that authorises actions from a wallet address. Cannot be reset, recovered or reissued. Lose it and you lose the ability to move the tokens.

**Revert**
: A transaction failing and undoing entirely. A compliance check that fails causes a revert — nothing partial happens.

**Smart contract**
: A program on a blockchain. Runs exactly as written, when called, without anybody deciding to let it.

**Stablecoin**
: A token intended to hold a steady value against a currency.

**Token**
: A unit recorded in a smart contract. Here, the on-chain representation of a security — the mechanism, not the security itself.

**Transaction hash**
: The identifier of a transaction. Your receipt; look it up on a block explorer.

**Wallet**
: Software holding a private key. It does not contain tokens — the contract records a balance against your address.

---

## Registerwerk

**Asset**
: A security in the registry. Formally: the register's record of an instrument.

**Audit log**
: The tamper-evident record of every state-changing operation. Hash-chained, so alteration is detectable.

**Endpoint**
: A wallet address you have registered with the registry, with a label.

**Entry type**
: Whether a register entry is *collective* (a custodian holds for many) or *individual* (the investor is named directly).

**Fail closed**
: When a check cannot run, refuse rather than allow. Sanctions screening fails closed — an outage means transfers are refused, not let through unchecked.

**Forced transfer**
: An operator-executed correction moving a holding between wallets, under §24 eWpG. The remedy for a lost key or a court order. Requires four eyes.

**Four eyes**
: Requiring two different people. Applied to the sharpest operations.

**Holder**
: A register entry recording that somebody holds an amount of a security.

**Impersonation**
: An operator acting inside a customer's portal for support. Every action is attributed to the **operator**, never the customer.

**Indexer**
: Software watching blockchains and writing what it sees into the register.

**Legal entity**
: An organisation in the registry. Users belong to one; verification and permissions attach to it.

**Manifest**
: The signed JSON describing a marketplace dApp. Its hash is anchored on-chain on approval.

**Operator**
: The organisation running the registry. Approves entities and issuances, and holds the register-correction powers.

**Payment rail**
: A supported means of moving the cash leg — stablecoin, instant-payment API, DvP settlement, or bank transfer.

**Register**
: The operator's database record of who holds what. **The legally significant record**, distinct from the token.

**Register statement** (*Registerauszug*)
: A statement of register content concerning one holder. Under §19(2) eWpG, owed to individual-entry consumer holders. A retained register record, not a notification.

**Sperrvermerk**
: A restriction noted against a register entry under §16 eWpG. The holding cannot be transferred while it stands. You still own it.

**Step-up**
: Requiring fresh proof of identity for a sensitive action, beyond an already-open session.

**Workspace**
: A view of the customer portal grouping the tools for one job — Investor, Trader, or Issuer. Navigation, **not** permission.

---

## Regulation

**AML**
: Anti-money-laundering. Rules preventing the financial system disguising criminal proceeds.

**DORA**
: EU regulation on ICT risk and operational resilience for financial entities.

**eWpG**
: The German Electronic Securities Act, in force since June 2021. Allows a security to exist as a register entry rather than a paper certificate.

**GDPR / DSGVO**
: EU data protection law.

**KYC**
: Know Your Customer. Verifying who you are dealing with.

**MiCAR**
: EU regulation covering crypto-asset issuers and service providers.

**MiFIR**
: EU regulation behind transaction reporting.

**Prospectus**
: The disclosure document for a public securities offer. Exemptions exist — commonly for offers restricted to professional investors.

**Travel Rule**
: The requirement that originator and beneficiary information travels with a transfer. The crypto equivalent of what a bank sends with a wire.

**§16 eWpG**
: The register's content and legal effect.

**§17(2) eWpG**
: Additional content required for individual entries.

**§19(2) eWpG**
: The obligation to provide register statements to consumer holders.

**§24 eWpG**
: Correcting the register — the basis for forced transfers.
