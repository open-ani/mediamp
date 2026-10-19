package mpv

import org.gradle.api.Project

fun Project.configureMediampMpvModule() {
    evaluationDependsOn(":mediamp-ffmpeg")

    val mpvPatches = listOf(
        "render_d3d11.patch",
        "coreaudio-channel-map.patch",
    ).map(project.projectDir::resolve)
    val context = MpvBuildContext(this, mpvPatches)
    registerHostMpvTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerMpvAndroidJniPackaging(context)
    wireMpvAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks)
}
