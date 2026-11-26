package com.hdz.wearcontrol.desktop;

import com.hdz.wearcontrol.WearEarsBinauralKernel;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.util.function.Consumer;

/**
 * Real-time 48 kHz stereo Java Sound audition engine directly reusing core/'s
 * WearEarsBinauralKernel (ITD + Brown-Duda ILD + Parametric Pinna + Hadamard FDN).
 */
public final class BinauralAuditionEngine {
    private static final int SAMPLE_RATE = WearEarsBinauralKernel.SAMPLE_RATE;
    private static final int BLOCK = WearEarsBinauralKernel.BLOCK_SIZE;

    private volatile boolean playing;
    private volatile boolean autoOrbit;
    private volatile int auditionMode = WearEarsBinauralKernel.AUDITION_CHORD_TEXTURE;
    private volatile float azimuthDeg = 0.0f;
    private volatile int scene = WearEarsBinauralKernel.SCENE_WIDE;
    private volatile int headWidthPct = WearEarsBinauralKernel.HEAD_WIDTH_DEFAULT_PCT;
    private volatile int pinnaNotchHz = WearEarsBinauralKernel.PINNA_NOTCH_DEFAULT_HZ;
    private volatile float ceilingGain = 0.65f; // Conservative safe listening ceiling by default
    private volatile Thread audioThread;

    public boolean isPlaying() {
        return playing;
    }

    public boolean isAutoOrbit() {
        return autoOrbit;
    }

    public void setAutoOrbit(boolean autoOrbit) {
        this.autoOrbit = autoOrbit;
    }

    public float getAzimuthDeg() {
        return azimuthDeg;
    }

    public void setAzimuthDeg(float deg) {
        float wrapped = ((deg % 360f) + 540f) % 360f - 180f;
        this.azimuthDeg = wrapped;
    }

    public void setScene(int scene) {
        this.scene = scene;
    }

    public int getScene() {
        return scene;
    }

    public void setHeadWidthPct(int pct) {
        this.headWidthPct = pct;
    }

    public int getHeadWidthPct() {
        return headWidthPct;
    }

    public void setPinnaNotchHz(int hz) {
        this.pinnaNotchHz = hz;
    }

    public int getPinnaNotchHz() {
        return pinnaNotchHz;
    }

    public void setAuditionMode(int mode) {
        this.auditionMode = mode;
    }

    public int getAuditionMode() {
        return auditionMode;
    }

    public void setSafetyCeilingGain(float gain) {
        this.ceilingGain = Math.max(0.10f, Math.min(1.0f, gain));
    }

    public synchronized void start(Consumer<Float> azimuthCallback, Consumer<String> errorCallback) {
        if (playing) return;
        Thread previous = audioThread;
        playing = true;
        Thread t = new Thread(() -> runLoop(previous, azimuthCallback, errorCallback), "WearEars-BinauralAudio");
        t.setDaemon(true);
        audioThread = t;
        t.start();
    }

    public synchronized void stop() {
        playing = false;
    }

    void awaitStopped(long millis) throws InterruptedException {
        Thread stopping = audioThread;
        if (stopping != null) stopping.join(millis);
    }

    private boolean playingHere() {
        return playing && Thread.currentThread() == audioThread;
    }

    private void runLoop(Thread previous, Consumer<Float> azimuthCallback, Consumer<String> errorCallback) {
        AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, 2, true, false);
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
        SourceDataLine line = null;
        try {
            // Allow a previous fade-out to finish before opening the same audio device again.
            if (previous != null) previous.join();
            if (!playingHere()) return;
            line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(fmt, BLOCK * 4 * 8);
            if (!playingHere()) return;
            line.start();

            WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
            kernel.reset();
            kernel.resetAuditionGenerator();
            kernel.setScene(scene);
            kernel.setHeadWidthPct(headWidthPct);
            kernel.setPinnaNotchHz(pinnaNotchHz);
            kernel.setAzimuthDeg(azimuthDeg);
            kernel.setOutputGainCeiling(ceilingGain);
            kernel.fadeIn();

            float[] mono = new float[BLOCK];
            float[] outL = new float[BLOCK];
            float[] outR = new float[BLOCK];
            byte[] pcm = new byte[BLOCK * 4];
            int uiTick = 0;

            while (playingHere()) {
                if (autoOrbit) {
                    // 45 deg/sec smooth orbit around listener head
                    float next = azimuthDeg + (45.0f * BLOCK / SAMPLE_RATE);
                    if (next >= 180.0f) next -= 360.0f;
                    azimuthDeg = next;
                    if (azimuthCallback != null && (++uiTick % 6 == 0)) {
                        azimuthCallback.accept(next);
                    }
                }
                kernel.setAzimuthDeg(azimuthDeg);
                kernel.setScene(scene);
                kernel.setHeadWidthPct(headWidthPct);
                kernel.setPinnaNotchHz(pinnaNotchHz);
                kernel.setOutputGainCeiling(ceilingGain);

                kernel.fillAuditionSource(mono, BLOCK, auditionMode);
                kernel.processMono(mono, outL, outR, BLOCK);
                packPcm16Le(outL, outR, pcm, BLOCK);
                line.write(pcm, 0, pcm.length);
            }

            // Click-free 25 ms fade-out tail (~5 blocks of 256 samples)
            kernel.fadeOut();
            for (int tail = 0; tail < 6 && !kernel.isFadedOut(); tail++) {
                kernel.fillAuditionSource(mono, BLOCK, auditionMode);
                kernel.processMono(mono, outL, outR, BLOCK);
                packPcm16Le(outL, outR, pcm, BLOCK);
                line.write(pcm, 0, pcm.length);
            }
            line.drain();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (LineUnavailableException | RuntimeException ex) {
            boolean report;
            synchronized (this) {
                report = playingHere();
                if (report) playing = false;
            }
            if (report && errorCallback != null) errorCallback.accept("Audio playback: " + ex.getMessage());
        } finally {
            if (line != null) {
                try { line.stop(); } catch (Exception ignored) { }
                try { line.close(); } catch (Exception ignored) { }
            }
            synchronized (this) {
                if (audioThread == Thread.currentThread()) {
                    audioThread = null;
                    playing = false;
                }
            }
        }
    }

    private static void packPcm16Le(float[] left, float[] right, byte[] pcm, int frames) {
        int p = 0;
        for (int i = 0; i < frames; i++) {
            int l = Math.round(Math.max(-1.0f, Math.min(1.0f, left[i])) * 32767.0f);
            int r = Math.round(Math.max(-1.0f, Math.min(1.0f, right[i])) * 32767.0f);
            pcm[p++] = (byte) (l & 0xFF);
            pcm[p++] = (byte) ((l >> 8) & 0xFF);
            pcm[p++] = (byte) (r & 0xFF);
            pcm[p++] = (byte) ((r >> 8) & 0xFF);
        }
    }
}
