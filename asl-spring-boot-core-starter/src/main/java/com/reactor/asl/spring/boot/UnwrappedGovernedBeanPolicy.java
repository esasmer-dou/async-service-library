package com.reactor.asl.spring.boot;

/** What to do when a governed interface is exposed as a bean without its ASL wrapper. */
public enum UnwrappedGovernedBeanPolicy {
    /** Log a warning at startup (default). */
    WARN,
    /** Fail startup. Recommended for production. */
    FAIL,
    /** Do not check. */
    IGNORE
}
