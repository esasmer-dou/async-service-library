package com.reactor.asl.admin.client;

import java.io.IOException;

/** Sends one signed protocol message to the admin server. */
public interface AslAdminTransport {
    Response post(String path, byte[] body, String authHeader) throws IOException, InterruptedException;

    record Response(int status, byte[] body) {
    }
}
