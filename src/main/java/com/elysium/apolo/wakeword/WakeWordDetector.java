package com.elysium.apolo.wakeword;

import com.elysium.apolo.audio.AudioCapture;
import com.elysium.apolo.config.ApoloConfig;
import com.elysium.apolo.feedback.ApoloState;
import com.elysium.apolo.feedback.FeedbackService;
import com.elysium.apolo.speech.SpeechService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.Recognizer;

import java.io.IOException;

/**
 * Wake word detector for "Apolo".
 * Listens continuously and notifies when the wake word is detected.
 *
 * Hardening applied:
 * - 2s cooldown after activation to avoid chained re-triggers
 * - Recognizer reset after detection to clear residual audio buffer
 * - Explicit detection criterion: "apolo" must appear as isolated token
 *   or at start of phrase, not as substring of another word
 * - False positive and detection counters for metrics
 */
public final class WakeWordDetector {

    private static final Logger log = LoggerFactory.getLogger(WakeWordDetector.class);

    private final SpeechService speechService;
    private final AudioCapture audioCapture;
    private final ApoloConfig config;
    private final FeedbackService feedback;
    private volatile boolean running = false;
    private volatile boolean processing = false; // Blocks detections during command processing
    private WakeWordListener listener;

    // Metrics
    private int detectionCount = 0;
    private int falsePositiveCount = 0;
    private long sessionStartTime = 0;

    public WakeWordDetector(
            SpeechService speechService,
            AudioCapture audioCapture,
            ApoloConfig config,
            FeedbackService feedback
    ) {
        this.speechService = speechService;
        this.audioCapture = audioCapture;
        this.config = config;
        this.feedback = feedback;
    }

    /**
     * Starts continuous wake word listening on a dedicated thread.
     */
    public void start(WakeWordListener listener) {
        this.listener = listener;
        this.running = true;
        this.sessionStartTime = System.currentTimeMillis();

        Thread wakeThread = new Thread(this::listenLoop, "apolo-wakeword");
        wakeThread.setDaemon(true);
        wakeThread.start();

        log.info("Wake word detector started. Waiting for '{}'. Cooldown: {}ms",
                config.getWakeWord(), config.getWakeWordCooldownMillis());
    }

    /**
     * Main continuous listening loop.
     * Creates a new recognizer after each detection to clear the buffer.
     */
    private void listenLoop() {
        while (running) {
            try (Recognizer recognizer = new Recognizer(
                    speechService.getModel(),
                    AudioCapture.SAMPLE_RATE
            )) {
                recognizer.setMaxAlternatives(0);
                recognizer.setWords(false);

                byte[] buffer = new byte[4096];

                while (running && !processing) {
                    feedback.setState(ApoloState.IDLE);

                    boolean detected = speechService.detectWakeWord(audioCapture, buffer, recognizer);

                    if (detected) {
                        detectionCount++;
                        feedback.setState(ApoloState.WAKE_DETECTED);
                        log.info("=== WAKE WORD DETECTED (#{}) ===", detectionCount);

                        // Mark as processing to block new detections
                        processing = true;

                        // Notify listener (this executes the full command cycle)
                        if (listener != null) {
                            listener.onWakeWordDetected();
                        }

                        // Cooldown: wait before listening again
                        int cooldown = config.getWakeWordCooldownMillis();
                        log.debug("Post-activation cooldown: {}ms", cooldown);
                        Thread.sleep(cooldown);

                        // Exit inner loop to create a new recognizer (clears buffer)
                        break;
                    }
                }
            } catch (IOException e) {
                log.error("Error in wake word detector: {}", e.getMessage(), e);
                // Retry after error
                try { Thread.sleep(1000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.info("Wake word detector interrupted");
                return;
            } finally {
                processing = false;
            }
        }
    }

    /**
     * Stops continuous listening.
     */
    public void stop() {
        running = false;
        long sessionDuration = System.currentTimeMillis() - sessionStartTime;
        log.info("Wake word detector stopped. Session metrics:");
        log.info("  Duration: {}s", sessionDuration / 1000);
        log.info("  Total detections: {}", detectionCount);
        log.info("  Estimated false positives: {}", falsePositiveCount);
    }

    public boolean isRunning() {
        return running;
    }

    public int getDetectionCount() {
        return detectionCount;
    }

    public int getFalsePositiveCount() {
        return falsePositiveCount;
    }

    /**
     * Allows external system to mark a detection as false positive.
     */
    public void reportFalsePositive() {
        falsePositiveCount++;
        log.debug("False positive reported. Total: {}", falsePositiveCount);
    }

    /**
     * Listener for wake word events.
     */
    @FunctionalInterface
    public interface WakeWordListener {
        void onWakeWordDetected();
    }
}
