package com.hdz.wearcontrol;

/**
 * WearEars Layer 2 proprietary real-time Binaural HRTF DSP engine (pure Java 11, zero dependencies).
 *
 * <p>Signal path (48 kHz, 256-sample internal blocks, zero per-block heap allocations):
 * <ol>
 *   <li>Woodworth-Schlosberg ITD fractional delay line (64-sample ring buffer + cubic Hermite).</li>
 *   <li>Brown &amp; Duda (1998) spherical-head ILD shadow filter (1-pole/1-zero bilinear per ear).</li>
 *   <li>Parametric Pinna spectral cues (3 RBJ 2nd-order peaking biquads per ear blended by cos(az)).</li>
 *   <li>Out-of-head externalization: 4 binaural early reflections (7, 13, 19, 29 ms) +
 *       4-channel Hadamard Feedback Delay Network (1103, 1487, 1993, 2503 samples) with HF damping.</li>
 *   <li>Safety stage: -3 dB nominal headroom, configurable volume-safety ceiling,
 *       25 ms click-free fade envelope, smooth tanh soft-clipper above 0.85, hard clamp [-0.95, 0.95].</li>
 * </ol>
 */
public final class WearEarsBinauralKernel {

    public static final int SAMPLE_RATE = 48000;
    public static final int BLOCK_SIZE = 256;

    public static final int SCENE_WIDE = 0;
    public static final int SCENE_CINEMA = 1;
    public static final int SCENE_CONCERT = 2;
    public static final int SCENE_STUDIO = 3;

    public static final int HEAD_WIDTH_MIN_PCT = 85;
    public static final int HEAD_WIDTH_MAX_PCT = 115;
    public static final int HEAD_WIDTH_DEFAULT_PCT = 100;

    public static final int PINNA_NOTCH_MIN_HZ = 7500;
    public static final int PINNA_NOTCH_MAX_HZ = 9000;
    public static final int PINNA_NOTCH_DEFAULT_HZ = 8000;

    public static final int AUDITION_CHORD_TEXTURE = 0;
    public static final int AUDITION_PINK_PULSE = 1;

    private static final float SPEED_OF_SOUND = 343.0f;
    private static final float BASE_HEAD_RADIUS_M = 0.0875f;
    private static final float BROWN_DUDA_ALPHA_MIN = 0.1f;
    private static final float BROWN_DUDA_THETA_MIN_DEG = 150.0f;
    private static final float BASE_ITD_DELAY_SAMPLES = 2.0f;

    private static final float NOMINAL_HEADROOM_GAIN = 0.70794578f; // -3.0 dB
    private static final float SOFT_KNEE = 0.85f;
    private static final float HARD_CLAMP = 0.95f;
    // Allow overdrive while keeping delay interpolation and feedback arithmetic bounded.
    private static final float MAX_INPUT_AMPLITUDE = 32.0f;
    private static final float FADE_STEP = 1.0f / (0.025f * SAMPLE_RATE); // 25 ms @ 48 kHz = 1200 samples
    private static final float DENORMAL_FLOOR = 1.0e-20f;

    // Early reflection taps at 7, 13, 19, 29 ms @ 48 kHz
    private static final int ER_BUF_SIZE = 2048;
    private static final int ER_BUF_MASK = ER_BUF_SIZE - 1;
    private static final int[] ER_TAPS = {336, 624, 912, 1392};
    private static final int[] ER_ITD_OFFSET_L = {0, 12, 0, 10};
    private static final int[] ER_ITD_OFFSET_R = {12, 0, 10, 0};
    private static final float[] ER_GAIN_L = {0.34f, 0.16f, 0.22f, 0.10f};
    private static final float[] ER_GAIN_R = {0.18f, 0.28f, 0.12f, 0.17f};

    // 4-channel Hadamard FDN mutually prime lengths
    private static final int[] FDN_LENGTHS = {1103, 1487, 1993, 2503};

    // Scene acoustic presets: [wetMix, fbGain, dampCoeff, erScale]
    private static final float[] SCENE_WET = {0.12f, 0.17f, 0.22f, 0.08f};
    private static final float[] SCENE_FB = {0.58f, 0.68f, 0.78f, 0.46f};
    private static final float[] SCENE_DAMP = {0.36f, 0.28f, 0.22f, 0.45f};
    private static final float[] SCENE_ER = {0.26f, 0.34f, 0.40f, 0.18f};

    // User-facing target parameters
    private float targetAzimuthDeg = 0.0f;
    private int scene = SCENE_WIDE;
    private int headWidthPct = HEAD_WIDTH_DEFAULT_PCT;
    private int pinnaNotchHz = PINNA_NOTCH_DEFAULT_HZ;
    private float targetCeilingGain = 1.0f;

    // Smoothed runtime states
    private float curWetMix = SCENE_WET[SCENE_WIDE];
    private float curFbGain = SCENE_FB[SCENE_WIDE];
    private float curDamp = SCENE_DAMP[SCENE_WIDE];
    private float curErScale = SCENE_ER[SCENE_WIDE];
    private float curCeilingGain = 1.0f;
    private float fadeEnv = 1.0f;
    private float fadeTarget = 1.0f;

