package de.makibytes.registerwerk.stepup.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler that applies step-up programmatically through {@link DualControlGate} for some
 * inputs only (so it cannot carry {@link RequiresStepUp}): a locally minted {@code acr=stepup} token
 * is accepted as bearer here, as on {@code @RequiresStepUp} handlers, and refused everywhere else.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface StepUpBearerAccepted {}
