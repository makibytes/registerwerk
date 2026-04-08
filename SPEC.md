# Registerwerk

An eWpG cypto registry.

## Goal

This project shall implement an eWpG conform registry in Java 25 / Spring Boot 4
and a PostgreSQL database, in which all data is stored. However, this is not the
only persistence layer. Actually, the more interesting persistence layer are the
blockchains. The registry must be able to connect to (~have assets on) the
following chains and their testnets:

- Ethereum L1
- Ethereum Optimistic L2s (e.g. Polygon, Base)
- Solana

Connections to all these chains are constantly available. The issuer selects which
chain / testnet to use for issuance. Different token standards must be available:

- ERC-20
- ERC-3643
- ERC-721
- ERC-1155
- confidential ERC-20 (see Zama project)
- confidential ERC-3643 (Zama + T-Rex, very new)

The registry supports different levels of onchain data:

- none (only local PostgreSQL db is used for all data)
- simple, asset token emitted just for primary market, i.e. issuer sends tokens
  to investors, all must be KYC'ed and their wallets must be whitelisted
- control, here the onchain layer is the primary storage layer and we provide
  additional functionality to the issuer:
  - mint control to wallets and smart contracts
  - auto-approval to wallets and smart contracts for `transferFrom` and `burn`

## Permissions & Roles

- Registry Admin: all permissions
- Audit Role: read everything
- Issuer: read and write permissions on his own issuances
- Investor: read all his investments (can be created by themselves or by issuer)
- Public: issuances have public data like the termsheet, which must be available
  publicly (without authentication), just by requesting the document by one of
  its identifiers:
  - asset token address
  - asset ISIN

The customer entities (issuer and investor) must be KYC'ed, so we need to be able
to put all required data to each entity into the PostgreSQL db. This includes
PDF files, images, XML files, etc.
Make sure to provide all necessary functionality for a full blown customer
management application (the full lifecycle, also respect renaming of companies
and mergers & acquisitions, keep all the customer's history).

## Architecture

We're using a monolithic API-first design for the backend, providing all
functionality for all users. However, this API will be placed behind an
API gateway to our customers (issuers, investors, auditors) and we will
manage authentication and authorization there. Add a subdirectory with
the necessary configuration and setup for one of the popular open source
API gateways there. The API gateway should also enable decoupling between
users and their companies / legal entities. So, in the registry we only have
to cope with companies / legal entities as they've been mapped to in the
API gateway already. The actual user authentication must be pluggable into
an identity provider like Entra ID, but also be manageable ourselves.
The API gateway should also contain a caching layer.

## Frontends

Write 2 different Angular Frontends (each in its own subdirectory):

- one for the operator of the crypto registry, giving him all functionality
  of managing all assets (also let him fix anything, data might be wrong),
  and manage the customers
- one for the issuers & investors - these are banks or big corporations;
  they need a dashboard with all their issuances and investments, as well
  as the functionality of the full lifecycle of new issuances and
  investments; actually: each company also needs an admin account to
  manage their own entity and their entitie's users (but this is a
  very limited type of admin account, not comparable to the operator's
  admin)

## Onboading

Implement a nice onboading process for issuers/investors/auditors like so:

- registry operator (admin or user) needs to create the entity and
  select its type; this will create some kind of token he can send
  to the entity's admin
- entity's admin uses this token for authentication and further setup
  of the entity (like their users and/or their identity provider)
- users will be sent a welcome mail, stating their user, the entity
  they belong to, their authentication mechanism, links to their
  frontend and the registry's API documentation

## Tests

Implement unit tests covering at least 70% of the code, as well as
integration tests. The integration tests can use testcontainers for
PostgreSQL, as well as to setup a Foundry project for anything
blockchain related (e.g. use an anvil test blockchain for depoying
and interacting with smart contracts).
