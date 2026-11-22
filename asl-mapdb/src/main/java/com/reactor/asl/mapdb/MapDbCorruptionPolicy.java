package com.reactor.asl.mapdb;

/**
 * Controls startup behavior when the configured MapDB store fails structural validation.
 */
public enum MapDbCorruptionPolicy {
    /** Preserve the store and fail application startup. */
    FAIL_FAST,

    /** Archive the complete damaged store set and create a new empty store. */
    ARCHIVE_AND_RESET
}
