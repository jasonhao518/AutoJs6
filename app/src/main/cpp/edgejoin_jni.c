#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>

typedef char *(*edge_join_fn)(const char *, const char *, const char *, const char *, const char *);
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

JNIEXPORT jstring JNICALL
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeJoin(
        JNIEnv *env,
        jclass clazz,
        jstring serial_number,
        jstring join_key,
        jstring version,
        jstring name,
        jstring endpoint) {
    (void) clazz;

    void *handle = dlopen(kGoLibraryName, RTLD_NOW);
    if (handle == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_join_fn edge_join = (edge_join_fn) dlsym(handle, "EdgeJoin");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_join == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_serial = NULL;
    const char *release_key = NULL;
    const char *release_version = NULL;
    const char *release_name = NULL;
    const char *release_endpoint = NULL;

    const char *native_serial = get_utf_or_empty(env, serial_number, &release_serial);
    const char *native_key = get_utf_or_empty(env, join_key, &release_key);
    const char *native_version = get_utf_or_empty(env, version, &release_version);
    const char *native_name = get_utf_or_empty(env, name, &release_name);
    const char *native_endpoint = get_utf_or_empty(env, endpoint, &release_endpoint);

    char *response = edge_join(native_serial, native_key, native_version, native_name, native_endpoint);

    release_utf(env, serial_number, release_serial);
    release_utf(env, join_key, release_key);
    release_utf(env, version, release_version);
    release_utf(env, name, release_name);
    release_utf(env, endpoint, release_endpoint);

    jstring output;
    if (response == NULL) {
        output = (*env)->NewStringUTF(env, "{\"ok\":false,\"status_code\":0,\"error\":\"native response is null\"}");
    } else {
        output = (*env)->NewStringUTF(env, response);
        edge_join_free(response);
    }

    dlclose(handle);
    return output;
}
