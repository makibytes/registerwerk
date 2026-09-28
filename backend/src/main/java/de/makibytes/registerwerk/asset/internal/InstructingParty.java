package de.makibytes.registerwerk.asset.internal;

/**
 * T3-13: who instructed a change to a register entry (§18 eWpG). The operator executes only against
 * a recorded instruction; which parties may instruct a §17(2) change is still a policy question, so
 * the party is recorded, not (yet) restricted.
 */
public enum InstructingParty {
    HOLDER, BENEFICIARY, COURT, INSOLVENCY_ADMINISTRATOR, ISSUER_TERMS_CHANGE
}
