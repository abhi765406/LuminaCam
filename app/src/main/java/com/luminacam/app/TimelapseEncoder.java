package com.luminacam.app;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Builds an MP4 from individual frames. Frames are drawn onto the encoder's input surface;
 * their real-time arrival gaps (seconds apart) are discarded and replaced with an even
 * 1/fps spacing, which is what turns slow sampling into a fast-playing time-lapse.
 */
public final class TimelapseEncoder {

    private final File out;
    private final int w;
    private final int h;
    private final int fps;
    private MediaCodec enc;
    private MediaMuxer mux;
    private Surface surface;
    private int track = -1;
    private boolean muxStarted = false;
    private long samples = 0;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

    public TimelapseEncoder(File out, int w, int h, int fps) {
        this.out = out;
        this.w = w;
        this.h = h;
        this.fps = fps;
    }

    public void start() throws IOException {
        MediaFormat f = MediaFormat.createVideoFormat("video/avc", w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, w * h * 5);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        enc = MediaCodec.createEncoderByType("video/avc");
        enc.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        surface = enc.createInputSurface();
        enc.start();
        mux = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    public void addFrame(Bitmap bmp) {
        Canvas c = surface.lockCanvas(null);
        try {
            c.drawBitmap(bmp, null, new Rect(0, 0, w, h), null);
        } finally {
            surface.unlockCanvasAndPost(c);
        }
        drain(false);
    }

    public void finish() {
        try {
            enc.signalEndOfInputStream();
            drain(true);
        } catch (Throwable t) {
            Diag.log("Timelapse finish", t);
        }
        try {
            if (enc != null) {
                enc.stop();
                enc.release();
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mux != null) {
                if (muxStarted) mux.stop();
                mux.release();
            }
        } catch (Throwable ignored) {
        }
        if (surface != null) surface.release();
    }

    private void drain(boolean eos) {
        int tries = 0;
        while (true) {
            int idx = enc.dequeueOutputBuffer(info, eos ? 10000 : 0);
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos) break;
                if (++tries > 300) break;
                continue;
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track = mux.addTrack(enc.getOutputFormat());
                mux.start();
                muxStarted = true;
            } else if (idx >= 0) {
                ByteBuffer buf = enc.getOutputBuffer(idx);
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (buf != null && info.size > 0 && muxStarted) {
                    buf.position(info.offset);
                    buf.limit(info.offset + info.size);
                    info.presentationTimeUs = samples * 1_000_000L / fps;
                    samples++;
                    mux.writeSampleData(track, buf, info);
                }
                enc.releaseOutputBuffer(idx, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) break;
            }
        }
    }
}
