#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>
#include <string.h>

typedef char *(*edge_create_identity_fn)(const char *);
typedef char *(*edge_start_client_fn)(const char *);
typedef char *(*edge_stop_client_fn)(void);
typedef void (*edge_join_free_fn)(char *);
typedef char *(*edge_provide_scrcpy_jar_fn)(const char *, int);
typedef char *(*edge_pair_wireless_fn)(const char *, int, const char *, const char *, const char *, int);
typedef char *(*edge_provision_device_owner_fn)(const char *, int, const char *);
typedef void (*edge_adb_unreachable_cb)(void);
typedef void (*edge_register_adb_unreachable_fn)(edge_adb_unreachable_cb);
typedef char *(*edge_set_adb_proxy_target_fn)(const char *, int);
typedef void (*edge_script_message_cb)(const char *, const char *, const char *);
typedef void (*edge_register_script_message_fn)(edge_script_message_cb);
typedef char *(*edge_send_script_message_fn)(const char *, const char *, const char *);
typedef char *(*edge_script_execute_cb)(const char *, const char *, const char *, const char *);
typedef void (*edge_register_script_execute_fn)(edge_script_execute_cb);

static const char *kGoLibraryName = "libedgejoin.so";

// Cached JVM and the EdgeJoinBridge.onAdbUnreachableFromNative() callback target,
// resolved once in JNI_OnLoad where the app classloader is available.
static JavaVM *g_vm = NULL;
static jclass g_bridge_class = NULL;
static jmethodID g_on_adb_unreachable_mid = NULL;
static jmethodID g_on_script_message_mid = NULL;
static jmethodID g_on_execute_script_mid = NULL;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    g_vm = vm;

    JNIEnv *env = NULL;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK || env == NULL) {
        return JNI_VERSION_1_6;
    }

    jclass local = (*env)->FindClass(env, "org/autojs/autojs/runtime/api/EdgeJoinBridge");
    if (local != NULL) {
        g_bridge_class = (jclass) (*env)->NewGlobalRef(env, local);
        (*env)->DeleteLocalRef(env, local);
        if (g_bridge_class != NULL) {
            g_on_adb_unreachable_mid = (*env)->GetStaticMethodID(
                    env, g_bridge_class, "onAdbUnreachableFromNative", "()V");
            g_on_script_message_mid = (*env)->GetStaticMethodID(
                    env, g_bridge_class, "onScriptMessageFromNative",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
                g_on_execute_script_mid = (*env)->GetStaticMethodID(
                    env, g_bridge_class, "onExecuteScriptFromNative",
                    "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;");
        }
    }
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    return JNI_VERSION_1_6;
}

// Invoked from a Go goroutine thread (not attached to the JVM) when the local
// adbd endpoint is unreachable. Attaches to the JVM and calls the static Java
// callback so the app can re-enable wireless debugging.
static void on_adb_unreachable_native(void) {
    if (g_vm == NULL || g_bridge_class == NULL || g_on_adb_unreachable_mid == NULL) {
        return;
    }
    JNIEnv *env = NULL;
    bool attached = false;
    jint getEnv = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (getEnv == JNI_EDETACHED || env == NULL) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK || env == NULL) {
            return;
        }
        attached = true;
    } else if (getEnv != JNI_OK) {
        return;
    }

    (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_adb_unreachable_mid);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

// Invoked from a Go goroutine thread when a script-message stream arrives.
// Attaches to JVM and forwards message to EdgeJoinBridge.onScriptMessageFromNative.
static void on_script_message_native(const char *peer_id, const char *protocol, const char *payload) {
    if (g_vm == NULL || g_bridge_class == NULL || g_on_script_message_mid == NULL) {
        return;
    }

    JNIEnv *env = NULL;
    bool attached = false;
    jint getEnv = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (getEnv == JNI_EDETACHED || env == NULL) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK || env == NULL) {
            return;
        }
        attached = true;
    } else if (getEnv != JNI_OK) {
        return;
    }

    jstring jPeer = (*env)->NewStringUTF(env, peer_id != NULL ? peer_id : "");
    jstring jProtocol = (*env)->NewStringUTF(env, protocol != NULL ? protocol : "");
    jstring jPayload = (*env)->NewStringUTF(env, payload != NULL ? payload : "");

    if (jPeer != NULL && jProtocol != NULL && jPayload != NULL) {
        (*env)->CallStaticVoidMethod(env, g_bridge_class, g_on_script_message_mid, jPeer, jProtocol, jPayload);
    }

    if (jPeer != NULL) (*env)->DeleteLocalRef(env, jPeer);
    if (jProtocol != NULL) (*env)->DeleteLocalRef(env, jProtocol);
    if (jPayload != NULL) (*env)->DeleteLocalRef(env, jPayload);

    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
}

