package com.reactor.asl.spring.boot;

import java.time.Instant;
import java.util.Objects;

public record AslAdminAuditEvent(
        Instant occurredAt,
        AslAdminAccess access,
        String httpMethod,
        String path,
        String remoteAddress,
        String principalName,
        boolean authorized,
        int responseStatus
) {
    public AslAdminAuditEvent {
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        Objects.requireNonNull(principalName, "principalName");
    }
}