    // Pre-allocated binaural voices (zero GC during streaming)
    private final BinauralVoice monoVoice = new BinauralVoice();
    private final BinauralVoice stereoLeftVoice = new BinauralVoice();
    private final BinauralVoice stereoRightVoice = new BinauralVoice();

    // Early reflections state
    private final float[] erRing = new float[ER_BUF_SIZE];
    private int erWriteIdx = 0;
    private float erLpL = 0f;
    private float erLpR = 0f;

    // 4-channel Hadamard FDN state
    private final float[][] fdnLines = {
            new float[FDN_LENGTHS[0]],
            new float[FDN_LENGTHS[1]],
            new float[FDN_LENGTHS[2]],
            new float[FDN_LENGTHS[3]]
    };
    private final int[] fdnIdx = new int[4];
    private final float[] fdnLp = new float[4];

    // Procedural audition signal generator state (zero asset bloat, zero GC)
    private int rngState = 0x5A3C9E17;
    private float pinkB0, pinkB1, pinkB2, pinkB3, pinkB4, pinkB5, pinkB6;
    private float pinkLp;
    private final double[] oscPhase = new double[4];
    private double pulsePhase = 0.0;
    private double shimmerPhase = 0.0;

    public WearEarsBinauralKernel() {
        reset();
    }

    /**
     * Clears all delay lines, filter states, and snaps smoothed parameters to current targets.
     */
    public void reset() {
        clearArray(erRing);
        erWriteIdx = 0;
        erLpL = 0f;
        erLpR = 0f;
        for (int i = 0; i < 4; i++) {
            clearArray(fdnLines[i]);
            fdnIdx[i] = 0;
            fdnLp[i] = 0f;
        }
        curWetMix = SCENE_WET[scene];
        curFbGain = SCENE_FB[scene];
        curDamp = SCENE_DAMP[scene];
        curErScale = SCENE_ER[scene];
        curCeilingGain = targetCeilingGain;
        fadeEnv = fadeTarget;

        float radius = headRadiusMeters();
        monoVoice.reset(targetAzimuthDeg, radius, pinnaNotchHz);
        float spkDeg = speakerAngleDeg(scene);
        stereoLeftVoice.reset(-spkDeg, radius, pinnaNotchHz);
        stereoRightVoice.reset(spkDeg, radius, pinnaNotchHz);
    }

    /**
     * Resets procedural audition oscillators and pink-noise PRNG to deterministic start state.
     */
    public void resetAuditionGenerator() {
        rngState = 0x5A3C9E17;
        pinkB0 = pinkB1 = pinkB2 = pinkB3 = pinkB4 = pinkB5 = pinkB6 = 0f;
        pinkLp = 0f;
        for (int i = 0; i < oscPhase.length; i++) {
            oscPhase[i] = 0.0;
        }
        pulsePhase = 0.0;
        shimmerPhase = 0.0;
    }

    /**
     * Sets target azimuth in degrees (0 = front, +90 = right, -90/270 = left, 180 = back).
     */
    public void setAzimuthDeg(float deg) {
        if (!Float.isFinite(deg)) {
            return;
        }
        this.targetAzimuthDeg = wrap180(deg);
    }

    public float getAzimuthDeg() {
        return targetAzimuthDeg;
    }

    public void setScene(int scene) {
        this.scene = clampInt(scene, SCENE_WIDE, SCENE_STUDIO);
    }

    public int getScene() {
        return scene;
    }

    public void setHeadWidthPct(int pct) {
        this.headWidthPct = clampInt(pct, HEAD_WIDTH_MIN_PCT, HEAD_WIDTH_MAX_PCT);
    }

    public int getHeadWidthPct() {
        return headWidthPct;
    }

    public void setPinnaNotchHz(int hz) {
        this.pinnaNotchHz = clampInt(hz, PINNA_NOTCH_MIN_HZ, PINNA_NOTCH_MAX_HZ);
    }

    public int getPinnaNotchHz() {
        return pinnaNotchHz;
    }

    /**
     * Sets output gain ceiling (1.0f normal, 0.25f = -12 dBFS under VolumeGuard >70% protection).
     */
    public void setOutputGainCeiling(float linearGain) {
        if (!Float.isFinite(linearGain)) {
            return;
        }
        this.targetCeilingGain = clampFloat(linearGain, 0.0f, 1.0f);
    }

    public float getOutputGainCeiling() {
        return targetCeilingGain;
    }

    /** Initiates a click-free 25 ms fade-in from silence. */
    public void fadeIn() {
        fadeEnv = 0.0f;
        fadeTarget = 1.0f;
    }

    /** Initiates a click-free 25 ms fade-out to silence. */
    public void fadeOut() {
        fadeTarget = 0.0f;
    }

