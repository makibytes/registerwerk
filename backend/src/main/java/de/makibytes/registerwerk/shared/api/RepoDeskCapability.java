package de.makibytes.registerwerk.shared.api;

/** Minimal cross-module view of Repo Desk release state (kept in shared.api so consumers need no dependency on the repo module). */
public interface RepoDeskCapability {
    boolean isReleased();
}

