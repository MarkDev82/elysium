package com.elysium.apolo.audio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.*;

/**
 * Audio capture from microphone.
 * Provides a continuous stream of 16-bit mono PCM audio at 16kHz,
 * which is the format Vosk requires.
 */
public final class AudioCapture {

    private static final Logger log = LoggerFactory.getLogger(AudioCapture.class);

    public static final float SAMPLE_RATE = 16000f;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int CHANNELS = 1;

    private final AudioFormat format;
    private TargetDataLine line;

    public AudioCapture() {
        this.format = new AudioFormat(
                SAMPLE_RATE,
                SAMPLE_SIZE_BITS,
                CHANNELS,
                true,   // signed
                false   // little-endian
        );
    }

    /**
     * Opens the audio line and starts capture.
     */
    public void start() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);

        if (!AudioSystem.isLineSupported(info)) {
            throw new LineUnavailableException("Audio line not supported: " + format);
        }

        line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();

        log.info("Audio capture started: {} Hz, {} bits, {} channel(s)",
                SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS);
    }

    /**
     * Reads audio data from the microphone.
     * Blocks until data is available.
     *
     * @param buffer buffer to write data to
     * @return number of bytes read
     */
    public int read(byte[] buffer) {
        if (line == null || !line.isOpen()) {
            return -1;
        }
        return line.read(buffer, 0, buffer.length);
    }

    /**
     * Returns the audio format.
     */
    public AudioFormat getFormat() {
        return format;
    }

    /**
     * Stops and closes the audio line.
     */
    public void stop() {
        if (line != null) {
            line.stop();
            line.close();
            log.info("Audio capture stopped");
        }
    }

    /**
     * Lists available microphones.
     */
    public static void listAvailableMicrophones() {
        Mixer.Info[] mixers = AudioSystem.getMixerInfo();
        log.info("Available audio devices:");
        for (Mixer.Info mixerInfo : mixers) {
            Mixer mixer = AudioSystem.getMixer(mixerInfo);
            Line.Info[] targetLines = mixer.getTargetLineInfo();
            if (targetLines.length > 0) {
                log.info("  [IN] {} - {}", mixerInfo.getName(), mixerInfo.getDescription());
            }
        }
    }
}
