package mpv

import org.gradle.api.Project

fun Project.configureMediampMpvModule() {
    evaluationDependsOn(":mediamp-ffmpeg")

    val context = MpvBuildContext(this)
    registerHostMpvTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerMpvAndroidJniPackaging(context)
    wireMpvAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks)
}
