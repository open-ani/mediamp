//
// Created by StageGuard on 12/28/2024.
//

#ifndef MEDIAMP_MPV_HANDLE_T_H
#define MEDIAMP_MPV_HANDLE_T_H

#include <iostream>
#include <jni.h>
#include <mpv/client.h>
#include "compatible_thread.h"
#include "log.h"

namespace mediampv {

class mpv_handle_t final {
public:
    void create(JNIEnv *env, jobject app_context);
    bool initialize();
    bool set_event_listener(JNIEnv *env, jobject listener);
    bool destroy(JNIEnv *env);
    
private:
    JavaVM *jvm_;
    mpv_handle *handle_;
    
    jobject event_listener_;

    std::shared_ptr<mediampv::compatible_thread> event_thread_;
    bool event_loop_request_exit = false;

    void *event_loop(void *arg);
};

} // namespace mediampv

#endif //MEDIAMP_MPV_HANDLE_T_H
