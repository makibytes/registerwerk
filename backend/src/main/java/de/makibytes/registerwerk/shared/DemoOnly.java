package de.makibytes.registerwerk.shared;

/**
 * Marker for beans that exist only to seed or expose demo data (known credentials, fixed TOTP
 * secrets, permit-all demo endpoints). {@code ProductionReadinessCheck} refuses production mode
 * while any bean of this type is registered (7A-01). Lives in the shared kernel so {@code auth}
 * does not depend on {@code bootstrap}.
 */
public interface DemoOnly {
}
