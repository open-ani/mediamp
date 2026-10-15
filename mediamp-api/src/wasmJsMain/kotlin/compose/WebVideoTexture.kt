@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * Use of this source code is governed by the Apache License version 2 license, which can be found at the following link.
 *
 * https://github.com/open-ani/mediamp/blob/main/LICENSE
 */

package org.openani.mediamp.compose

import org.khronos.webgl.WebGLRenderingContext
import org.khronos.webgl.WebGLTexture
import org.w3c.dom.HTMLVideoElement
import kotlin.js.JsAny

@JsFun("""(video, onFrame) => {
    let disposed = false;
    let callbackId = null;
    let previousTime = -1;
    const hasFrameCallback = typeof video.requestVideoFrameCallback === 'function';
    const notify = () => {
        if (!disposed) onFrame(video.videoWidth, video.videoHeight);
    };
    const tick = () => {
        if (disposed) return;
        if (hasFrameCallback || video.currentTime !== previousTime) {
            previousTime = video.currentTime;
            notify();
        }
        callbackId = hasFrameCallback
            ? video.requestVideoFrameCallback(tick)
            : window.requestAnimationFrame(tick);
    };
    const events = ['loadeddata', 'resize', 'seeked', 'emptied'];
    events.forEach(name => video.addEventListener(name, notify));
    notify();
    callbackId = hasFrameCallback
        ? video.requestVideoFrameCallback(tick)
        : window.requestAnimationFrame(tick);
    return () => {
        disposed = true;
        events.forEach(name => video.removeEventListener(name, notify));
        if (callbackId !== null) {
            if (hasFrameCallback) video.cancelVideoFrameCallback(callbackId);
            else window.cancelAnimationFrame(callbackId);
        }
    };
}""")
internal external fun observeVideoFrames(video: HTMLVideoElement, onFrame: (Int, Int) -> Unit): JsAny

@JsFun("(stop) => stop()")
internal external fun stopObservingVideoFrames(stop: JsAny)

@JsFun("""(video, onText) => {
    let tracks = [];
    const notify = () => {
        const text = Array.from(video.textTracks)
            .filter(track => track.mode === 'showing')
            .flatMap(track => Array.from(track.activeCues || []))
            .map(cue => cue.getCueAsHTML ? cue.getCueAsHTML().textContent : cue.text || '')
            .join('\n');
        onText(text);
    };
    const refresh = () => {
        tracks.forEach(track => track.removeEventListener('cuechange', notify));
        tracks = Array.from(video.textTracks);
        tracks.forEach(track => track.addEventListener('cuechange', notify));
        notify();
    };
    video.textTracks.addEventListener('addtrack', refresh);
    video.textTracks.addEventListener('removetrack', refresh);
    video.textTracks.addEventListener('change', notify);
    video.addEventListener('emptied', notify);
    refresh();
    return () => {
        tracks.forEach(track => track.removeEventListener('cuechange', notify));
        video.textTracks.removeEventListener('addtrack', refresh);
        video.textTracks.removeEventListener('removetrack', refresh);
        video.textTracks.removeEventListener('change', notify);
        video.removeEventListener('emptied', notify);
    };
}""")
internal external fun observeVideoSubtitles(video: HTMLVideoElement, onText: (String) -> Unit): JsAny

@JsFun("(video) => video.readyState >= 2 && video.videoWidth > 0 && video.videoHeight > 0")
internal external fun hasVideoFrame(video: HTMLVideoElement): Boolean

/** Uploads into a Compose-owned texture without reallocating it. Call inside render(). */
@JsFun("""(gl, texture, video) => {
    const activeTexture = gl.getParameter(gl.ACTIVE_TEXTURE);
    gl.activeTexture(gl.TEXTURE0);
    const binding = gl.getParameter(gl.TEXTURE_BINDING_2D);
    const flipY = gl.getParameter(gl.UNPACK_FLIP_Y_WEBGL);
    const premultiply = gl.getParameter(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL);
    try {
        gl.bindTexture(gl.TEXTURE_2D, texture);
        // Compose adopts this texture with BOTTOM_LEFT origin.
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, true);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, true);
        gl.texSubImage2D(gl.TEXTURE_2D, 0, 0, 0, gl.RGBA, gl.UNSIGNED_BYTE, video);
    } finally {
        gl.pixelStorei(gl.UNPACK_FLIP_Y_WEBGL, flipY);
        gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, premultiply);
        gl.bindTexture(gl.TEXTURE_2D, binding);
        gl.activeTexture(activeTexture);
    }
}""")
internal external fun uploadVideoFrame(gl: WebGLRenderingContext, texture: WebGLTexture, video: HTMLVideoElement)

@JsFun("""(gl) => {
    const scissor = gl.isEnabled(gl.SCISSOR_TEST);
    const colorMask = gl.getParameter(gl.COLOR_WRITEMASK);
    const clearColor = gl.getParameter(gl.COLOR_CLEAR_VALUE);
    try {
        gl.disable(gl.SCISSOR_TEST);
        gl.colorMask(true, true, true, true);
        gl.clearColor(0, 0, 0, 0);
        gl.clear(gl.COLOR_BUFFER_BIT);
    } finally {
        if (scissor) gl.enable(gl.SCISSOR_TEST);
        gl.colorMask(...colorMask);
        gl.clearColor(...clearColor);
    }
}""")
internal external fun clearVideoTexture(gl: WebGLRenderingContext)
