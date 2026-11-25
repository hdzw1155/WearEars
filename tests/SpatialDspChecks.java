package com.hdz.wearcontrol;

/**
 * Pure JVM DSP verification suite for {@link WearEarsBinauralKernel}.
 * Auto-discovered and executed by {@code build.py test}.
 */
public final class SpatialDspChecks {
    private static int checks = 0;

    private static void check(boolean ok, String message) {
        if (!ok) {
            throw new AssertionError("SpatialDspChecks failed: " + message);
        }
        checks++;
    }

    private static void itdPolarityAndAccuracy() {
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        int n = 256;
        float[] in = new float[n];
        float[] outL = new float[n];
        float[] outR = new float[n];
        in[0] = 0.8f;

        // 1. +90° (right): Right ear must arrive earlier than Left ear by ~0.65 ms (~31.5 samples)
        kernel.setScene(WearEarsBinauralKernel.SCENE_STUDIO);
        kernel.setHeadWidthPct(100);
        kernel.setAzimuthDeg(90.0f);
        kernel.reset();
        kernel.processMono(in, outL, outR, n);

        int peakR = argMaxAbs(outR, 0, 128);
        int peakL = argMaxAbs(outL, 0, 128);
        double deltaMsRight = (peakL - peakR) * 1000.0 / WearEarsBinauralKernel.SAMPLE_RATE;
        check(peakR < peakL, "+90° must arrive at Right ear before Left ear (peakR=" + peakR + ", peakL=" + peakL + ")");
        check(deltaMsRight > 0.55 && deltaMsRight < 0.78,
                "+90° ITD should be ~0.65 ms, got " + deltaMsRight + " ms");

        // 2. -90° (left): Left ear must arrive earlier than Right ear by ~0.65 ms
        kernel.setAzimuthDeg(-90.0f);
        kernel.reset();
        kernel.processMono(in, outL, outR, n);

        int peakL2 = argMaxAbs(outL, 0, 128);
        int peakR2 = argMaxAbs(outR, 0, 128);
        double deltaMsLeft = (peakR2 - peakL2) * 1000.0 / WearEarsBinauralKernel.SAMPLE_RATE;
        check(peakL2 < peakR2, "-90° must arrive at Left ear before Right ear (peakL=" + peakL2 + ", peakR=" + peakR2 + ")");
        check(deltaMsLeft > 0.55 && deltaMsLeft < 0.78,
                "-90° ITD should be ~0.65 ms, got " + deltaMsLeft + " ms");
        check(Math.abs(deltaMsRight - deltaMsLeft) < 0.05, "ITD polarity should be symmetric between +90° and -90°");

        // 3. 0° (front): Direct arrival must be symmetric between Left and Right
        kernel.setAzimuthDeg(0.0f);
        kernel.reset();
        kernel.processMono(in, outL, outR, n);
        float maxDiff = 0.0f;
        for (int i = 0; i < 128; i++) {
            float d = Math.abs(outL[i] - outR[i]);
            if (d > maxDiff) maxDiff = d;
        }
        check(maxDiff < 1e-5f, "0° direct arrival must be symmetric (maxDiff=" + maxDiff + ")");

        // 4. Head width calibration (115% > 85% ITD)
        kernel.setAzimuthDeg(90.0f);
        kernel.setHeadWidthPct(85);
        kernel.reset();
        kernel.processMono(in, outL, outR, n);
        int diffNarrow = argMaxAbs(outL, 0, 128) - argMaxAbs(outR, 0, 128);

        kernel.setHeadWidthPct(115);
        kernel.reset();
        kernel.processMono(in, outL, outR, n);
        int diffWide = argMaxAbs(outL, 0, 128) - argMaxAbs(outR, 0, 128);
        check(diffWide > diffNarrow + 4,
                "Head width 115% must yield larger ITD than 85% (" + diffWide + " vs " + diffNarrow + " samples)");
    }

