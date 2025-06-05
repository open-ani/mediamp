package org.openani.mediamp.mpv

/** Renderer used by the desktop mpv backend. */
interface MpvBufferRenderer {
    /**
     * Called when a new frame is rendered.
     *
     * @param width rendered frame width
     * @param height rendered frame height
     * @param data raw BGRA bytes
     */
    fun onFrame(width: Int, height: Int, data: ByteArray)
}
