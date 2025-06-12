#include <jni.h>

#if defined(_WIN32)
#include <windows.h>
#include <GL/gl.h>
#else

#include <GL/gl.h>

#endif

extern "C" {
JNIEXPORT void JNICALL
Java_org_openani_mediamp_mpv_GLExt_glViewport(
        JNIEnv * , jclass , jint x, jint
y , jint width, jint
height ) {
::glViewport(x, y, width, height ) ;
}
}
