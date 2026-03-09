/*
 * Copyright (C) 2024-2025 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project

fun MavenPublishBaseExtension.signAllPublicationsIfEnabled(project: Project) {
    if (project.getPropertyOrNull("mediamp.sign.publications.disabled")?.toBoolean() == true) return
    if (!project.hasSigningCredentials()) {
        project.logger.lifecycle("Skipping publication signing: no Gradle signing credentials are configured.")
        return
    }
    signAllPublications()
}

private fun Project.hasSigningCredentials(): Boolean {
    fun prop(name: String): String? = getPropertyOrNull(name)?.takeIf { it.isNotBlank() }

    val inMemoryKey = prop("signingInMemoryKey")
    val inMemoryPassword = prop("signingInMemoryKeyPassword")
    val legacyKey = prop("signingKey")
    val legacyPassword = prop("signingPassword")
    val keyRing = prop("signing.secretKeyRingFile")
    val signingPassword = prop("signing.password")

    return (inMemoryKey != null && inMemoryPassword != null) ||
        (legacyKey != null && legacyPassword != null) ||
        (keyRing != null && signingPassword != null)
}

fun MavenPublishBaseExtension.configurePom(project: Project) {
    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/open-ani/mediamp")

        licenses {
            license {
                name.set("GNU General Public License, Version 3")
                url.set("https://github.com/open-ani/mediamp/blob/main/LICENSE")
                distribution.set("https://www.gnu.org/licenses/gpl-3.0.txt")
            }
        }

        developers {
            developer {
                id.set("openani")
                name.set("OpenAni and contributors")
                email.set("support@openani.org")
            }
        }

        scm {
            connection.set("scm:git:https://github.com/open-ani/mediamp.git")
            developerConnection.set("scm:git:git@github.com:open-ani/mediamp.git")
            url.set("https://github.com/open-ani/mediamp")
        }
    }
}
