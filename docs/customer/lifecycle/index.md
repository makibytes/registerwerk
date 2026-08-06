---
title: The life of a security
description: One bond, followed from the first idea to final repayment, with every Registerwerk feature explained where it is actually used.
---

# The life of a security

Most documentation explains features. This section explains a *story*, and lets the features appear where they belong in it.

The story is a bond. We follow it from the moment somebody wants to borrow money, through the paperwork, onto a blockchain, into investors' hands, across a trading venue, into a lending market as collateral, and finally out of existence when the debt is repaid.

**If you read this whole section you will understand the business Registerwerk is in.** It takes about forty minutes.

---

## Meet Nordwind Energie

!!! example "The running example"

    **Nordwind Energie GmbH** builds wind farms in Schleswig-Holstein. It needs **€50 million** to finance a new site, and it does not want to go to a bank.

    So it decides to borrow the money from investors directly, by issuing a **bond**: a promise to pay the money back on a fixed date, with interest along the way.

    The terms it has in mind:

    | | |
    |---|---|
    | Amount | €50,000,000 |
    | Denomination | €1,000 per unit, so 50,000 units |
    | Interest | 4.5% per year, paid twice a year |
    | Maturity | 5 years |
    | Repayment | full face value on the maturity date |

That is the entire financial product. Everything that follows is the machinery for making that promise real, tradable, and enforceable — and for satisfying a regulator that it was all done properly.

??? note "For readers new to finance: what a bond actually is"

    A bond is a loan cut into equal pieces so that many lenders can each take one.

    Nordwind wants €50 million. Rather than find one lender willing to provide all of it, it splits the loan into 50,000 pieces of €1,000. An investor buys as many pieces as they like. Each piece entitles the holder to their share of the interest, and to €1,000 back at the end.

    Three words you will meet constantly:

    - **Face value** (or *nominal*, or *par*): the amount printed on the piece — here €1,000. This is what gets repaid at the end, regardless of what anybody paid for it in between.
    - **Coupon**: the interest rate, here 4.5% per year. The name is a leftover from when bonds were paper and you physically clipped a coupon off the certificate to claim each payment.
    - **Maturity**: the date the loan ends and the face value is repaid.

    The crucial and counter-intuitive part: **the price of a bond and its face value are different numbers, and the price moves.** If interest rates rise after the bond is issued, a bond paying 4.5% becomes less attractive, and people will only buy it at a discount — perhaps €960 for a €1,000 piece. The face value has not changed. What has changed is what someone will pay you for the right to receive it.

---

## The six stages

<div class="grid cards" markdown>

-   **1. [Design and approval](design.md)**

    ---

    Nordwind describes the bond in the portal, chooses how it will exist on a blockchain, and submits it. The operator checks it and approves it. Nothing is on-chain yet.

-   **2. [Primary issuance](primary-issuance.md)**

    ---

    The contract is deployed, investors are admitted, and the 50,000 units come into existence in their hands. Money goes one way, securities the other.

-   **3. [Holding and custody](holding.md)**

    ---

    Investors own something. Where does it actually live, who is recorded as the owner, and what happens when the register and the blockchain disagree?

-   **4. [Secondary trading](secondary-market.md)**

    ---

    An investor wants out before maturity. Somebody else wants in. How the two find each other, and how the swap is made safe.

-   **5. [Repo and lending](repo-lending.md)**

    ---

    An investor wants cash but wants to keep the bond. They pledge it as collateral and borrow against it — the oldest trick in financial markets, rebuilt on-chain.

-   **6. [Corporate actions and redemption](redemption.md)**

    ---

    Interest gets paid twice a year for five years. Then the loan ends, the money goes back, and the security is destroyed.

</div>

---

## The two mistakes worth avoiding

Two misconceptions cause most of the confusion for newcomers. Naming them now saves a lot of re-reading.

**"The token *is* the security."** It is not. The token is how the security is transferred and evidenced on a blockchain. The security is the legal claim against Nordwind. The register is the record of who holds it. If every blockchain in the world went dark tomorrow, investors would still be owed their €50 million — they would simply have a much harder time proving who was owed what. The token is the mechanism, not the thing.

**"On a blockchain, anyone can send anything to anyone."** True for a cryptocurrency. Emphatically false here. A regulated security may only be held by people who are permitted to hold it, and that restriction has to survive contact with a public blockchain where anybody can call any function. Solving that problem is most of what makes securities tokens harder than ordinary tokens, and it is the subject of [Design and approval](design.md).

---

[Start with stage 1: Design and approval :octicons-arrow-right-24:](design.md){ .md-button .md-button--primary }
