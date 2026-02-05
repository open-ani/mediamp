cmake_minimum_required(VERSION 3.21)

project("mediampv" LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# set prebuilt mpv library path for each target

set(MPV_LIB_PATH "")
set(MPV_LIB_NAME "")
set(MPV_LINK_LIBS "")
set(MPV_SKIP_COPY FALSE)

if(WIN32)
    add_compile_definitions(_WIN32_WINNT=0x0A00)
    # Windows
    if(CMAKE_SIZEOF_VOID_P EQUAL 8)
        # x64
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/windows/x86_64")
        set(MPV_LIB_NAME "libmpv-2.lib")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "avcodec-61.lib")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "swscale-8.lib")
        set(MPV_LINK_LIBS
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_AVCODEC_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_SWSCALE_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_NAME}")
    else()
        message(FATAL_ERROR "only support x86_64 on Windows")
    endif()

    if (MSVC)
        message(STATUS "MSVC detected")
        add_compile_options(/Zc:__cplusplus)
        set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>DLL")
    endif ()
elseif(APPLE)
    # macOS (Darwin)
    if((CMAKE_SYSTEM_PROCESSOR STREQUAL "x86_64") OR (CMAKE_SYSTEM_PROCESSOR STREQUAL "arm64"))
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/macos/${CMAKE_SYSTEM_PROCESSOR}")
        set(MPV_LIB_NAME "libmpv.2.dylib")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "libavcodec.61.19.100.dylib")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "libswscale.8.3.100.dylib")
        set(MPV_LINK_LIBS
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_AVCODEC_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_SWSCALE_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_NAME}")
    endif()