// Invoked from Go when /31025/{instance}/5 execute is requested.
// Returns a heap-allocated UTF-8 JSON string that Go will free via C.free.
static char *on_script_execute_native(const char *endpoint, const char *instance_id, const char *script_text, const char *params_json) {
    if (g_vm == NULL || g_bridge_class == NULL || g_on_execute_script_mid == NULL) {
        return strdup("{\"ok\":false,\"status_code\":0,\"error\":\"execute callback not initialized\"}");
    }

    JNIEnv *env = NULL;
    bool attached = false;
    jint getEnv = (*g_vm)->GetEnv(g_vm, (void **) &env, JNI_VERSION_1_6);
    if (getEnv == JNI_EDETACHED || env == NULL) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK || env == NULL) {
            return strdup("{\"ok\":false,\"status_code\":0,\"error\":\"attach jvm failed\"}");
        }
        attached = true;
    } else if (getEnv != JNI_OK) {
        return strdup("{\"ok\":false,\"status_code\":0,\"error\":\"get jni env failed\"}");
    }

    jstring jEndpoint = (*env)->NewStringUTF(env, endpoint != NULL ? endpoint : "");
    jstring jInstance = (*env)->NewStringUTF(env, instance_id != NULL ? instance_id : "");
    jstring jScript = (*env)->NewStringUTF(env, script_text != NULL ? script_text : "");
    jstring jParams = (*env)->NewStringUTF(env, params_json != NULL ? params_json : "{}");

    char *result = NULL;
    if (jEndpoint != NULL && jInstance != NULL && jScript != NULL && jParams != NULL) {
        jstring jOut = (jstring) (*env)->CallStaticObjectMethod(
                env, g_bridge_class, g_on_execute_script_mid, jEndpoint, jInstance, jScript, jParams);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        if (jOut != NULL) {
            const char *outUtf = (*env)->GetStringUTFChars(env, jOut, NULL);
            if (outUtf != NULL) {
                result = strdup(outUtf);
                (*env)->ReleaseStringUTFChars(env, jOut, outUtf);
            }
            (*env)->DeleteLocalRef(env, jOut);
        }
    }

    if (jEndpoint != NULL) (*env)->DeleteLocalRef(env, jEndpoint);
    if (jInstance != NULL) (*env)->DeleteLocalRef(env, jInstance);
    if (jScript != NULL) (*env)->DeleteLocalRef(env, jScript);
    if (jParams != NULL) (*env)->DeleteLocalRef(env, jParams);

    if (result == NULL) {
        result = strdup("{\"ok\":false,\"status_code\":0,\"error\":\"execute callback returned null\"}");
    }

    if (attached) {
        (*g_vm)->DetachCurrentThread(g_vm);
    }
    return result;
}


static const char *get_utf_or_empty(JNIEnv *env, jstring value, const char **release_ptr) {
    if (value == NULL) {
        *release_ptr = NULL;
        return "";
    }

    const char *utf = (*env)->GetStringUTFChars(env, value, NULL);
    if (utf == NULL) {
        *release_ptr = NULL;
        return "";
    }

    *release_ptr = utf;
    return utf;
}

static void release_utf(JNIEnv *env, jstring value, const char *release_ptr) {
    if (value != NULL && release_ptr != NULL) {
        (*env)->ReleaseStringUTFChars(env, value, release_ptr);
    }
}

static jstring make_error(JNIEnv *env, const char *message) {
    return (*env)->NewStringUTF(env, message);
}

