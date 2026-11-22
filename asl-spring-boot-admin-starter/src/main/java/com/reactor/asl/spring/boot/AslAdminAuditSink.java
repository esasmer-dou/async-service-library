package com.reactor.asl.spring.boot;

@FunctionalInterface
public interface AslAdminAuditSink {
    void record(AslAdminAuditEvent event);
}
