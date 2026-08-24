---
title: Deploying to the Blockchain
---

# Deploying to the Blockchain

Once your issuance has been approved by the registry operator, you can deploy the token contract to the blockchain. This step is irreversible — the contract address becomes part of the permanent registry record.

## Prerequisites

- Issuance status is **APPROVED**
- You have the **Issuer** or **Company Admin** role
- For ERC-3643 issuances: the operator has pre-deployed the factory contracts on the target chain

## Starting the deployment

1. Navigate to **Issuances** and find your issuance (status: APPROVED)
2. Click **Deploy to Blockchain**
3. A confirmation dialog appears, summarizing the deployment parameters:

| Parameter | Value |
|-----------|-------|
| Token standard | ERC-3643 |
| Network | Polygon Mainnet |
| ISIN | DE000EXAMPLE0 |
| Name | Example AG Bond 2025 |
| Symbol | EAGB25 |
| Total supply | 10,000,000 |

4. Click **Confirm Deployment**

## What happens during deployment

The registry backend submits a deployment transaction to the blockchain on your behalf using an operator-controlled deployer wallet. You do not need to sign any transaction yourself or hold any ETH/MATIC.

For an **ERC-3643** issuance, the following contracts are deployed in sequence:

1. **Token contract** — the main ERC-3643 token
2. **Identity Registry** — maps investor wallet addresses to their ONCHAINID
3. **Identity Registry Storage** — persistent storage for the registry
4. **Claim Topics Registry** — lists required KYC claim topics (e.g., topic 1 = KYC, topic 2 = AML)
5. **Trusted Issuers Registry** — lists which identity issuers are trusted to issue claims
6. **Modular Compliance** — container for compliance rule modules

This typically takes 30–120 seconds depending on network congestion.

## Monitoring deployment progress

The issuance detail page shows a live progress indicator during deployment. Each contract deployment is listed with its transaction hash, which links to the block explorer.

If any step fails (e.g., due to a network outage or insufficient gas), the deployment is automatically retried up to three times. If all retries fail, the issuance returns to **APPROVED** status and you will be notified by email.

## After successful deployment

When all contracts are deployed, the issuance moves to **ISSUED** status. You can see:

- **Contract address** — the main token contract address
- **Block explorer link** — verify the contract on Etherscan, Polygonscan, etc.
- **Deployment transaction** — the transaction that created the token

!!! tip
    Share the contract address and explorer link with your investors so they can verify their holdings independently.


## Next steps

- [Add investors and whitelist wallets](./managing-investors.md)
- Set up compliance modules (operators do this automatically for standard ERC-3643 configurations)
- Announce the issuance to your investors
