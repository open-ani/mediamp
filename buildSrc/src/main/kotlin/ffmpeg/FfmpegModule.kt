package ffmpeg

import org.gradle.api.Project

fun Project.configureMediampFfmpegModule() {
    val context = FfmpegBuildContext(this, project.projectDir.resolve("fix-prepare-cli-args-on-win-and-clean-up.patch"))
    registerHostFfmpegTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val appleRuntimeJarTasks = registerAppleRuntimeJarTasks(context)
    val prepareTask = registerAndroidJniPackaging(context)
    wireAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks, appleRuntimeJarTasks)
}
