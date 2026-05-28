package org.autojs.autojs.runtime.api;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.util.Log;

import org.autojs.autojs.app.GlobalAppContext;
import com.stardust.autojs.core.util.ProcessShell;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

public final class EdgeJoinBridge {

    private static final String TAG = "EdgeJoinBridge";

    private static volatile Process sScrcpyProcess;
    private static final String DEFAULT_ENDPOINT = "https://www.edgez.ai/api/join";
    private static final String PREF_NAME = "edgejoin";
    private static final String KEY_CONFIG = "config";
    private static final String KEY_PEER_ID = "peer_id";
    private static final String KEY_PRIVATE_KEY = "private_key";
    private static final String KEY_PUBLIC_KEY = "public_key";
    private static final String KEY_JOIN_RESPONSE = "join_response";
    private static final String KEY_JOIN_KEY = "join_key";
    private static final String KEY_SERIAL_NUMBER = "serial_number";
    private static final String SCRCPY_LOGCAT_TAG = "ScrcpyServer";
    private static final String SCRCPY_TUNNEL_PORT_HEX = "22B6";

    private static final Object SCRCPY_BOOTSTRAP_LOCK = new Object();
    private static volatile boolean sScrcpyBootstrapped = false;

    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient();

    static {
        System.loadLibrary("edgejoin_jni");
    }

    private EdgeJoinBridge() {
    }

    private static native String nativeCreateIdentity(String name);
    private static native String nativeStartClient(String configJson);
    private static native String nativeStopClient();

    public static String createIdentity(String name) {
        Log.d(TAG, "createIdentity: requested with name=" + safeValue(sanitizeName(name)));
        return nativeCreateIdentity(sanitizeName(name));
    }

    public static String startClientFromStoredConfig() {
        ensureScrcpyServerForRelayAsync();
        String configJson = loadStoredConfig();
        Log.d(TAG, "startClientFromStoredConfig: configLen=" + configJson.length());
        return nativeStartClient(configJson);
    }

    public static void ensureScrcpyServerForRelayAsync() {
        if (sScrcpyBootstrapped) {
            return;
        }
        Thread bootstrapThread = new Thread(() -> {
            try {
                ensureScrcpyServerForRelay();
            } catch (Throwable t) {
                Log.w(TAG, "ensureScrcpyServerForRelayAsync: bootstrap failed", t);
            }
        }, "edgejoin-scrcpy-bootstrap");
        bootstrapThread.setDaemon(true);
        bootstrapThread.start();
    }

    public static void ensureScrcpyServerForRelay() {
        if (sScrcpyBootstrapped) {
            return;
        }
        boolean shouldProbePort = false;
        try {
            synchronized (SCRCPY_BOOTSTRAP_LOCK) {
                if (sScrcpyBootstrapped) {
                    return;
                }
                Context context = GlobalAppContext.get();
                if (context == null) {
                    Log.w(TAG, "ensureScrcpyServerForRelay: context is null");
                    return;
                }

                String appJarPath = context.getPackageCodePath();
                Process existing = sScrcpyProcess;
                if (existing != null && existing.isAlive()) {
                    sScrcpyBootstrapped = true;
                    Log.i(TAG, "ensureScrcpyServerForRelay: scrcpy already running");
                    shouldProbePort = true;
                    return;
                }

                Log.i(TAG, "ensureScrcpyServerForRelay: starting scrcpy from apk classpath=" + appJarPath);
                Process process = startScrcpyServerProcess(appJarPath);
                sScrcpyProcess = process;

                Thread.sleep(250L);
                if (!process.isAlive()) {
                    int exitCode = process.exitValue();
                    sScrcpyProcess = null;
                    sScrcpyBootstrapped = false;
                    Log.w(TAG, "ensureScrcpyServerForRelay: scrcpy exited early code=" + exitCode);
                    return;
                }

                sScrcpyBootstrapped = true;
                Log.i(TAG, "ensureScrcpyServerForRelay: started");
                shouldProbePort = true;
            }

            if (shouldProbePort) {
                probeScrcpyTunnelPort();
            }
        } catch (Throwable t) {
            Log.w(TAG, "ensureScrcpyServerForRelay: bootstrap failed unexpectedly", t);
        }
    }

    private static Process startScrcpyServerProcess(String jarPath) throws IOException {
        List<String> command = Arrays.asList(
                "app_process",
                "/",
                "com.genymobile.scrcpy.Server",
                "1.19-ws5",
                "log_level=info",
                "tunnel_forward=true",
                "audio=false"
        );

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.environment().put("CLASSPATH", jarPath);
        builder.redirectErrorStream(true);
        Process process = builder.start();

        Thread outputReader = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Log.i(SCRCPY_LOGCAT_TAG, line);
                }
            } catch (IOException e) {
                Log.w(TAG, "startScrcpyServerProcess: output reader failed", e);
            }
        }, "scrcpy-server-log-reader");
        outputReader.setDaemon(true);
        outputReader.start();

        Thread exitWatcher = new Thread(() -> {
            try {
                int exitCode = process.waitFor();
                Log.w(SCRCPY_LOGCAT_TAG, "__SCRCPY_EXIT__:" + exitCode);
                sScrcpyBootstrapped = false;
                if (sScrcpyProcess == process) {
                    sScrcpyProcess = null;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "scrcpy-server-exit-watcher");
        exitWatcher.setDaemon(true);
        exitWatcher.start();

        return process;
    }

    private static void probeScrcpyTunnelPort() {
        try {
            ProcessShell.Result probeResult = ProcessShell.execCommand(new String[]{
                    "sleep 1",
                    "cat /proc/net/tcp /proc/net/tcp6 2>/dev/null | grep -i ':" + SCRCPY_TUNNEL_PORT_HEX + " '",
            }, false);

            if (probeResult.code == 0) {
                Log.i(TAG, "probeScrcpyTunnelPort: 8886 is listening");
            } else {
                Log.w(
                        TAG,
                        "probeScrcpyTunnelPort: 8886 not listening"
                                + ", stdout=" + safeValue(probeResult.result)
                                + ", stderr=" + safeValue(probeResult.error)
                );
            }
        } catch (Throwable t) {
            Log.w(TAG, "probeScrcpyTunnelPort: failed", t);
        }
    }

    public static String stopClient() {
        Log.d(TAG, "stopClient: requested");
        return nativeStopClient();
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
        Log.d(TAG, "loadStoredConfig: loaded configLen=" + (config == null ? 0 : config.length()));
        return config;
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
}
