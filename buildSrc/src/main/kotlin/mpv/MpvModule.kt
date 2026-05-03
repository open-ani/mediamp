package mpv

import org.gradle.api.Project

fun Project.configureMediampMpvModule() {
    evaluationDependsOn(":mediamp-ffmpeg")

    val context = MpvBuildContext(this, project.projectDir.resolve("render_d3d11.patch"))
    registerHostMpvTasks(context)
    val desktopRuntimeJarTasks = registerDesktopRuntimeJarTasks(context)
    val prepareTask = registerMpvAndroidJniPackaging(context)
    wireMpvAndroidJniPackaging(context, prepareTask)
    configureRuntimePublishing(context, desktopRuntimeJarTasks)
}
