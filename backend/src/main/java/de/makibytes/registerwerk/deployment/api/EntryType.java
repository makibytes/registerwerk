package de.makibytes.registerwerk.deployment.api;

/**
 * eWpG §8 Eintragungsart — how a crypto security is entered in the register.
 *
 * <ul>
 *   <li>{@link #COLLECTIVE} — Sammeleintragung: the holder of record is a
 *       custodian/Verwahrer holding for the benefit of underlying investors.</li>
 *   <li>{@link #INDIVIDUAL} — Einzeleintragung: each investor is entered directly
 *       (pseudonymised by a unique identifier, §17(2) eWpG). Consumer holders in
 *       this form are entitled to register statements under §19 eWpG.</li>
 *   <li>{@link #MIXED} — Mischbestand: both forms coexist for the same asset.</li>
 * </ul>
 */
public enum EntryType {
    COLLECTIVE,
    INDIVIDUAL,
    MIXED
}
