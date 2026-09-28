/**
 * Pure (Spring-free) bond calendar arithmetic: business-day calendars and conventions, ICMA
 * day-count fractions and the coupon-schedule calculator. Shared by the asset module (schedule
 * generation) and the corporate-action jobs, so it lives next to {@code DayCountConvention} /
 * {@code PaymentFrequency} rather than inside either consumer.
 */
@org.springframework.modulith.NamedInterface("schedule")
package de.makibytes.registerwerk.deployment.api.schedule;
