package com.reactor.asl.spring.boot;

import java.util.Objects;

public record AslAdminAccessRequest(
        AslAdminAccess access,
        String httpMethod,
        String path,
        String remoteAddress,
        String principalName
) {
    public AslAdminAccessRequest {
        Objects.requireNonNull(access, "access");
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(remoteAddress, "remoteAddress");
        principalName = principalName == null || principalName.isBlank() ? "anonymous" : principalName;
    }
}
