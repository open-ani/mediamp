package ffmpeg

import org.gradle.api.Project

fun Project.configureMediampFfmpegModule() {
    val context = FfmpegBuildContext(this)
    registerHostFfmpegTasks(context)
    val runtimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerAndroidJniPackaging(context)
    wireAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, runtimeJarTasks)
}
