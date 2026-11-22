package com.reactor.asl.spring.boot;

@FunctionalInterface
public interface AslAdminAccessPolicy {
    boolean isAuthorized(AslAdminAccessRequest request);
}
