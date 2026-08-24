package de.makibytes.registerwerk.finality.api;

/**
 * A nameable, reviewable policy artefact — "this bond is Conservative" — assignable globally, per
 * {@code TokenStandard}, or per asset (most specific wins; see {@link FinalityPolicyService}).
 */
public enum FinalityPolicyProfile {

    /** Non-hard-floor operations resolve at {@link FinalityLevel#SAFE} — the profile that makes
     *  "count balances at SAFE" reachable without a per-asset override. Hard-floor operations
     *  (documents/exports leaving the system) still clamp to FINALIZED regardless. */
    FAST,

    /** Every operation resolves at {@link FinalityLevel#FINALIZED} — matches this system's
     *  behavior before the policy model existed, so adopting it changes nothing by default
     *  ("upgrade day is a no-op"). The global compiled-in default. */
    BALANCED,

    /** Resolves identically to {@link #BALANCED} today — {@link FinalityLevel#FINALIZED} is the
     *  ceiling of this model, so there is currently no stronger level for CONSERVATIVE to require.
     *  Exists as its own nameable profile so an admin can declare heightened caution explicitly
     *  for a specific asset/standard (audit-visible intent), and so a future stricter tier (e.g.
     *  a minimum post-FINALIZED confirmation count) has somewhere to attach without a schema
     *  change. Not a placeholder bug — a deliberate, disclosed choice. */
    CONSERVATIVE
}
