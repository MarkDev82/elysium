package com.elysium.apolo.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Feedback service: voice responses and state notification.
 * Uses PowerShell + Windows TTS (System.Speech.Synthesis) for voice output.
 * Does not block the main thread.
 *
 * Improvement: selects a Spanish voice when available.
 */
public final class FeedbackService {

    private static final Logger log = LoggerFactory.getLogger(FeedbackService.class);
    private final ExecutorService ttsExecutor;
    private volatile ApoloState currentState = ApoloState.IDLE;
    private StateListener stateListener;
    private volatile boolean ttsAvailable = true;

    public FeedbackService() {
        this.ttsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "apolo-tts");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Speaks a short text asynchronously.
     */
    public void speak(String text) {
        if (!ttsAvailable) {
            log.debug("TTS not available, log only: {}", text);
            return;
        }
        log.info("TTS: {}", text);
        ttsExecutor.submit(() -> speakSync(text));
    }

    /**
     * Speaks synchronously using Windows Speech Synthesis via PowerShell.
     * Uses a timeout to avoid infinite blocks.
     * Selects a Spanish voice when available.
     */
    private void speakSync(String text) {
        long start = System.currentTimeMillis();
        try {
            String escaped = text.replace("'", "''");
            String psCommand = String.format(
                    "Add-Type -AssemblyName System.Speech; " +
                    "$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer; " +
                    "$voices = $synth.GetInstalledVoices() | Where-Object { $_.Enabled }; " +
                    "$esVoice = $voices | Where-Object { $_.VoiceInfo.Culture.Name -like 'es-*' } | Select-Object -First 1; " +
                    "if ($esVoice) { $synth.SelectVoice($esVoice.VoiceInfo.Name) }; " +
                    "$synth.Rate = 1; " +
                    "$synth.Speak('%s')",
                    escaped
            );

            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", psCommand
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            boolean finished = process.waitFor(8, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("TTS timeout after 8s for: '{}'", text);
                process.destroyForcibly();
            }

            long elapsed = System.currentTimeMillis() - start;
            log.debug("TTS completed in {}ms: '{}'", elapsed, text);

        } catch (IOException e) {
            log.warn("Error in TTS (PowerShell not available?): {}", e.getMessage());
            ttsAvailable = false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("TTS interrupted");
        }
    }

    /**
     * Updates the assistant state and notifies the listener.
     */
    public void setState(ApoloState state) {
        this.currentState = state;
        log.debug("State: {}", state.getLabel());
        if (stateListener != null) {
            stateListener.onStateChange(state);
        }
    }

    public ApoloState getCurrentState() {
        return currentState;
    }

    public void setStateListener(StateListener listener) {
        this.stateListener = listener;
    }

    public void shutdown() {
        ttsExecutor.shutdownNow();
    }

    /**
     * Listener for state changes.
     */
    @FunctionalInterface
    public interface StateListener {
        void onStateChange(ApoloState newState);
    }
}