    private static void brownDudaIldShadow() {
        // Measure steady-state sine response at +90° (Right ipsilateral, Left contralateral)
        double[] hfRight = measureSineRms(90.0f, 8000.0, 8000);
        double ildHfDb = 20.0 * Math.log10(hfRight[1] / Math.max(1e-9, hfRight[0]));
        check(ildHfDb > 6.0,
                "8 kHz contralateral Left ear at +90° must be > 6 dB lower than Right ear (got " + ildHfDb + " dB)");

        // Measure -90° (Left ipsilateral, Right contralateral)
        double[] hfLeft = measureSineRms(-90.0f, 8000.0, 8000);
        double ildHfLeftDb = 20.0 * Math.log10(hfLeft[0] / Math.max(1e-9, hfLeft[1]));
        check(ildHfLeftDb > 6.0,
                "8 kHz contralateral Right ear at -90° must be > 6 dB lower than Left ear (got " + ildHfLeftDb + " dB)");

        // Bass at 200 Hz should remain nearly equal (< 2 dB difference)
        double[] bass = measureSineRms(90.0f, 200.0, 8000);
        double ildBassDb = Math.abs(20.0 * Math.log10(bass[1] / Math.max(1e-9, bass[0])));
        check(ildBassDb < 2.0,
                "200 Hz bass ILD at +90° must stay < 2 dB (got " + ildBassDb + " dB)");
    }

    private static void pinnaFrontBackSpectralCue() {
        // Compare 0° (front) vs 180° (back) at 3.2 kHz concha band and 8.0 kHz Pinna notch
        double[] frontConcha = measureSineRms(0.0f, 3200.0, 8000);
        double[] backConcha = measureSineRms(180.0f, 3200.0, 8000);
        double conchaDiffDb = 20.0 * Math.log10(frontConcha[0] / Math.max(1e-9, backConcha[0]));
        check(conchaDiffDb > 3.5,
                "Front (0°) must be brighter than Back (180°) at 3.2 kHz concha band (got " + conchaDiffDb + " dB)");

        double[] frontNotch = measureSineRms(0.0f, 8000.0, 8000);
        double[] backNotch = measureSineRms(180.0f, 8000.0, 8000);
        double notchDiffDb = 20.0 * Math.log10(backNotch[0] / Math.max(1e-9, frontNotch[0]));
        check(notchDiffDb > 3.5,
                "Front (0°) must exhibit Pinna notch at 8 kHz compared to Back (180°) (got " + notchDiffDb + " dB)");

        // Tuning Pinna notch from 7500 Hz to 9000 Hz must shift attenuation at 7500 Hz vs 9000 Hz
        double[] notch7500At7500 = measureSineRms(0.0f, 7500.0, 7500);
        double[] notch9000At7500 = measureSineRms(0.0f, 7500.0, 9000);
        double shiftDb = 20.0 * Math.log10(notch9000At7500[0] / Math.max(1e-9, notch7500At7500[0]));
        check(shiftDb > 3.0,
                "Pinna notch tuning must shift notch depth at 7.5 kHz (got " + shiftDb + " dB)");
    }

    private static void fdnExternalizationAndDecay() {
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        kernel.setScene(WearEarsBinauralKernel.SCENE_CONCERT);
        kernel.setAzimuthDeg(0.0f);
        kernel.reset();

        int total = WearEarsBinauralKernel.SAMPLE_RATE * 2; // 2.0 seconds
        float[] in = new float[total];
        float[] outL = new float[total];
        float[] outR = new float[total];
        in[0] = 0.9f;

        kernel.processMono(in, outL, outR, total);

        // Early reflections window: 6 ms .. 35 ms (288 .. 1680)
        double erRms = stereoRms(outL, outR, 288, 1680);
        // Late FDN window: 40 ms .. 250 ms (1920 .. 12000)
        double lateRms = stereoRms(outL, outR, 1920, 12000);
        // Tail decay window: 1.5 s .. 2.0 s (72000 .. 96000)
        double tailRms = stereoRms(outL, outR, 72000, 96000);

        check(erRms > 1e-4, "Early reflections energy must be non-zero (erRms=" + erRms + ")");
        check(lateRms > 1e-4, "FDN late tail energy must be non-zero (lateRms=" + lateRms + ")");
        check(tailRms < lateRms * 0.05,
                "FDN tail must decay cleanly without instability (tailRms=" + tailRms + ", lateRms=" + lateRms + ")");
        check(allFiniteAndBounded(outL, outR, total, 0.95f), "Impulse tail must stay finite and <= 0.95");
    }