elseif(ANDROID)
    set(ANDROID_STL c++_shared)
    set(CMAKE_SYSTEM_NAME Android)
    set(CMAKE_SYSTEM_VERSION 21) # Adjust as needed

    # Set Android NDK paths
    set(ANDROID_NDK ${ANDROID_NDK_HOME})
    set(CMAKE_ANDROID_NDK ${ANDROID_NDK})
    set(CMAKE_TOOLCHAIN_FILE ${ANDROID_NDK}/build/cmake/android.toolchain.cmake)

    if ((CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
        OR (CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a")
        OR (CMAKE_ANDROID_ARCH_ABI STREQUAL "x86_64")
    )
        set(MPV_LIB_PATH "${CMAKE_CURRENT_SOURCE_DIR}/libmpv/lib/android/${CMAKE_ANDROID_ARCH_ABI}")
        set(MPV_LIB_NAME "libmpv.so")
        set(MPV_LIB_EXTERNAL_AVCODEC_NAME "libavcodec.so")
        set(MPV_LIB_EXTERNAL_SWSCALE_NAME "libswscale.so")
        set(MPV_LINK_LIBS
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_AVCODEC_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_EXTERNAL_SWSCALE_NAME}"
            "${MPV_LIB_PATH}/${MPV_LIB_NAME}")
    endif()
elseif(UNIX)
    # Linux (and other UNIX-like systems)
    find_package(PkgConfig REQUIRED)
    pkg_check_modules(MPV REQUIRED IMPORTED_TARGET mpv)
    find_package(OpenGL REQUIRED)
    find_package(X11 REQUIRED)
    set(MPV_LINK_LIBS PkgConfig::MPV)
    set(MPV_SKIP_COPY TRUE)
else()
    message(FATAL_ERROR "Unknown operating system")
endif()

# configure project

include_directories("${CMAKE_CURRENT_SOURCE_DIR}/libmpv/include")
if (MPV_LIB_PATH)
    link_directories(${MPV_LIB_PATH})
endif()

if (NOT ANDROID) 
    set(JAVA_AWT_LIBRARY NotNeeded) # todo
    set(JAVA_JVM_LIBRARY NotNeeded) # todo
    find_package(JNI REQUIRED)
    include_directories(${JNI_INCLUDE_DIRS})
endif()

set(SRC_PREFIX "${CMAKE_CURRENT_SOURCE_DIR}/src/cpp")

add_library(${PROJECT_NAME} SHARED
    "${SRC_PREFIX}/main.cpp"
    "${SRC_PREFIX}/include/log.h"
    "${SRC_PREFIX}/include/global_lock.h"
    "${SRC_PREFIX}/include/compatible_thread.h"
    "${SRC_PREFIX}/compatible_thread.cpp"
    "${SRC_PREFIX}/include/method_cache.h"
    "${SRC_PREFIX}/method_cache.cpp"
    "${SRC_PREFIX}/event_listener.cpp"
    "${SRC_PREFIX}/include/mpv_handle_t.h"
    "${SRC_PREFIX}/mpv_handle_t.cpp"
    "${SRC_PREFIX}/jni.cpp"
)
target_include_directories(${PROJECT_NAME} PRIVATE "${SRC_PREFIX}/include")

## link project
if (ANDROID)
    target_link_libraries(${PROJECT_NAME} PRIVATE 
            android log
            ${MPV_LINK_LIBS})
else()
    # 假设 JNI_INCLUDE_DIRS 是一个包含多个路径的列表
    set(FILTERED_JNI_LIBRARIES "")

    # 遍历 JNI_INCLUDE_DIRS，移除包含 "NotNeeded" 的值
    foreach(dir ${JNI_LIBRARIES})
        if(NOT dir MATCHES "NotNeeded")
            list(APPEND FILTERED_JNI_LIBRARIES ${dir})
        endif()
    endforeach()

    target_link_libraries(${PROJECT_NAME} PRIVATE
            ${FILTERED_JNI_LIBRARIES}
            ${MPV_LINK_LIBS})
    if(WIN32)
        target_link_libraries(${PROJECT_NAME} PRIVATE
                opengl32)
    endif()
    if(UNIX AND NOT APPLE AND NOT ANDROID)
        if(TARGET OpenGL::GL)
            target_link_libraries(${PROJECT_NAME} PRIVATE OpenGL::GL)
        elseif(TARGET OpenGL::OpenGL)
            target_link_libraries(${PROJECT_NAME} PRIVATE OpenGL::OpenGL)
        endif()
        if(TARGET OpenGL::GLX)
            target_link_libraries(${PROJECT_NAME} PRIVATE OpenGL::GLX)
        endif()
        if(TARGET X11::X11)
            target_link_libraries(${PROJECT_NAME} PRIVATE X11::X11)
        endif()
        if(X11_INCLUDE_DIR)
            target_include_directories(${PROJECT_NAME} PRIVATE ${X11_INCLUDE_DIR})
        endif()
    endif()
endif()

if (NOT MPV_SKIP_COPY)
    # 查找所有动态库文件
    file(GLOB MPV_LIBS_DEPS
        "${MPV_LIB_PATH}/*.dylib"  # macOS 动态库
        "${MPV_LIB_PATH}/*.so"     # Linux 和 Android 动态库
        "${MPV_LIB_PATH}/*.dll"    # Windows 动态库
    )

    set(MPV_OUTPUT_LIBS_DIR "")
    if (WIN32 OR ANDROID)
        set(MPV_OUTPUT_LIBS_DIR $<TARGET_FILE_DIR:${PROJECT_NAME}>)
    else ()
        set(MPV_OUTPUT_LIBS_DIR "$<TARGET_FILE_DIR:${PROJECT_NAME}>/deps/")
    endif ()

    # 在构建后复制所有动态库文件到目标目录
    add_custom_command(TARGET ${PROJECT_NAME} POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E echo "Copying dynamic library files..."
        COMMAND ${CMAKE_COMMAND} -E make_directory ${MPV_OUTPUT_LIBS_DIR}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
            ${MPV_LIBS_DEPS}
            ${MPV_OUTPUT_LIBS_DIR}
    )
endif()


message(STATUS "MPV library path: ${MPV_LIB_PATH}")
message(STATUS "MPV library name: ${MPV_LIB_NAME}")
