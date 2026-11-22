package com.reactor.asl.core;

/** Controls how synchronous runtime failures are retained for observation. */
public enum RuntimeErrorDetailPolicy {
    TYPE_ONLY,
    TYPE_AND_MESSAGE
}
