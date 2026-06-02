package org.autojs.autojs.runtime.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.autojs.autojs.app.GlobalAppContext;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class EdgeJoinBridge {

    private static final String TAG = "EdgeJoinBridge";

    private static final String DEFAULT_ENDPOINT = "https://www.edgez.ai/api/join";
    private static final String PREF_NAME = "edgejoin";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_PEER_ID = "peer_id";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_PUBLIC_KEY = "public_key";
    private static final String KEY_JOIN_RESPONSE = "join_response";
    private static final String KEY_JOIN_KEY = "join_key";
    private static final String KEY_SERIAL_NUMBER = "serial_number";
    private static final String KEY_ADB_PROXY_HOST = "adb_proxy_host";
    private static final String KEY_ADB_PROXY_PORT = "adb_proxy_port";

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    static {
        System.loadLibrary("edgejoin_jni");
        provideScrcpyJarFromAssets();
    }

    private EdgeJoinBridge() {
    }

    private static native String nativeCreateIdentity(String name);
    private static native String nativeStartClient(String configJson);
    private static native String nativeStopClient();
    private static native String nativeProvideScrcpyJar(byte[] bytes);
    private static native String nativePairWireless(String host, int port, String code, String packageName);

    private static final class HostPort {
        final String host;
        final int port;

        HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static void provideScrcpyJarFromAssets() {
        try {
            Context ctx = GlobalAppContext.get();
            if (ctx == null) {
                Log.w(TAG, "provideScrcpyJarFromAssets: no application context yet");
                return;
            }
            try (InputStream in = ctx.getAssets().open("scrcpy/scrcpy-server.jar");
                 ByteArrayOutputStream out = new ByteArrayOutputStream(128 * 1024)) {
                byte[] buf = new byte[8 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                byte[] data = out.toByteArray();
                String resp = nativeProvideScrcpyJar(data);
                Log.d(TAG, "provideScrcpyJarFromAssets: provided size=" + data.length + " resp=" + resp);
            }
        } catch (Throwable t) {
            Log.w(TAG, "provideScrcpyJarFromAssets: failed to load scrcpy-server.jar from assets", t);
        }
    }

    public static String createIdentity(String name) {
        Log.d(TAG, "createIdentity: requested with name=" + safeValue(sanitizeName(name)));
        return nativeCreateIdentity(sanitizeName(name));
    }

    public static String startClientFromStoredConfig() {
        String configJson = loadStoredConfig();
        Log.d(TAG, "startClientFromStoredConfig: configLen=" + configJson.length());
        return nativeStartClient(configJson);
    }

    public static String stopClient() {
        Log.d(TAG, "stopClient: requested");
        return nativeStopClient();
    }

    public static String pairWirelessAndProvision(String pairEndpoint, String pairCode, String debugEndpoint) {
        String normalizedCode = trimOrEmpty(pairCode);
        if (normalizedCode.isEmpty()) {
            return buildErrorResult("pair code is required", 0);
        }

        HostPort pair = parseHostPort(trimOrEmpty(pairEndpoint));
        if (pair == null) {
            return buildErrorResult("invalid pair endpoint, expected host:port", 0);
        }
        HostPort debug = parseHostPort(trimOrEmpty(debugEndpoint));
        if (debug == null) {
            return buildErrorResult("invalid debug endpoint, expected host:port", 0);
        }

        persistAdbProxyEndpoint(debug.host, debug.port);

        Context context = GlobalAppContext.get();
        String packageName = context != null ? trimOrEmpty(context.getPackageName()) : "";
        if (packageName.isEmpty()) {
            return buildErrorResult("application context unavailable", 0);
        }

        Log.d(TAG, "pairWirelessAndProvision: pair=" + pair.host + ":" + pair.port
                + ", debug=" + debug.host + ":" + debug.port + ", package=" + packageName);
        return nativePairWireless(pair.host, pair.port, normalizedCode, packageName);
    }

    public static String joinAndPersist(String serialNumber, String joinKey, String version, String name) {
        return joinAndPersist(serialNumber, joinKey, version, name, DEFAULT_ENDPOINT);
    }

    public static String joinAndPersist(String serialNumber, String joinKey, String version, String name, String endpoint) {
        Log.d(TAG, "joinAndPersist: start");
        String normalizedSerial = trimOrEmpty(serialNumber);
        String normalizedJoinKey = trimOrEmpty(joinKey);
        String normalizedVersion = trimOrEmpty(version);
        String normalizedName = sanitizeName(name);
        String normalizedEndpoint = trimOrEmpty(endpoint).isEmpty() ? DEFAULT_ENDPOINT : trimOrEmpty(endpoint);

        Log.d(
                TAG,
                "joinAndPersist: normalized input serial=" + safeValue(normalizedSerial)
                        + ", version=" + safeValue(normalizedVersion)
                        + ", name=" + safeValue(normalizedName)
                        + ", endpoint=" + normalizedEndpoint
                        + ", joinKeyLen=" + normalizedJoinKey.length()
        );

        if (normalizedSerial.isEmpty()) {
            Log.w(TAG, "joinAndPersist: serial_number is empty");
            return buildErrorResult("serial_number is required", 0);
        }
        if (normalizedJoinKey.isEmpty()) {
            Log.w(TAG, "joinAndPersist: join_key is empty");
            return buildErrorResult("join_key is required", 0);
        }

        Log.d(TAG, "joinAndPersist: creating identity via JNI");
        String identityJson = createIdentity(normalizedName);
        JSONObject identity;
        try {
            identity = new JSONObject(identityJson);
        } catch (JSONException e) {
            Log.e(TAG, "joinAndPersist: failed to parse identity JSON", e);
            return buildErrorResult("invalid identity json: " + e.getMessage(), 0);
        }

        if (!identity.optBoolean("ok", false)) {
            Log.w(TAG, "joinAndPersist: identity generation returned not ok");
            return identityJson;
        }

        Log.d(
                TAG,
                "joinAndPersist: identity ready peerId=" + safeValue(identity.optString("peer_id", ""))
                        + ", privateKeyLen=" + identity.optString("private_key", "").length()
                        + ", publicKeyLen=" + identity.optString("public_key", "").length()
        );

        JSONObject payload = new JSONObject();
        try {
            payload.put("id", identity.optString("peer_id", ""));
            payload.put("join_key", normalizedJoinKey);
            payload.put("serial_number", normalizedSerial);
            payload.put("platform", "android");
            payload.put("arch", primaryAbi());
            payload.put("version", normalizedVersion);
            payload.put("name", normalizedName);
            payload.put("port", 22);
            payload.put("username", "android");
        } catch (JSONException e) {
            Log.e(TAG, "joinAndPersist: failed to construct payload", e);
            return buildErrorResult("failed to create join payload: " + e.getMessage(), 0);
        }

        Log.d(TAG, "joinAndPersist: payload built, sending /api/join request");

        RequestBody requestBody = RequestBody.create(payload.toString(), JSON_MEDIA_TYPE);
        Request request = new Request.Builder()
                .url(normalizedEndpoint)
                .addHeader("Authorization", "Bearer " + normalizedJoinKey)
                .addHeader("Content-Type", "application/json")
                .post(requestBody)
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            int statusCode = response.code();
            ResponseBody body = response.body();
            String responseText = body != null ? body.string() : "";

            Log.d(TAG, "joinAndPersist: /api/join responded with status=" + statusCode + ", bodyLen=" + responseText.length());

            if (statusCode != 200) {
                Log.w(TAG, "joinAndPersist: /api/join failed status=" + statusCode);
                return buildErrorResult("join failed with status " + statusCode + ": " + responseText.trim(), statusCode);
            }

            JSONObject config = new JSONObject(responseText);
            config.put("key", identity.optString("private_key", ""));
            config.put("public", false);

            Log.d(TAG, "joinAndPersist: response parsed, persisting config and credentials");

            saveToPreferences(
                    config,
                    responseText,
                    identity.optString("peer_id", ""),
                    identity.optString("private_key", ""),
                    identity.optString("public_key", ""),
                    normalizedJoinKey,
                    normalizedSerial
            );

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("status_code", 200);
            result.put("config", config);
            Log.d(TAG, "joinAndPersist: success");
            return result.toString();
        } catch (IOException e) {
            Log.e(TAG, "joinAndPersist: network request failed", e);
            return buildErrorResult("join request failed: " + e.getMessage(), 0);
        } catch (JSONException e) {
            Log.e(TAG, "joinAndPersist: failed to parse or build JSON", e);
            return buildErrorResult("decode join response failed: " + e.getMessage(), 0);
        }
    }

    public static String loadStoredConfig() {
        Context context = GlobalAppContext.get();
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String config = preferences.getString(KEY_CONFIG, "");
        if (config == null) {
            config = "";
        }
        String adbProxyHost = trimOrEmpty(preferences.getString(KEY_ADB_PROXY_HOST, ""));
        int adbProxyPort = preferences.getInt(KEY_ADB_PROXY_PORT, 0);

        if (!config.isEmpty() && (!adbProxyHost.isEmpty() || (adbProxyPort > 0 && adbProxyPort <= 65535))) {
            try {
                JSONObject configJson = new JSONObject(config);
                if (!adbProxyHost.isEmpty()) {
                    configJson.put(KEY_ADB_PROXY_HOST, adbProxyHost);
                }
                if (adbProxyPort > 0 && adbProxyPort <= 65535) {
                    configJson.put(KEY_ADB_PROXY_PORT, adbProxyPort);
                }
                config = configJson.toString();
            } catch (JSONException e) {
                Log.w(TAG, "loadStoredConfig: failed to merge adb proxy config", e);
            }
        }

        Log.d(TAG, "loadStoredConfig: loaded configLen=" + (config == null ? 0 : config.length()));
        return config;
    }

    /**
     * Persists the adb proxy endpoint (host/port) into the EdgeJoin preferences so that
     * {@link #loadStoredConfig()} merges it into the config consumed by the native client.
     * Pass a non-positive port to clear the stored port.
     *
     * @return true if the stored port changed.
     */
    public static boolean persistAdbProxyEndpoint(String host, int port) {
        Context context = GlobalAppContext.get();
        if (context == null) {
            return false;
        }
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        int previousPort = preferences.getInt(KEY_ADB_PROXY_PORT, 0);
        String previousHost = trimOrEmpty(preferences.getString(KEY_ADB_PROXY_HOST, ""));
        String normalizedHost = trimOrEmpty(host);

        SharedPreferences.Editor editor = preferences.edit();
        if (!normalizedHost.isEmpty()) {
            editor.putString(KEY_ADB_PROXY_HOST, normalizedHost);
        }
        if (port > 0 && port <= 65535) {
            editor.putInt(KEY_ADB_PROXY_PORT, port);
        } else {
            editor.remove(KEY_ADB_PROXY_PORT);
        }
        editor.apply();

        boolean changed = previousPort != port
                || (!normalizedHost.isEmpty() && !normalizedHost.equals(previousHost));
        Log.d(TAG, "persistAdbProxyEndpoint: host=" + safeValue(normalizedHost) + ", port=" + port
                + ", previousPort=" + previousPort + ", changed=" + changed);
        return changed;
    }

    public static String loadStoredPeerId() {
        Context context = GlobalAppContext.get();
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String peerId = preferences.getString(KEY_PEER_ID, "");
        Log.d(TAG, "loadStoredPeerId: loaded peerId=" + safeValue(peerId));
        return peerId == null ? "" : peerId;
    }

    private static void saveToPreferences(
            JSONObject config,
            String joinResponse,
            String peerId,
            String privateKey,
            String publicKey,
            String joinKey,
            String serialNumber
    ) {
        Context context = GlobalAppContext.get();
        SharedPreferences preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        preferences.edit()
                .putString(KEY_CONFIG, config.toString())
                .putString(KEY_JOIN_RESPONSE, joinResponse)
                .putString(KEY_PEER_ID, peerId)
                .putString(KEY_PRIVATE_KEY, privateKey)
                .putString(KEY_PUBLIC_KEY, publicKey)
                .putString(KEY_JOIN_KEY, joinKey)
                .putString(KEY_SERIAL_NUMBER, serialNumber)
                .apply();

            Log.d(
                TAG,
                "saveToPreferences: persisted configLen=" + config.toString().length()
                    + ", joinResponseLen=" + joinResponse.length()
                    + ", peerId=" + safeValue(peerId)
                    + ", privateKeyLen=" + privateKey.length()
                    + ", publicKeyLen=" + publicKey.length()
                    + ", joinKeyLen=" + joinKey.length()
                    + ", serial=" + safeValue(serialNumber)
            );
    }

    private static String buildErrorResult(String error, int statusCode) {
        try {
            JSONObject result = new JSONObject();
            result.put("ok", false);
            result.put("status_code", statusCode);
            result.put("error", error);
            return result.toString();
        } catch (JSONException e) {
            return "{\"ok\":false,\"status_code\":0,\"error\":\"" + error + "\"}";
        }
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String sanitizeName(String name) {
        String normalized = trimOrEmpty(name);
        if (!normalized.isEmpty()) {
            return normalized;
        }
        String deviceName = trimOrEmpty(Build.MODEL);
        return deviceName.isEmpty() ? "android-device" : deviceName;
    }

    private static String primaryAbi() {
        if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
            return Build.SUPPORTED_ABIS[0];
        }
        return Build.CPU_ABI;
    }

    private static String safeValue(String value) {
        if (value == null || value.isEmpty()) {
            return "<empty>";
        }
        if (value.length() <= 8) {
            return value.charAt(0) + "***";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }

    private static HostPort parseHostPort(String endpoint) {
        String normalized = trimOrEmpty(endpoint);
        int idx = normalized.lastIndexOf(':');
        if (idx <= 0 || idx >= normalized.length() - 1) {
            return null;
        }
        String host = trimOrEmpty(normalized.substring(0, idx));
        String portPart = trimOrEmpty(normalized.substring(idx + 1));
        if (host.isEmpty() || portPart.isEmpty()) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            return null;
        }
        if (port <= 0 || port > 65535) {
            return null;
        }
        return new HostPort(host, port);
    }
}