    private static void continuousTenSecondSweepAndSafety() {
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        kernel.resetAuditionGenerator();
        kernel.fadeIn();

        int totalFrames = WearEarsBinauralKernel.SAMPLE_RATE * 10; // 10 seconds = 480,000 samples
        int block = WearEarsBinauralKernel.BLOCK_SIZE;
        float[] mono = new float[block];
        float[] outL = new float[block];
        float[] outR = new float[block];

        float peak = 0.0f;
        int blocks = totalFrames / block;
        for (int b = 0; b < blocks; b++) {
            float az = (b * 3.5f) % 360.0f;
            kernel.setAzimuthDeg(az);
            if (b % 250 == 0) {
                kernel.setScene((b / 250) % 4);
                kernel.setHeadWidthPct(85 + ((b / 250) % 7) * 5);
                kernel.setPinnaNotchHz(7500 + ((b / 250) % 7) * 250);
            }
            int mode = (b < blocks / 2)
                    ? WearEarsBinauralKernel.AUDITION_CHORD_TEXTURE
                    : WearEarsBinauralKernel.AUDITION_PINK_PULSE;
            kernel.fillAuditionSource(mono, block, mode);
            kernel.processMono(mono, outL, outR, block);

            for (int i = 0; i < block; i++) {
                float l = outL[i];
                float r = outR[i];
                check(Float.isFinite(l) && Float.isFinite(r), "Non-finite output at block " + b + " sample " + i);
                float absL = Math.abs(l);
                float absR = Math.abs(r);
                if (absL > peak) peak = absL;
                if (absR > peak) peak = absR;
            }
        }
        check(peak > 0.05f, "10s audition sweep should produce audible signal (peak=" + peak + ")");
        check(peak <= 0.95f, "10s audition sweep peak must strictly stay <= 0.95 (peak=" + peak + ")");

        // Hard overdrive limiter check (+20 dB input)
        for (int i = 0; i < block; i++) {
            mono[i] = (i % 2 == 0) ? 10.0f : -10.0f;
        }
        kernel.processMono(mono, outL, outR, block);
        check(allFiniteAndBounded(outL, outR, block, 0.95f),
                "Soft-clipper + hard clamp must enforce <= 0.95 under extreme overdrive");

        // Volume safety ceiling (-12 dBFS = 0.25f) check
        kernel.setOutputGainCeiling(0.25f);
        kernel.setFadeImmediate(1.0f);
        kernel.setAzimuthDeg(0.0f);
        kernel.reset();
        for (int i = 0; i < block; i++) {
            mono[i] = (float) Math.sin(2.0 * Math.PI * 1000.0 * i / WearEarsBinauralKernel.SAMPLE_RATE) * 0.8f;
        }
        kernel.processMono(mono, outL, outR, block);
        float cappedPeak = maxAbs(outL, outR, block);
        check(cappedPeak < 0.30f, "Volume safety ceiling 0.25 must keep output well below 0.30 (got " + cappedPeak + ")");

        // Fade-out check (25 ms = 1200 samples -> 6 blocks = 1536 samples must reach fadedOut)
        kernel.fadeOut();
        for (int b = 0; b < 6; b++) {
            kernel.processMono(mono, outL, outR, block);
        }
        check(kernel.isFadedOut(), "25 ms fadeOut should complete within 6 blocks");
        check(Math.abs(outL[block - 1]) < 1e-4f && Math.abs(outR[block - 1]) < 1e-4f,
                "Faded-out output tail should be silent");
    }

