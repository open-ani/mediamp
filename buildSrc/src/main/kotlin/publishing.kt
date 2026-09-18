/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType

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

/** One `<license>` entry of a Maven POM. */
data class PomLicense(val name: String, val url: String)

/**
 * Licenses that mediamp artifacts are published under. A published artifact carries every
 * license that applies to something inside it: the mediamp sources are Apache-2.0, while the
 * bundled native runtimes add the license of the libraries they contain (see the README
 * "License" section for the per-artifact breakdown).
 */
object PomLicenses {
    val APACHE_2 = PomLicense(
        name = "The Apache License, Version 2.0",
        url = "https://www.apache.org/licenses/LICENSE-2.0.txt",
    )

    /** libmpv built with `-Dgpl=false`, FFmpeg built without `--enable-gpl`, libplacebo. */
    val LGPL_2_1_OR_LATER = PomLicense(
        name = "GNU Lesser General Public License, version 2.1 or later",
        url = "https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt",
    )

    /**
     * Apache-2.0 code combined with GPLv2-or-later libmpv (the Linux mpv runtime) or with
     * GPLv3 vlcj. Apache-2.0 is only compatible with GPL version 3, so the combined work is
     * distributed under GPLv3.
     */
    val GPL_3 = PomLicense(
        name = "GNU General Public License, Version 3",
        url = "https://www.gnu.org/licenses/gpl-3.0.txt",
    )

    /** Pure Kotlin/Java modules and native runtimes whose bundled libraries are all LGPL. */
    val APACHE_ONLY: List<PomLicense> = listOf(APACHE_2)
    val APACHE_WITH_LGPL_RUNTIME: List<PomLicense> = listOf(APACHE_2, LGPL_2_1_OR_LATER)
    val GPL_3_ONLY: List<PomLicense> = listOf(GPL_3)
}

private const val LICENSE_OVERRIDES_EXTRA = "mediamp.pomLicenseOverrides"

@Suppress("UNCHECKED_CAST")
private val Project.pomLicenseOverrides: MutableMap<String, List<PomLicense>>
    get() {
        val extra = extensions.extraProperties
        if (!extra.has(LICENSE_OVERRIDES_EXTRA)) {
            extra.set(LICENSE_OVERRIDES_EXTRA, linkedMapOf<String, List<PomLicense>>())
        }
        return extra.get(LICENSE_OVERRIDES_EXTRA) as MutableMap<String, List<PomLicense>>
    }

/**
 * Declares the licenses of one publication of this project, overriding the project-wide
 * default passed to [configurePom]. Must be called before the publication named
 * [publicationName] is created (POM licenses are applied when the publication is added).
 */
fun Project.setPublicationLicenses(publicationName: String, licenses: List<PomLicense>) {
    require(licenses.isNotEmpty()) { "Publication '$publicationName' must declare at least one license." }
    pomLicenseOverrides[publicationName] = licenses
}

/**
 * Configures the POM of every Maven publication of [project]. [licenses] applies to all
 * publications that have no [setPublicationLicenses] override; the override is looked up by
 * Gradle publication name when the publication is added.
 */
fun MavenPublishBaseExtension.configurePom(
    project: Project,
    licenses: List<PomLicense> = PomLicenses.APACHE_ONLY,
) {
    require(licenses.isNotEmpty()) { "Project '${project.path}' must declare at least one license." }

    pom {
        name.set(project.name)
        description.set(project.description)
        url.set("https://github.com/open-ani/mediamp")

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

    // `licenses {}` is additive, so each publication's licenses are written exactly once here
    // instead of through the shared `pom {}` block above.
    project.extensions.getByType<PublishingExtension>().publications
        .withType<MavenPublication>()
        .configureEach {
            val effective = project.pomLicenseOverrides[name] ?: licenses
            pom.licenses {
                effective.forEach { entry ->
                    license {
                        name.set(entry.name)
                        url.set(entry.url)
                        distribution.set("repo")
                    }
                }
            }
        }
}
