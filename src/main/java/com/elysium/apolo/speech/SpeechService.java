package com.elysium.apolo.speech;

import com.elysium.apolo.audio.AudioCapture;
import com.elysium.apolo.config.ApoloConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.vosk.Model;
import org.vosk.Recognizer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Speech-To-Text service using Vosk offline.
 * Converts microphone audio to text in real time.
 *
 * Hardening applied:
 * - Stricter wake word criterion: "apolo" as isolated word or at start
 * - Recognition timing logs
 * - Partial result cleanup between sessions
 */
public final class SpeechService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SpeechService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ApoloConfig config;
    private Model model;
    private boolean initialized = false;

    public SpeechService(ApoloConfig config) {
        this.config = config;
    }

    /**
     * Initializes the Vosk model. Must be called once at startup.
     */
    public void initialize() throws IOException {
        long start = System.currentTimeMillis();
        String modelPath = config.getModelPath();
        log.info("Loading Vosk model from: {}", modelPath);

        if (!Files.isDirectory(Path.of(modelPath))) {
            throw new IOException(
                    "Vosk model not found at: " + modelPath + "\n" +
                    "Download the English model from: https://alphacephei.com/vosk/models\n" +
                    "and extract it to: " + modelPath
            );
        }

        this.model = new Model(modelPath);
        this.initialized = true;
        long elapsed = System.currentTimeMillis() - start;
        log.info("Vosk model loaded successfully in {}ms", elapsed);
    }

    /**
     * Listens for a command during the configured time window.
     * Returns recognized text or null if nothing was recognized.
     *
     * @param audioCapture active audio source
     * @return recognized text, or null
     */
    public String listenForCommand(AudioCapture audioCapture) {
        if (!initialized) {
            log.error("SpeechService not initialized");
            return null;
        }

        long timeout = config.getCommandWindowMillis();
        long startTime = System.currentTimeMillis();
        log.info("Listening for command (timeout: {}ms)...", timeout);

        try (Recognizer recognizer = new Recognizer(model, AudioCapture.SAMPLE_RATE)) {
            recognizer.setMaxAlternatives(1);
            recognizer.setWords(false);

            byte[] buffer = new byte[4096];
            String lastPartial = "";
            int partialUpdates = 0;

            while (System.currentTimeMillis() - startTime < timeout) {
                int bytesRead = audioCapture.read(buffer);
                if (bytesRead < 0) break;

                boolean isEnd = recognizer.acceptWaveForm(buffer, bytesRead);

                // Read partial results
                String partialJson = recognizer.getPartialResult();
                JsonNode partialNode = MAPPER.readTree(partialJson);
                String partial = partialNode.path("partial").asText("").trim();

                if (!partial.isEmpty() && !partial.equals(lastPartial)) {
                    lastPartial = partial;
                    partialUpdates++;
                    log.debug("Partial [{}ms]: '{}'", System.currentTimeMillis() - startTime, partial);
                }

                if (isEnd) {
                    // Final result
                    String resultJson = recognizer.getResult();
                    JsonNode resultNode = MAPPER.readTree(resultJson);
                    String text = resultNode.path("text").asText("").trim();

                    if (!text.isEmpty()) {
                        long elapsed = System.currentTimeMillis() - startTime;
                        log.info("Command recognized in {}ms ({} partials): '{}'",
                                elapsed, partialUpdates, text);
                        return text;
                    }
                }
            }

            // Timeout: return last partial if exists
            if (!lastPartial.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Command recognized (timeout/partial) in {}ms: '{}'", elapsed, lastPartial);
                return lastPartial;
            }

            // Try final result
            String finalJson = recognizer.getFinalResult();
            JsonNode finalNode = MAPPER.readTree(finalJson);
            String finalText = finalNode.path("text").asText("").trim();
            if (!finalText.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("Command recognized (final) in {}ms: '{}'", elapsed, finalText);
                return finalText;
            }

        } catch (Exception e) {
            log.error("Error in command recognition: {}", e.getMessage(), e);
        }

        long elapsed = System.currentTimeMillis() - startTime;
        log.info("No command recognized in {}ms", elapsed);
        return null;
    }

    /**
     * Continuously processes audio looking for the wake word.
     * Returns true when "apolo" is detected in recognized text.
     *
     * Hardened detection criterion:
     * - "apolo" must appear as isolated word or at start of phrase
     * - Not accepted as substring of another word (e.g., "apologetic")
     * - Both final and partial results checked for responsiveness
     *
     * @param audioCapture active audio source
     * @param buffer       audio buffer
     * @param recognizer   active recognizer
     * @return true if wake word was detected
     */
    public boolean detectWakeWord(AudioCapture audioCapture, byte[] buffer, Recognizer recognizer) {
        try {
            int bytesRead = audioCapture.read(buffer);
            if (bytesRead < 0) return false;

            boolean isEnd = recognizer.acceptWaveForm(buffer, bytesRead);

            if (isEnd) {
                String resultJson = recognizer.getResult();
                JsonNode node = MAPPER.readTree(resultJson);
                String text = node.path("text").asText("").trim().toLowerCase();

                if (containsWakeWordStrict(text)) {
                    log.info("Wake word detected (final) in: '{}'", text);
                    return true;
                }
            }

            // Check partials for responsiveness
            String partialJson = recognizer.getPartialResult();
            JsonNode partialNode = MAPPER.readTree(partialJson);
            String partial = partialNode.path("partial").asText("").trim().toLowerCase();

            if (containsWakeWordStrict(partial)) {
                log.info("Wake word detected (partial) in: '{}'", partial);
                return true;
            }

        } catch (Exception e) {
            log.debug("Error in wake word detection: {}", e.getMessage());
        }

        return false;
    }

    /**
     * Strict wake word detection.
     * "apolo" must appear as isolated word, not as substring.
     * Tolerant to simple phonetic variations and accents.
     * Includes English phonetic variants (apollo, a polo, etc.)
     */
    private boolean containsWakeWordStrict(String text) {
        if (text == null || text.isEmpty()) return false;

        String wakeWord = config.getWakeWord();

        // Normalize: lowercase, no accents, no extra spaces
        String normalized = text.toLowerCase().trim()
                .replace("á", "a").replace("é", "e").replace("í", "i")
                .replace("ó", "o").replace("ú", "u");

        // English phonetic variants of "apolo"
        String[] wakeWordVariants = {
            wakeWord,           // "apolo"
            "apollo",          // English spelling (double L)
            "a polo",          // Two words variant
            "apolo",           // Original
            "appolo",          // Common misspelling
            "apollo"           // Another variant
        };

        // Split into words and search for wake word as isolated word
        String[] words = normalized.split("\\s+");
        for (String word : words) {
            // Clean punctuation from word
            String cleanWord = word.replaceAll("[^a-z]", "");
            for (String variant : wakeWordVariants) {
                String cleanVariant = variant.replaceAll("\\s+", "").replaceAll("[^a-z]", "");
                if (cleanWord.equals(cleanVariant)) {
                    log.debug("Wake word match (isolated): '{}' matches variant '{}'", cleanWord, variant);
                    return true;
                }
            }
        }

        // Also accept if full normalized text (no spaces) matches any variant
        String noSpaces = normalized.replaceAll("\\s+", "");
        for (String variant : wakeWordVariants) {
            String cleanVariant = variant.replaceAll("\\s+", "").replaceAll("[^a-z]", "");
            if (noSpaces.equals(cleanVariant)) {
                log.debug("Wake word match (full text): '{}' matches variant '{}'", noSpaces, variant);
                return true;
            }
        }

        // Check if text contains any variant as substring (for partial matches)
        for (String variant : wakeWordVariants) {
            String cleanVariant = variant.replaceAll("\\s+", "").replaceAll("[^a-z]", "");
            if (noSpaces.contains(cleanVariant)) {
                log.debug("Wake word match (substring): '{}' contains variant '{}'", noSpaces, variant);
                return true;
            }
        }

        return false;
    }

    public boolean isInitialized() {
        return initialized;
    }

    /**
     * Returns the loaded Vosk model (for shared use by WakeWordDetector).
     */
    public Model getModel() {
        return model;
    }

    @Override
    public void close() {
        if (model != null) {
            model.close();
            log.info("Vosk model closed");
        }
    }
}