    private static void stereoToBinauralCrossfeed() {
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        int n = 4096;
        float[] inL = new float[n];
        float[] inR = new float[n]; // strictly zero right input
        float[] outL = new float[n];
        float[] outR = new float[n];

        for (int i = 0; i < n; i++) {
            double t = i / (double) WearEarsBinauralKernel.SAMPLE_RATE;
            inL[i] = (float) (0.35 * Math.sin(2.0 * Math.PI * 1000.0 * t)
                    + 0.25 * Math.sin(2.0 * Math.PI * 4000.0 * t));
        }

        int[] scenes = {WearEarsBinauralKernel.SCENE_WIDE, WearEarsBinauralKernel.SCENE_CINEMA};
        for (int sc : scenes) {
            kernel.setScene(sc);
            kernel.reset();
            kernel.processStereo(inL, inR, outL, outR, n);

            double rmsL = rms(outL, 1024, n);
            double rmsR = rms(outR, 1024, n);
            check(allFiniteAndBounded(outL, outR, n, 0.95f),
                    "Stereo mode scene " + sc + " must be finite and <= 0.95");
            check(rmsR > 0.02,
                    "Stereo-to-binaural crossfeed must produce non-zero Right ear response for Left-only input (scene="
                            + sc + ", rmsR=" + rmsR + ")");
            check(rmsL > rmsR,
                    "Left-only input must remain louder on Left ear than crossfed Right ear (scene="
                            + sc + ", rmsL=" + rmsL + ", rmsR=" + rmsR + ")");
        }
    }

    private static double[] measureSineRms(float azDeg, double freqHz, int pinnaNotchHz) {
        WearEarsBinauralKernel kernel = new WearEarsBinauralKernel();
        kernel.setScene(WearEarsBinauralKernel.SCENE_STUDIO);
        kernel.setHeadWidthPct(100);
        kernel.setPinnaNotchHz(pinnaNotchHz);
        kernel.setAzimuthDeg(azDeg);
        kernel.reset();

        int settle = 2048;
        int measure = 4096;
        int total = settle + measure;
        float[] in = new float[total];
        float[] outL = new float[total];
        float[] outR = new float[total];
        for (int i = 0; i < total; i++) {
            in[i] = 0.25f * (float) Math.sin(2.0 * Math.PI * freqHz * i / WearEarsBinauralKernel.SAMPLE_RATE);
        }
        kernel.processMono(in, outL, outR, total);
        return new double[]{rms(outL, settle, total), rms(outR, settle, total)};
    }

    private static int argMaxAbs(float[] arr, int from, int to) {
        int bestIdx = from;
        float best = -1.0f;
        for (int i = from; i < to; i++) {
            float v = Math.abs(arr[i]);
            if (v > best) {
                best = v;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static double rms(float[] arr, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += (double) arr[i] * arr[i];
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    private static double stereoRms(float[] l, float[] r, int from, int to) {
        double sum = 0.0;
        for (int i = from; i < to; i++) {
            sum += 0.5 * ((double) l[i] * l[i] + (double) r[i] * r[i]);
        }
        return Math.sqrt(sum / Math.max(1, to - from));
    }

    private static float maxAbs(float[] l, float[] r, int n) {
        float peak = 0.0f;
        for (int i = 0; i < n; i++) {
            float a = Math.abs(l[i]);
            float b = Math.abs(r[i]);
            if (a > peak) peak = a;
            if (b > peak) peak = b;
        }
        return peak;
    }

    private static boolean allFiniteAndBounded(float[] l, float[] r, int n, float limit) {
        for (int i = 0; i < n; i++) {
            if (!Float.isFinite(l[i]) || !Float.isFinite(r[i])) return false;
            if (Math.abs(l[i]) > limit || Math.abs(r[i]) > limit) return false;
        }
        return true;
    }

    public static void main(String[] args) {
        itdPolarityAndAccuracy();
        brownDudaIldShadow();
        pinnaFrontBackSpectralCue();
        fdnExternalizationAndDecay();
        continuousTenSecondSweepAndSafety();
        stereoToBinauralCrossfeed();
        System.out.println("PASS " + checks + " spatial DSP checks");
    }
}
