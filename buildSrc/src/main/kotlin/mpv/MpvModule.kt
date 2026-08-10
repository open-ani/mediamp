package mpv

import org.gradle.api.Project

fun Project.configureMediampMpvModule() {
    evaluationDependsOn(":mediamp-ffmpeg")

    // One file per upstream change, so each can be dropped independently once it lands:
    // - render_d3d11: mpv PR #17764 (libmpv D3D11 render API), targeted at 0.42.
    // - libmpv_gl_platform_exts: lets the OpenGL render API see WGL/GLX extension
    //   strings, without which WGL-gated interops (dxva2-dxinterop) can never be
    //   detected by an embedder. Not upstreamed yet.
    val context = MpvBuildContext(
        this,
        listOf(
            project.projectDir.resolve("render_d3d11.patch"),
            project.projectDir.resolve("libmpv_gl_platform_exts.patch"),
        ),
    )
    registerHostMpvTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerMpvAndroidJniPackaging(context)
    wireMpvAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks)
}
