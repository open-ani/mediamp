#include <jni.h>

#if defined(_WIN32)
#include <windows.h>
#include <GL/glew.h>
#include <GL/gl.h>
#else

#include <GL/gl.h>

#endif

struct OffscreenTexture {
    GLuint texture;
    GLuint fbo;
};

extern "C" {
JNIEXPORT jlong
JNICALL Java_org_openani_mediamp_mpv_OffscreenGL_createTextureFbo(
        JNIEnv *, jclass, jint width, jint height) {
    GLuint tex;
    glGenTextures(1, &tex);
    glBindTexture(GL_TEXTURE_2D, tex);
    glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

    GLuint fbo;
    glGenFramebuffers(1, &fbo);
    glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);

    OffscreenTexture *off = new OffscreenTexture{tex, fbo};
    return reinterpret_cast<jlong>(off);
}

JNIEXPORT jint
JNICALL Java_org_openani_mediamp_mpv_OffscreenGL_getFboId(
        JNIEnv *, jclass, jlong ptr) {
    OffscreenTexture *off = reinterpret_cast<OffscreenTexture *>(ptr);
    return off->fbo;
}

JNIEXPORT void JNICALL
Java_org_openani_mediamp_mpv_OffscreenGL_disposeTextureFbo(
        JNIEnv * , jclass , jlong ptr ) {
OffscreenTexture *off = reinterpret_cast<OffscreenTexture *>(ptr);
if ( ! off ) return ;
glDeleteFramebuffers ( 1 , & off -> fbo ) ;
glDeleteTextures ( 1 , & off -> texture ) ;
delete off;
}
}
