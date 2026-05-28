#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>

typedef char *(*edge_create_identity_fn)(const char *);
typedef char *(*edge_start_client_fn)(const char *);
typedef char *(*edge_stop_client_fn)(void);
typedef void (*edge_join_free_fn)(char *);

static const char *kGoLibraryName = "libedgejoin.so";

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
