package org.autojs.autojs.runtime.api;

public final class EdgeJoinBridge {

    private static final String DEFAULT_ENDPOINT = "https://edgez.ai/api/join";

    static {
        System.loadLibrary("edgejoin_jni");
    }

    private EdgeJoinBridge() {
    }

    private static native String nativeJoin(
            String serialNumber,
            String joinKey,
            String version,
            String name,
            String endpoint
    );

    public static String join(String serialNumber, String joinKey, String version, String name) {
        return nativeJoin(serialNumber, joinKey, version, name, DEFAULT_ENDPOINT);
    }

    public static String join(String serialNumber, String joinKey, String version, String name, String endpoint) {
        return nativeJoin(serialNumber, joinKey, version, name, endpoint);
    }
}
