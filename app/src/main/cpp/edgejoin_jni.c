#include <dlfcn.h>
#include <jni.h>
#include <stdbool.h>
#include <stdio.h>

typedef char *(*edge_create_identity_fn)(const char *);
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
Java_org_autojs_autojs_runtime_api_EdgeJoinBridge_nativeCreateIdentity(
        JNIEnv *env,
        jclass clazz,
        jstring name) {
    (void) clazz;

    void *handle = dlopen(kGoLibraryName, RTLD_NOW);
    if (handle == NULL) {
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to open libedgejoin.so\"}");
    }

    edge_create_identity_fn edge_create_identity = (edge_create_identity_fn) dlsym(handle, "EdgeCreateIdentity");
    edge_join_free_fn edge_join_free = (edge_join_free_fn) dlsym(handle, "EdgeJoinFree");
    if (edge_create_identity == NULL || edge_join_free == NULL) {
        dlclose(handle);
        return (*env)->NewStringUTF(env, "{\"ok\":false,\"status_code\":0,\"error\":\"failed to resolve Go symbols\"}");
    }

    const char *release_name = NULL;
    const char *native_name = get_utf_or_empty(env, name, &release_name);

    char *response = edge_create_identity(native_name);

    release_utf(env, name, release_name);

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
