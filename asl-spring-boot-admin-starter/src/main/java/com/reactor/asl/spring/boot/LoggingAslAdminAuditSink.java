package com.reactor.asl.spring.boot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class LoggingAslAdminAuditSink implements AslAdminAuditSink {
    private static final Logger log = LoggerFactory.getLogger(LoggingAslAdminAuditSink.class);

    @Override
    public void record(AslAdminAuditEvent event) {
        log.info(
                "ASL_ADMIN_AUDIT access={} method={} path={} principal={} remote={} authorized={} status={}",
                event.access(),
                event.httpMethod(),
                event.path(),
                event.principalName(),
                event.remoteAddress(),
                event.authorized(),
                event.responseStatus()
        );
    }
}
