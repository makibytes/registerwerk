package de.makibytes.registerwerk.asset.api;

import java.util.UUID;

/**
 * Answers whether a corporate action has already been raised for a coupon-schedule row.
 * Implemented by the corporateactions module (which owns the actions); declared here because
 * the asset module regenerates schedules and must not depend on corporateactions (that module
 * already depends on asset, so a direct call would form a cycle).
 *
 * <p>A row with an action is immutable: schedule regeneration never replaces it.
 */
public interface CouponPaymentActionLookup {

    boolean hasCorporateAction(UUID couponPaymentId);
}
