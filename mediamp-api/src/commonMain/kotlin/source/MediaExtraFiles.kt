package org.openani.mediamp.source

public class MediaExtraFiles(
    public val subtitles: List<Subtitle> = emptyList(),
) {
    public companion object {
        public val Empty: MediaExtraFiles = MediaExtraFiles()
    }
}

public class Subtitle(
    /**
     * e.g. `https://example.com/1.ass`
     */
    public val uri: String,
    public val mimeType: String? = null,
    public val language: String? = null,
)