static void *open_go_library(JNIEnv *env) {
    void *handle = dlopen(kGoLibraryName, RTLD_NOW);
    if (handle == NULL) {
        (void) env;
    }
    return handle;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeCreateIdentity(
        JNIEnv *env,
        jclass clazz,
        jstring name) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_create_identity_fn edge_create_identity = (edge_create_identity_fn) dlsym(handle, "EdgeCreateIdentity");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_create_identity == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_name = NULL;
    const char *native_name = get_utf_or_empty(env, name, &release_name);

    char *response = edge_create_identity(native_name);

    release_utf(env, name, release_name);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeStartClient(
        JNIEnv *env,
        jclass clazz,
        jstring config_json) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_start_client_fn edge_start_client = (edge_start_client_fn) dlsym(handle, "EdgeStartClient");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_start_client == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    // Register (or refresh) the adb-unreachable callback so the Go client can ask
    // the app to re-enable wireless debugging when the local adbd port is closed.
    edge_register_adb_unreachable_fn edge_register =
            (edge_register_adb_unreachable_fn) dlsym(handle, "EdgeRegisterAdbUnreachableCallback");
    if (edge_register != NULL) {
        edge_register(&on_adb_unreachable_native);
    }

    const char *release_config = NULL;
    const char *native_config = get_utf_or_empty(env, config_json, &release_config);
    char *response = edge_start_client(native_config);
    release_utf(env, config_json, release_config);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeStopClient(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_stop_client_fn edge_stop_client = (edge_stop_client_fn) dlsym(handle, "EdgeStopClient");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_stop_client == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    char *response = edge_stop_client();
    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeProvideScrcpyJar(
        JNIEnv *env,
        jclass clazz,
        jbyteArray bytes) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_provide_scrcpy_jar_fn edge_provide = (edge_provide_scrcpy_jar_fn) dlsym(handle, "EdgeProvideScrcpyJar");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_provide == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    char *response;
    if (bytes == NULL) {
        response = edge_provide(NULL, 0);
    } else {
        jsize len = (*env)->GetArrayLength(env, bytes);
        jbyte *buf = (*env)->GetByteArrayElements(env, bytes, NULL);
        if (buf == NULL) {
            dlclose(handle);
            return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to access byte array\"}");
        }
        response = edge_provide((const char *) buf, (int) len);
        (*env)->ReleaseByteArrayElements(env, bytes, buf, JNI_ABORT);
    }

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativePairWireless(
        JNIEnv *env,
        jclass clazz,
        jstring host,
        jint port,
        jstring code,
        jstring package_name,
        jstring debug_host,
        jint debug_port) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_pair_wireless_fn edge_pair_wireless = (edge_pair_wireless_fn) dlsym(handle, "EdgePairWireless");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_pair_wireless == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_host = NULL;
    const char *release_code = NULL;
    const char *release_package = NULL;
    const char *release_debug_host = NULL;
    const char *native_host = get_utf_or_empty(env, host, &release_host);
    const char *native_code = get_utf_or_empty(env, code, &release_code);
    const char *native_package = get_utf_or_empty(env, package_name, &release_package);
    const char *native_debug_host = get_utf_or_empty(env, debug_host, &release_debug_host);

    char *response = edge_pair_wireless(native_host, (int) port, native_code, native_package,
                                        native_debug_host, (int) debug_port);

    release_utf(env, host, release_host);
    release_utf(env, code, release_code);
    release_utf(env, package_name, release_package);
    release_utf(env, debug_host, release_debug_host);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeProvisionDeviceOwner(
        JNIEnv *env,
        jclass clazz,
        jstring debug_host,
        jint debug_port,
        jstring package_name) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_provision_device_owner_fn edge_provision =
            (edge_provision_device_owner_fn) dlsym(handle, "EdgeProvisionDeviceOwner");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_provision == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_debug_host = NULL;
    const char *release_package = NULL;
    const char *native_debug_host = get_utf_or_empty(env, debug_host, &release_debug_host);
    const char *native_package = get_utf_or_empty(env, package_name, &release_package);

    char *response = edge_provision(native_debug_host, (int) debug_port, native_package);

    release_utf(env, debug_host, release_debug_host);
    release_utf(env, package_name, release_package);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeSetAdbProxyTarget(
        JNIEnv *env,
        jclass clazz,
        jstring host,
        jint port) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_set_adb_proxy_target_fn edge_set_target =
            (edge_set_adb_proxy_target_fn) dlsym(handle, "EdgeSetAdbProxyTarget");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_set_target == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_host = NULL;
    const char *native_host = get_utf_or_empty(env, host, &release_host);

    char *response = edge_set_target(native_host, (int) port);

    release_utf(env, host, release_host);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeRegisterScriptMessageCallback(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_register_script_message_fn edge_register =
            (edge_register_script_message_fn) dlsym(handle, "EdgeRegisterScriptMessageCallback");
    if (edge_register == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbol EdgeRegisterScriptMessageCallback\"}");
    }

    edge_register(&on_script_message_native);
    dlclose(handle);
    return (*env)->NewStringUTF(env, "{\"ok\":true,\"status_code\":200,\"state\":\"registered\"}");
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeSendScriptMessage(
        JNIEnv *env,
        jclass clazz,
        jstring peer_id,
        jstring protocol,
        jstring payload) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_send_script_message_fn edge_send =
            (edge_send_script_message_fn) dlsym(handle, "EdgeSendScriptMessage");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_send == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_peer = NULL;
    const char *release_protocol = NULL;
    const char *release_payload = NULL;
    const char *native_peer = get_utf_or_empty(env, peer_id, &release_peer);
    const char *native_protocol = get_utf_or_empty(env, protocol, &release_protocol);
    const char *native_payload = get_utf_or_empty(env, payload, &release_payload);

    char *response = edge_send(native_peer, native_protocol, native_payload);

    release_utf(env, peer_id, release_peer);
    release_utf(env, protocol, release_protocol);
    release_utf(env, payload, release_payload);

    jstring output;
    if (response == NULL) {
        output = make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeRegisterScriptExecuteCallback(
        JNIEnv *env,
        jclass clazz) {
    (void) clazz;

    void *handle = open_go_library(env);
    if (handle == NULL) {
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_register_script_execute_fn edge_register =
            (edge_register_script_execute_fn) dlsym(handle, "EdgeRegisterScriptExecuteCallback");
    if (edge_register == NULL) {
        dlclose(handle);
        return make_error(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbol EdgeRegisterScriptExecuteCallback\"}");
    }

    edge_register(&on_script_execute_native);
    dlclose(handle);
    return (*env)->NewStringUTF(env, "{\"ok\":true,\"status_code\":200,\"state\":\"registered\"}");
}