    /** Immediately sets fade envelope (for deterministic unit tests). */
    public void setFadeImmediate(float gain) {
        float g = clampFloat(gain, 0.0f, 1.0f);
        fadeEnv = g;
        fadeTarget = g;
    }

    public boolean isFadedOut() {
        return fadeTarget <= 0.0f && fadeEnv <= 1e-5f;
    }

    /**
     * Processes a mono point source at the configured 360° azimuth into binaural stereo output.
     */
    public void processMono(float[] in, float[] outL, float[] outR, int frames) {
        if (in == null || outL == null || outR == null || frames <= 0) {
            return;
        }
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(BLOCK_SIZE, frames - offset);
            processMonoBlock(in, outL, outR, offset, n);
            offset += n;
        }
    }

    /**
     * Processes stereo input through two binaural virtual loudspeakers (±30°, or ±45° in Cinema).
     */
    public void processStereo(float[] inL, float[] inR, float[] outL, float[] outR, int frames) {
        if (inL == null || inR == null || outL == null || outR == null || frames <= 0) {
            return;
        }
        int offset = 0;
        while (offset < frames) {
            int n = Math.min(BLOCK_SIZE, frames - offset);
            processStereoBlock(inL, inR, outL, outR, offset, n);
            offset += n;
        }
    }

    /**
     * Generates a pleasant, zero-asset procedural audition signal containing both rhythmic
     * harmonic transients (clear ITD/ILD cues) and gentle filtered pink-noise texture (clear
     * Pinna front/back &amp; notch cues).
     */
    public void fillAuditionSource(float[] mono, int frames, int mode) {
        if (mono == null || frames <= 0) {
            return;
        }
        // Warm Cmaj9 chord frequencies: C4, G4, B4, D5
        final double inc0 = 2.0 * Math.PI * 261.6256 / SAMPLE_RATE;
        final double inc1 = 2.0 * Math.PI * 391.9954 / SAMPLE_RATE;
        final double inc2 = 2.0 * Math.PI * 493.8833 / SAMPLE_RATE;
        final double inc3 = 2.0 * Math.PI * 587.3295 / SAMPLE_RATE;
        final double pulseInc = 1.65 / SAMPLE_RATE; // ~1.65 Hz rhythmic pulse
        final double shimmerInc = 2.0 * Math.PI * 0.35 / SAMPLE_RATE;

        for (int i = 0; i < frames; i++) {
            // Smooth percussive-like pulse envelope (fast 12 ms attack, gentle warm decay)
            pulsePhase += pulseInc;
            if (pulsePhase >= 1.0) {
                pulsePhase -= 1.0;
            }
            float env;
            if (pulsePhase < 0.025) {
                env = (float) (pulsePhase / 0.025);
            } else {
                double decayT = (pulsePhase - 0.025) / 0.975;
                env = (float) Math.exp(-3.4 * decayT);
            }
            // Subtle breathing floor so continuous crown sweep is always audible
            float chordEnv = 0.22f + 0.78f * env;

            // Paul Kellet 7-pole pink noise approximation (broadband 100 Hz .. 16 kHz)
            float white = nextWhiteNoise();
            pinkB0 = 0.99886f * pinkB0 + white * 0.0555179f;
            pinkB1 = 0.99332f * pinkB1 + white * 0.0750759f;
            pinkB2 = 0.96900f * pinkB2 + white * 0.1538520f;
            pinkB3 = 0.86650f * pinkB3 + white * 0.3104856f;
            pinkB4 = 0.55000f * pinkB4 + white * 0.5329522f;
            pinkB5 = -0.7616f * pinkB5 - white * 0.0168980f;
            float pink = (pinkB0 + pinkB1 + pinkB2 + pinkB3 + pinkB4 + pinkB5 + pinkB6 + white * 0.5362f) * 0.055f;
            pinkB6 = white * 0.115926f;

            // Gentle 1-pole high-pass / brightness air blend so 3-10 kHz Pinna cues shine cleanly
            pinkLp += 0.22f * (pink - pinkLp);
            float airyPink = 0.65f * pink + 0.55f * (pink - pinkLp);

            float sample;
            if (mode == AUDITION_PINK_PULSE) {
                sample = airyPink * (0.28f + 0.72f * env) * 1.15f;
            } else {
                oscPhase[0] = wrapTwoPi(oscPhase[0] + inc0);
                oscPhase[1] = wrapTwoPi(oscPhase[1] + inc1);
                oscPhase[2] = wrapTwoPi(oscPhase[2] + inc2);
                oscPhase[3] = wrapTwoPi(oscPhase[3] + inc3);
                shimmerPhase = wrapTwoPi(shimmerPhase + shimmerInc);

                float shimmer = 0.75f + 0.25f * (float) Math.sin(shimmerPhase);
                float chord = (float) (
                        0.38 * Math.sin(oscPhase[0])
                                + 0.28 * Math.sin(oscPhase[1])
                                + 0.19 * Math.sin(oscPhase[2]) * shimmer
                                + 0.15 * Math.sin(oscPhase[3])
                                + 0.06 * Math.sin(oscPhase[0] * 2.0)
                );
                // Blend warm harmonic pulse + soft broadband texture for Pinna cues
                float textureEnv = 0.32f + 0.45f * env;
                sample = chord * chordEnv * 0.42f + airyPink * textureEnv * 0.46f;
            }
            mono[i] = clampFloat(sample, -0.85f, 0.85f);
        }
    }

    private void processMonoBlock(float[] in, float[] outL, float[] outR, int offset, int n) {
        float invN = 1.0f / n;
        float radius = headRadiusMeters();

        monoVoice.prepareBlock(targetAzimuthDeg, radius, pinnaNotchHz, invN);

        float wetStart = curWetMix;
        float wetStep = (SCENE_WET[scene] - wetStart) * invN;
        float fbStart = curFbGain;
        float fbStep = (SCENE_FB[scene] - fbStart) * invN;
        float dampStart = curDamp;
        float dampStep = (SCENE_DAMP[scene] - dampStart) * invN;
        float erStart = curErScale;
        float erStep = (SCENE_ER[scene] - erStart) * invN;
        float ceilStart = curCeilingGain;
        float ceilStep = (targetCeilingGain - ceilStart) * invN;

        for (int i = 0; i < n; i++) {
            float x = sanitize(in[offset + i]);

            monoVoice.processSample(x);
            float dryL = monoVoice.lastL;
            float dryR = monoVoice.lastR;

            float wet = wetStart + wetStep * i;
            float fb = fbStart + fbStep * i;
            float damp = dampStart + dampStep * i;
            float erScale = erStart + erStep * i;
            float ceil = ceilStart + ceilStep * i;

            // Externalization: early reflections + 4-ch Hadamard FDN
            stepExternalization(x, fb, damp, erScale);

            float mixL = dryL + wet * extOutL;
            float mixR = dryR + wet * extOutR;

            float env = stepFade();
            float gain = NOMINAL_HEADROOM_GAIN * ceil * env;
            outL[offset + i] = softLimit(mixL * gain);
            outR[offset + i] = softLimit(mixR * gain);
        }

        monoVoice.finishBlock();
        curWetMix = SCENE_WET[scene];
        curFbGain = SCENE_FB[scene];
        curDamp = SCENE_DAMP[scene];
        curErScale = SCENE_ER[scene];
        curCeilingGain = targetCeilingGain;
    }

    private void processStereoBlock(float[] inL, float[] inR, float[] outL, float[] outR, int offset, int n) {
        float invN = 1.0f / n;
        float radius = headRadiusMeters();
        float spkDeg = speakerAngleDeg(scene);

        stereoLeftVoice.prepareBlock(-spkDeg, radius, pinnaNotchHz, invN);
        stereoRightVoice.prepareBlock(spkDeg, radius, pinnaNotchHz, invN);

        float wetStart = curWetMix;
        float wetStep = (SCENE_WET[scene] - wetStart) * invN;
        float fbStart = curFbGain;
        float fbStep = (SCENE_FB[scene] - fbStart) * invN;
        float dampStart = curDamp;
        float dampStep = (SCENE_DAMP[scene] - dampStart) * invN;
        float erStart = curErScale;
        float erStep = (SCENE_ER[scene] - erStart) * invN;
        float ceilStart = curCeilingGain;
        float ceilStep = (targetCeilingGain - ceilStart) * invN;

        for (int i = 0; i < n; i++) {
            float xl = sanitize(inL[offset + i]);
            float xr = sanitize(inR[offset + i]);

            stereoLeftVoice.processSample(xl);
            stereoRightVoice.processSample(xr);

            // -3 dB stereo-sum calibration to prevent correlated center buildup
            float dryL = (stereoLeftVoice.lastL + stereoRightVoice.lastL) * 0.70710678f;
            float dryR = (stereoLeftVoice.lastR + stereoRightVoice.lastR) * 0.70710678f;

            float wet = wetStart + wetStep * i;
            float fb = fbStart + fbStep * i;
            float damp = dampStart + dampStep * i;
            float erScale = erStart + erStep * i;
            float ceil = ceilStart + ceilStep * i;

            float monoSum = 0.5f * (xl + xr);
            stepExternalization(monoSum, fb, damp, erScale);

            float mixL = dryL + wet * extOutL;
            float mixR = dryR + wet * extOutR;

            float env = stepFade();
            float gain = NOMINAL_HEADROOM_GAIN * ceil * env;
            outL[offset + i] = softLimit(mixL * gain);
            outR[offset + i] = softLimit(mixR * gain);
        }

        stereoLeftVoice.finishBlock();
        stereoRightVoice.finishBlock();
        curWetMix = SCENE_WET[scene];
        curFbGain = SCENE_FB[scene];
        curDamp = SCENE_DAMP[scene];
        curErScale = SCENE_ER[scene];
        curCeilingGain = targetCeilingGain;
    }

    private float extOutL;
    private float extOutR;

    private void stepExternalization(float monoIn, float fbGain, float dampCoeff, float erScale) {
        // 1. Write input to early reflection ring buffer
        erWriteIdx = (erWriteIdx + 1) & ER_BUF_MASK;
        erRing[erWriteIdx] = monoIn;

        float erRawL = 0f;
        float erRawR = 0f;
        for (int t = 0; t < 4; t++) {
            int tap = ER_TAPS[t];
            float sL = erRing[(erWriteIdx - tap - ER_ITD_OFFSET_L[t]) & ER_BUF_MASK];
            float sR = erRing[(erWriteIdx - tap - ER_ITD_OFFSET_R[t]) & ER_BUF_MASK];
            erRawL += sL * ER_GAIN_L[t];
            erRawR += sR * ER_GAIN_R[t];
        }
        // Gentle HF wall absorption on early reflections (~6.5 kHz)
        erLpL = flushDenormal(erLpL + 0.52f * (erRawL - erLpL));
        erLpR = flushDenormal(erLpR + 0.52f * (erRawR - erLpR));

        // 2. 4-channel Hadamard Feedback Delay Network
        float d0 = fdnLines[0][fdnIdx[0]];
        float d1 = fdnLines[1][fdnIdx[1]];
        float d2 = fdnLines[2][fdnIdx[2]];
        float d3 = fdnLines[3][fdnIdx[3]];

        // 1-pole HF damping per FDN channel (strictly contractive)
        float l0 = flushDenormal(fdnLp[0] + dampCoeff * (d0 - fdnLp[0]));
        float l1 = flushDenormal(fdnLp[1] + dampCoeff * (d1 - fdnLp[1]));
        float l2 = flushDenormal(fdnLp[2] + dampCoeff * (d2 - fdnLp[2]));
        float l3 = flushDenormal(fdnLp[3] + dampCoeff * (d3 - fdnLp[3]));
        fdnLp[0] = l0;
        fdnLp[1] = l1;
        fdnLp[2] = l2;
        fdnLp[3] = l3;

        // Orthonormal 4x4 Hadamard feedback mixing
        float h0 = 0.5f * (l0 + l1 + l2 + l3);
        float h1 = 0.5f * (l0 - l1 + l2 - l3);
        float h2 = 0.5f * (l0 + l1 - l2 - l3);
        float h3 = 0.5f * (l0 - l1 - l2 + l3);

        // Excite all 4 Hadamard eigenmodes equally ([+1, +1, +1, -1])
        float feed = monoIn * 0.35f;
        fdnLines[0][fdnIdx[0]] = flushDenormal(h0 * fbGain + feed);
        fdnLines[1][fdnIdx[1]] = flushDenormal(h1 * fbGain + feed);
        fdnLines[2][fdnIdx[2]] = flushDenormal(h2 * fbGain + feed);
        fdnLines[3][fdnIdx[3]] = flushDenormal(h3 * fbGain - feed);

        for (int c = 0; c < 4; c++) {
            int next = fdnIdx[c] + 1;
            fdnIdx[c] = (next >= FDN_LENGTHS[c]) ? 0 : next;
        }

        // Decorrelated binaural FDN readout + early reflections
        float fdnL = 0.5f * (l0 - l1);
        float fdnR = 0.5f * (l2 - l3);
        extOutL = erLpL * erScale + fdnL;
        extOutR = erLpR * erScale + fdnR;
    }

    private float stepFade() {
        if (fadeEnv < fadeTarget) {
            fadeEnv += FADE_STEP;
            if (fadeEnv > fadeTarget) {
                fadeEnv = fadeTarget;
            }
        } else if (fadeEnv > fadeTarget) {
            fadeEnv -= FADE_STEP;
            if (fadeEnv < fadeTarget) {
                fadeEnv = fadeTarget;
            }
        }
        return fadeEnv;
    }

    private float headRadiusMeters() {
        return BASE_HEAD_RADIUS_M * (headWidthPct / 100.0f);
    }

    private static float speakerAngleDeg(int scene) {
        return scene == SCENE_CINEMA ? 45.0f : 30.0f;
    }

    private float nextWhiteNoise() {
        int x = rngState;
        x ^= (x << 13);
        x ^= (x >>> 17);
        x ^= (x << 5);
        rngState = x;
        return (x >> 8) * (1.0f / 8388608.0f); // [-1, 1)
    }

    /**
     * Smooth C1 tanh soft-clipper above 0.85 with hard clamp to [-0.95, +0.95].
     */
    static float softLimit(float x) {
        if (!Float.isFinite(x)) {
            return 0.0f;
        }
        float abs = Math.abs(x);
        if (abs <= SOFT_KNEE) {
            return x;
        }
        float range = HARD_CLAMP - SOFT_KNEE; // 0.10f
        float limited = SOFT_KNEE + range * (float) Math.tanh((abs - SOFT_KNEE) / range);
        if (limited > HARD_CLAMP) {
            limited = HARD_CLAMP;
        }
        return x < 0.0f ? -limited : limited;
    }

    static float wrap180(float deg) {
        float d = deg % 360.0f;
        if (d >= 180.0f) {
            d -= 360.0f;
        } else if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }

    private static double wrapTwoPi(double p) {
        final double twoPi = 2.0 * Math.PI;
        return p >= twoPi ? p - twoPi : p;
    }

    private static float sanitize(float x) {
        return Float.isFinite(x) ? clampFloat(x, -MAX_INPUT_AMPLITUDE, MAX_INPUT_AMPLITUDE) : 0.0f;
    }

    private static float flushDenormal(float v) {
        return Math.abs(v) < DENORMAL_FLOOR ? 0.0f : v;
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static float clampFloat(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void clearArray(float[] arr) {
        for (int i = 0; i < arr.length; i++) {
            arr[i] = 0.0f;
        }
    }

    /**
     * Single binauralized source voice: 64-sample cubic-Hermite ITD line + L/R Brown-Duda ILD
     * head-shadow filters + L/R 3-band parametric Pinna spectral cue biquads.
     */
    private static final class BinauralVoice {
        private static final int DELAY_SIZE = 64;
        private static final int DELAY_MASK = DELAY_SIZE - 1;

        private final float[] delayBuf = new float[DELAY_SIZE];
        private int writeIdx = 0;

        private float curAzDeg = 0.0f;
        private float targetAzDeg = 0.0f;
        private float curDelayL = BASE_ITD_DELAY_SAMPLES;
        private float curDelayR = BASE_ITD_DELAY_SAMPLES;
        private float targetDelayL = BASE_ITD_DELAY_SAMPLES;
        private float targetDelayR = BASE_ITD_DELAY_SAMPLES;
        private float delayStepL = 0.0f;
        private float delayStepR = 0.0f;

        private final HeadShadowFilter ildL = new HeadShadowFilter();
        private final HeadShadowFilter ildR = new HeadShadowFilter();

        private final RampedBiquad pinnaConchaL = new RampedBiquad();
        private final RampedBiquad pinnaConchaR = new RampedBiquad();
        private final RampedBiquad pinnaFrontNotchL = new RampedBiquad();
        private final RampedBiquad pinnaFrontNotchR = new RampedBiquad();
        private final RampedBiquad pinnaBackNotchL = new RampedBiquad();
        private final RampedBiquad pinnaBackNotchR = new RampedBiquad();

        float lastL;
        float lastR;

        void reset(float azDeg, float headRadiusM, int pinnaNotchHz) {
            clearArray(delayBuf);
            writeIdx = 0;
            curAzDeg = wrap180(azDeg);
            targetAzDeg = curAzDeg;

            computeDelays(curAzDeg, headRadiusM);
            curDelayL = targetDelayL;
            curDelayR = targetDelayR;
            delayStepL = 0.0f;
            delayStepR = 0.0f;

            float omega0 = SPEED_OF_SOUND / headRadiusM;
            ildL.reset(earAngleDeg(curAzDeg, true), omega0);
            ildR.reset(earAngleDeg(curAzDeg, false), omega0);

            updatePinna(curAzDeg, pinnaNotchHz, 0.0f, true);
            lastL = 0.0f;
            lastR = 0.0f;
        }

        void prepareBlock(float newAzDeg, float headRadiusM, int pinnaNotchHz, float invN) {
            // Shortest circular path around 360° avoids ±180° wrap jumps
            float delta = wrap180(newAzDeg - curAzDeg);
            // Limit per-block step to smooth fast drags/rotations while remaining responsive
            targetAzDeg = wrap180(curAzDeg + delta);

            computeDelays(targetAzDeg, headRadiusM);
            delayStepL = (targetDelayL - curDelayL) * invN;
            delayStepR = (targetDelayR - curDelayR) * invN;

            float omega0 = SPEED_OF_SOUND / headRadiusM;
            ildL.prepareBlock(earAngleDeg(targetAzDeg, true), omega0, invN);
            ildR.prepareBlock(earAngleDeg(targetAzDeg, false), omega0, invN);

            updatePinna(targetAzDeg, pinnaNotchHz, invN, false);
        }

        void finishBlock() {
            curAzDeg = targetAzDeg;
            curDelayL = targetDelayL;
            curDelayR = targetDelayR;
            ildL.finishBlock();
            ildR.finishBlock();
            pinnaConchaL.finishBlock();
            pinnaConchaR.finishBlock();
            pinnaFrontNotchL.finishBlock();
            pinnaFrontNotchR.finishBlock();
            pinnaBackNotchL.finishBlock();
            pinnaBackNotchR.finishBlock();
        }

        void processSample(float x) {
            writeIdx = (writeIdx + 1) & DELAY_MASK;
            delayBuf[writeIdx] = x;

            curDelayL += delayStepL;
            curDelayR += delayStepR;

            float dl = readHermite(curDelayL);
            float dr = readHermite(curDelayR);

            dl = ildL.process(dl);
            dr = ildR.process(dr);

            dl = pinnaConchaL.process(dl);
            dl = pinnaFrontNotchL.process(dl);
            dl = pinnaBackNotchL.process(dl);

            dr = pinnaConchaR.process(dr);
            dr = pinnaFrontNotchR.process(dr);
            dr = pinnaBackNotchR.process(dr);

            lastL = dl;
            lastR = dr;
        }

        private void computeDelays(float azDeg, float headRadiusM) {
            double theta = Math.toRadians(azDeg);
            double absTheta = Math.abs(theta);
            double thetaFold = absTheta <= (Math.PI * 0.5) ? absTheta : (Math.PI - absTheta);
            double tauSec = (headRadiusM / SPEED_OF_SOUND) * (thetaFold + Math.sin(thetaFold));
            float tauSamples = (float) (tauSec * SAMPLE_RATE);

            // Positive azimuth = right side -> Right ear earlier, Left ear delayed
            if (azDeg > 0.0f) {
                targetDelayR = BASE_ITD_DELAY_SAMPLES;
                targetDelayL = BASE_ITD_DELAY_SAMPLES + tauSamples;
            } else if (azDeg < 0.0f) {
                targetDelayL = BASE_ITD_DELAY_SAMPLES;
                targetDelayR = BASE_ITD_DELAY_SAMPLES + tauSamples;
            } else {
                targetDelayL = BASE_ITD_DELAY_SAMPLES;
                targetDelayR = BASE_ITD_DELAY_SAMPLES;
            }
        }

        private float readHermite(float delaySamples) {
            float d = clampFloat(delaySamples, 1.0f, DELAY_SIZE - 4.0f);
            int id = (int) d;
            float f = d - id;

            float ym1 = delayBuf[(writeIdx - id + 1) & DELAY_MASK];
            float y0 = delayBuf[(writeIdx - id) & DELAY_MASK];
            float y1 = delayBuf[(writeIdx - id - 1) & DELAY_MASK];
            float y2 = delayBuf[(writeIdx - id - 2) & DELAY_MASK];

            float c1 = 0.5f * (y1 - ym1);
            float c2 = ym1 - 2.5f * y0 + 2.0f * y1 - 0.5f * y2;
            float c3 = 0.5f * (y2 - ym1) + 1.5f * (y0 - y1);
            return ((c3 * f + c2) * f + c1) * f + y0;
        }

        private void updatePinna(float azDeg, int pinnaNotchHz, float invN, boolean immediate) {
            float cosAz = (float) Math.cos(Math.toRadians(azDeg));
            float frontWeight = 0.5f * (1.0f + cosAz); // 1.0 front (0°), 0.5 side (±90°), 0.0 back (180°)

            // Band 1: Concha peak (+2.5 dB @ 3.2 kHz front) <-> Back presence dip (-3.5 dB @ 3.0 kHz back)
            float conchaHz = 3000.0f + 200.0f * frontWeight;
            float conchaDb = -3.5f + 6.0f * frontWeight;
            float conchaQ = 1.0f + 0.2f * frontWeight;

            // Band 2: Primary user-tunable front Pinna notch (-7.0 dB @ 7.5-9.0 kHz front -> 0 dB back)
            float frontNotchDb = -7.0f * frontWeight;

            // Band 3: Back shifted Pinna notch (-5.5 dB @ ~6.2 kHz back -> 0 dB front)
            float backNotchHz = 6200.0f * (pinnaNotchHz / 8000.0f);
            float backNotchDb = -5.5f * (1.0f - frontWeight);

            if (immediate) {
                pinnaConchaL.resetPeaking(conchaHz, conchaQ, conchaDb);
                pinnaConchaR.resetPeaking(conchaHz, conchaQ, conchaDb);
                pinnaFrontNotchL.resetPeaking(pinnaNotchHz, 4.0f, frontNotchDb);
                pinnaFrontNotchR.resetPeaking(pinnaNotchHz, 4.0f, frontNotchDb);
                pinnaBackNotchL.resetPeaking(backNotchHz, 3.5f, backNotchDb);
                pinnaBackNotchR.resetPeaking(backNotchHz, 3.5f, backNotchDb);
            } else {
                pinnaConchaL.preparePeaking(conchaHz, conchaQ, conchaDb, invN);
                pinnaConchaR.preparePeaking(conchaHz, conchaQ, conchaDb, invN);
                pinnaFrontNotchL.preparePeaking(pinnaNotchHz, 4.0f, frontNotchDb, invN);
                pinnaFrontNotchR.preparePeaking(pinnaNotchHz, 4.0f, frontNotchDb, invN);
                pinnaBackNotchL.preparePeaking(backNotchHz, 3.5f, backNotchDb, invN);
                pinnaBackNotchR.preparePeaking(backNotchHz, 3.5f, backNotchDb, invN);
            }
        }

        /**
         * Incidence angle in [0°, 180°] measured from the target ear's ipsilateral axis
         * (0° = directly facing this ear, 90° = front/back, 180° = opposite side of head).
         */
        private static float earAngleDeg(float azDeg, boolean leftEar) {
            float earAxis = leftEar ? -90.0f : 90.0f;
            return Math.abs(wrap180(azDeg - earAxis));
        }
    }

    /**
     * Brown &amp; Duda (1998) 1-pole / 1-zero spherical-head shadow filter (bilinear transform).
     */
    private static final class HeadShadowFilter {
        private float b0, b1, a1;
        private float tb0, tb1, ta1;
        private float db0, db1, da1;
        private float x1, y1;

        void reset(float earAngleDeg, float omega0) {
            computeTarget(earAngleDeg, omega0);
            b0 = tb0;
            b1 = tb1;
            a1 = ta1;
            db0 = db1 = da1 = 0.0f;
            x1 = y1 = 0.0f;
        }

        void prepareBlock(float earAngleDeg, float omega0, float invN) {
            computeTarget(earAngleDeg, omega0);
            db0 = (tb0 - b0) * invN;
            db1 = (tb1 - b1) * invN;
            da1 = (ta1 - a1) * invN;
        }

        void finishBlock() {
            b0 = tb0;
            b1 = tb1;
            a1 = ta1;
            db0 = db1 = da1 = 0.0f;
        }

        float process(float x) {
            b0 += db0;
            b1 += db1;
            a1 += da1;
            float y = b0 * x + b1 * x1 - a1 * y1;
            y = flushDenormal(y);
            x1 = x;
            y1 = y;
            return y;
        }

        private void computeTarget(float earAngleDeg, float omega0) {
            double alpha = (1.0 + BROWN_DUDA_ALPHA_MIN * 0.5)
                    + (1.0 - BROWN_DUDA_ALPHA_MIN * 0.5)
                    * Math.cos((earAngleDeg / BROWN_DUDA_THETA_MIN_DEG) * Math.PI);
            double beta = omega0 / (double) SAMPLE_RATE;
            double invDenom = 1.0 / (1.0 + beta);
            tb0 = (float) ((alpha + beta) * invDenom);
            tb1 = (float) ((beta - alpha) * invDenom);
            ta1 = (float) ((beta - 1.0) * invDenom);
        }
    }

    /**
     * Direct Form II Transposed RBJ Peaking EQ biquad with per-sample coefficient ramping.
     */
    private static final class RampedBiquad {
        private float b0 = 1.0f, b1, b2, a1, a2;
        private float tb0 = 1.0f, tb1, tb2, ta1, ta2;
        private float db0, db1, db2, da1, da2;
        private float z1, z2;

        void resetPeaking(float freqHz, float q, float gainDb) {
            computeTarget(freqHz, q, gainDb);
            b0 = tb0;
            b1 = tb1;
            b2 = tb2;
            a1 = ta1;
            a2 = ta2;
            db0 = db1 = db2 = da1 = da2 = 0.0f;
            z1 = z2 = 0.0f;
        }

        void preparePeaking(float freqHz, float q, float gainDb, float invN) {
            computeTarget(freqHz, q, gainDb);
            db0 = (tb0 - b0) * invN;
            db1 = (tb1 - b1) * invN;
            db2 = (tb2 - b2) * invN;
            da1 = (ta1 - a1) * invN;
            da2 = (ta2 - a2) * invN;
        }

        void finishBlock() {
            b0 = tb0;
            b1 = tb1;
            b2 = tb2;
            a1 = ta1;
            a2 = ta2;
            db0 = db1 = db2 = da1 = da2 = 0.0f;
        }

        float process(float x) {
            b0 += db0;
            b1 += db1;
            b2 += db2;
            a1 += da1;
            a2 += da2;
            float y = b0 * x + z1;
            z1 = flushDenormal(b1 * x - a1 * y + z2);
            z2 = flushDenormal(b2 * x - a2 * y);
            return y;
        }

        private void computeTarget(float freqHz, float q, float gainDb) {
            if (Math.abs(gainDb) < 0.01f) {
                tb0 = 1.0f;
                tb1 = tb2 = ta1 = ta2 = 0.0f;
                return;
            }
            double A = Math.pow(10.0, gainDb / 40.0);
            double w0 = 2.0 * Math.PI * freqHz / SAMPLE_RATE;
            double cosW0 = Math.cos(w0);
            double alpha = Math.sin(w0) / (2.0 * q);
            double invA0 = 1.0 / (1.0 + alpha / A);

            tb0 = (float) ((1.0 + alpha * A) * invA0);
            tb1 = (float) ((-2.0 * cosW0) * invA0);
            tb2 = (float) ((1.0 - alpha * A) * invA0);
            ta1 = (float) ((-2.0 * cosW0) * invA0);
            ta2 = (float) ((1.0 - alpha / A) * invA0);
        }
    }
}
