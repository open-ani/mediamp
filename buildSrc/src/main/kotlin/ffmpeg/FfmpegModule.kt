package ffmpeg

import org.gradle.api.Project

fun Project.configureMediampFfmpegModule() {
    val context = FfmpegBuildContext(this)
    registerHostFfmpegTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val appleRuntimeJarTasks = registerAppleRuntimeJarTasks(context)
    val prepareTask = registerAndroidJniPackaging(context)
    wireAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks, appleRuntimeJarTasks)
}
