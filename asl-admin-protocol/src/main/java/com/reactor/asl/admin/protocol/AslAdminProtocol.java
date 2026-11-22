package com.reactor.asl.admin.protocol;

/** Constants shared by the ASL admin client and server. */
public final class AslAdminProtocol {
    public static final int VERSION = 1;
    public static final String CONTENT_TYPE = "application/x-asl-admin-v1";
    public static final String AUTH_HEADER = "X-ASL-Auth";
    public static final String REGISTER_PATH = "/asl/v1/register";
    public static final String BEAT_PATH = "/asl/v1/beat";

    /** Upper bound for a single string field, in UTF-8 bytes. */
    public static final int MAX_STRING_BYTES = 8 * 1024;
    /** Upper bound for any repeated field. */
    public static final int MAX_COUNT = 65_536;

    private AslAdminProtocol() {
    }
}
