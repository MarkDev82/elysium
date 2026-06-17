package com.elysium.apolo.feedback;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.LineEvent;
import com.elysium.apolo.config.ApoloConfig;

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
    private final ApoloConfig config;
    private volatile ApoloState currentState = ApoloState.IDLE;
    private StateListener stateListener;
    private volatile boolean ttsAvailable = true;

    public FeedbackService(ApoloConfig config) {
        this.config = config;
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
     * Speaks synchronously using Xiaomi MiMo TTS API (Dean voice).
     */
    private void speakSync(String text) {
        long start = System.currentTimeMillis();
        try {
            String apiKey = config.getMimoApiKey();
            // Xiaomi MiMo TTS uses the chat/completions endpoint
            String url = "https://token-plan-ams.xiaomimimo.com/v1/chat/completions";
            
            // Escape double quotes and newlines for JSON
            String escapedText = text.replace("\"", "\\\"").replace("\n", " ");
            
            String json = "{"
                + "\"model\": \"mimo-v2.5-tts\","
                + "\"messages\": [{\"role\": \"assistant\", \"content\": \"" + escapedText + "\"}],"
                + "\"audio\": {\"format\": \"wav\", \"voice\": \"Dean\"}"
                + "}";
                
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .header("api-key", apiKey)
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
                    
            HttpClient client = HttpClient.newHttpClient();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                log.warn("TTS API Error: {} - {}", response.statusCode(), response.body());
                return;
            }
            
            Pattern pattern = Pattern.compile("\"data\":\\s*\"([^\"]+)\"");
            Matcher matcher = pattern.matcher(response.body());
            if (matcher.find()) {
                String base64 = matcher.group(1);
                byte[] decoded = Base64.getDecoder().decode(base64);
                
                try (ByteArrayInputStream bais = new ByteArrayInputStream(decoded);
                     AudioInputStream ais = AudioSystem.getAudioInputStream(bais)) {
                    Clip clip = AudioSystem.getClip();
                    clip.open(ais);
                    
                    // Start and wait for it to finish
                    clip.start();
                    Thread.sleep(clip.getMicrosecondLength() / 1000);
                    clip.close();
                }
                
                long elapsed = System.currentTimeMillis() - start;
                log.debug("TTS completed in {}ms: '{}'", elapsed, text);
            } else {
                log.warn("No audio data found in TTS response.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.debug("TTS interrupted");
        } catch (Exception e) {
            log.warn("Error in MiMo TTS API: {}", e.getMessage());
            ttsAvailable = false;
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
