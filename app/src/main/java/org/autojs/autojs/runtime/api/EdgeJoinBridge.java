package org.autojs.autojs.runtime.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Keep;

import org.autojs.autojs.app.GlobalAppContext;
import org.autojs.autojs.core.edgejoin.WirelessDebugEnabler;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class EdgeJoinBridge {

    private static final String TAG = "EdgeJoin";
    private static final String LOG_SCOPE = "[Bridge] ";

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
    private static final String DEFAULT_SCRIPT_MESSAGE_PROTOCOL = "/autojs/script-message/1.0.0";
    private static final Set<ScriptMessageListener> SCRIPT_MESSAGE_LISTENERS = new CopyOnWriteArraySet<>();

    private static String scoped(String msg) {
        return LOG_SCOPE + msg;
    }

    static {
        System.loadLibrary("edgejoin_jni");
        provideScrcpyJarFromAssets();
        try {
            String resp = nativeRegisterScriptMessageCallback();
            Log.d(TAG, scoped("nativeRegisterScriptMessageCallback: resp=" + resp));
        } catch (Throwable t) {
            Log.w(TAG, scoped("nativeRegisterScriptMessageCallback failed"), t);
        }
    }

    private EdgeJoinBridge() {
    }

    private static native String nativeCreateIdentity(String name);
    private static native String nativeStartClient(String configJson);
    private static native String nativeStopClient();
    private static native String nativeProvideScrcpyJar(byte[] bytes);
    private static native String nativePairWireless(String host, int port, String code, String packageName, String debugHost, int debugPort);
    private static native String nativeProvisionDeviceOwner(String debugHost, int debugPort, String packageName);
    private static native String nativeSetAdbProxyTarget(String host, int port);
    private static native String nativeRegisterScriptMessageCallback();
    private static native String nativeSendScriptMessage(String peerId, String protocol, String payload);

    public interface ScriptMessageListener {
        void onMessage(String peerId, String protocol, String payload);
    }

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
                Log.w(TAG, scoped("provideScrcpyJarFromAssets: no application context yet"));
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
                Log.d(TAG, scoped("provideScrcpyJarFromAssets: provided size=" + data.length + " resp=" + resp));
            }
        } catch (Throwable t) {
            Log.w(TAG, scoped("provideScrcpyJarFromAssets: failed to load scrcpy-server.jar from assets"), t);
        }
    }

    public static String createIdentity(String name) {
        Log.d(TAG, scoped("createIdentity: requested with name=" + safeValue(sanitizeName(name))));
        return nativeCreateIdentity(sanitizeName(name));
    }

    public static String startClientFromStoredConfig() {
        String configJson = loadStoredConfig();
        Log.d(TAG, scoped("startClientFromStoredConfig: configLen=" + configJson.length()));
        return nativeStartClient(configJson);
    }

    public static String stopClient() {
        Log.d(TAG, scoped("stopClient: requested"));
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

        // Pairing-only mode: do not run `dpm set-device-owner` here.
        // Native side skips device-owner provisioning when packageName is empty.
        Log.d(TAG, "pairWirelessAndProvision: pair=" + pair.host + ":" + pair.port
            + ", debug=" + debug.host + ":" + debug.port + ", package=(pair-only)");
        return nativePairWireless(pair.host, pair.port, normalizedCode, "", debug.host, debug.port);
    }

    /**
     * Run the {@code dpm set-device-owner} adb-shell step on its own over the
     * already-trusted wireless-debug channel. Use this to retry device-owner
     * provisioning after a successful pairing whose device-owner step did not
     * complete (e.g. the connect channel was not ready right after pairing).
     *
     * @param debugEndpoint wireless-debug connect endpoint as {@code host:port}
     * @return native JSON result; {@code ok=true} with {@code state=paired_device_owner} on success
     */
    public static String provisionDeviceOwner(String debugEndpoint) {
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

        Log.d(TAG, "provisionDeviceOwner: debug=" + debug.host + ":" + debug.port + ", package=" + packageName);
        return nativeProvisionDeviceOwner(debug.host, debug.port, packageName);
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

    /**
     * Updates the live adb proxy target of the running native client without a restart.
     * Used after wireless debugging is re-enabled and a new dynamic TLS port is discovered.
     *
     * @return the native JSON result, or an error JSON if the port is invalid.
     */
    public static String setAdbProxyTarget(String host, int port) {
        String normalizedHost = trimOrEmpty(host);
        if (normalizedHost.isEmpty()) {
            normalizedHost = "127.0.0.1";
        }
        if (port < 1 || port > 65535) {
            return buildErrorResult("invalid adb proxy port: " + port, 0);
        }
        Log.d(TAG, scoped("setAdbProxyTarget: host=" + safeValue(normalizedHost) + ", port=" + port));
        return nativeSetAdbProxyTarget(normalizedHost, port);
    }

    public static void addScriptMessageListener(ScriptMessageListener listener) {
        if (listener != null) {
            SCRIPT_MESSAGE_LISTENERS.add(listener);
        }
    }

    public static void removeScriptMessageListener(ScriptMessageListener listener) {
        if (listener != null) {
            SCRIPT_MESSAGE_LISTENERS.remove(listener);
        }
    }

    public static String sendScriptMessage(String peerId, String payload) {
        return sendScriptMessage(peerId, DEFAULT_SCRIPT_MESSAGE_PROTOCOL, payload);
    }

    public static String sendScriptMessage(String peerId, String protocol, String payload) {
        String normalizedPeerId = trimOrEmpty(peerId);
        if (normalizedPeerId.isEmpty()) {
            return buildErrorResult("peer id is required", 0);
        }
        String normalizedProtocol = trimOrEmpty(protocol);
        if (normalizedProtocol.isEmpty()) {
            normalizedProtocol = DEFAULT_SCRIPT_MESSAGE_PROTOCOL;
        }
        String safePayload = payload == null ? "" : payload;
        return nativeSendScriptMessage(normalizedPeerId, normalizedProtocol, safePayload);
    }

    /**
     * Invoked from native (Go via JNI) on a dedicated attached thread when the local
     * adbd / wireless-debug endpoint is unreachable (e.g. a reboot closed the dynamic
     * TLS port). Re-enables wireless debugging via accessibility, re-resolves the new
     * port over mDNS, and pushes it back into the running native client.
     *
     * <p>Must return quickly; the actual work runs on a background thread.
     */
    @Keep
    public static void onAdbUnreachableFromNative() {
        Log.i(TAG, scoped("onAdbUnreachableFromNative: native reported adb unreachable, re-enabling wireless debug"));
        try {
            Context context = GlobalAppContext.get();
            if (context == null) {
                Log.w(TAG, scoped("onAdbUnreachableFromNative: no application context"));
                return;
            }
            WirelessDebugEnabler.requestEnableAndRefresh(context.getApplicationContext());
        } catch (Throwable t) {
            Log.w(TAG, scoped("onAdbUnreachableFromNative: failed to dispatch re-enable"), t);
        }
    }

    @Keep
    public static void onScriptMessageFromNative(String peerId, String protocol, String payload) {
        String safePeer = peerId == null ? "" : peerId;
        String safeProtocol = protocol == null ? "" : protocol;
        String safePayload = payload == null ? "" : payload;

        for (ScriptMessageListener listener : SCRIPT_MESSAGE_LISTENERS) {
            try {
                listener.onMessage(safePeer, safeProtocol, safePayload);
            } catch (Throwable t) {
                Log.w(TAG, scoped("script message listener failed"), t);
            }
        }
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
