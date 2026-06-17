package com.elysium.apolo.router;

import com.elysium.apolo.commands.Command;
import com.elysium.apolo.commands.CommandType;
import com.elysium.apolo.config.ApoloConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.elysium.apolo.integrations.mimo.MiMoClient;
import com.elysium.apolo.integrations.mimo.MiMoResponse;

import java.text.Normalizer;

/**
 * Command router: normalizes recognized text and maps it to a Command.
 * Uses deterministic rules, no AI.
 *
 * Applied fixes:
 * - Matching ordered by descending length (most specific first)
 * - Exact match preferred over startsWith
 * - Robust normalization (accents, punctuation, articles)
 * - Detailed logging of matching process
 */
public final class CommandRouter {

    private static final Logger log = LoggerFactory.getLogger(CommandRouter.class);

    private final ApoloConfig config;
    private final MiMoClient mimoClient;

    public CommandRouter(ApoloConfig config) {
        this.config = config;
        this.mimoClient = new MiMoClient(config);
    }

    /**
     * Normalizes and routes recognized text to a Command.
     */
    public Command route(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return Command.unknown("");
        }

        long start = System.currentTimeMillis();
        String normalized = normalize(rawText);
        log.info("Router: raw='{}' -> normalized='{}'", rawText, normalized);

        if (normalized.isEmpty()) {
            log.info("Router: empty text after normalization");
            return Command.unknown(rawText);
        }

        // Use MiMo for everything
        MiMoResponse response = mimoClient.processText(normalized);
        
        if (response.action() != null) {
            try {
                CommandType type = CommandType.valueOf(response.action());
                if (type == CommandType.SPEAK) {
                    return Command.speak(response.text(), rawText);
                } else if (response.target() != null && !response.target().isEmpty()) {
                    if (type == CommandType.OPEN_APP) {
                        return Command.openApp(response.target(), rawText);
                    }
                    return Command.system(type, response.target(), rawText);
                } else {
                    return Command.system(type, rawText);
                }
            } catch (IllegalArgumentException e) {
                log.error("MiMo devolvió un tipo de acción desconocido: {}", response.action());
                return Command.speak("Lo siento, la nube me devolvió una orden que no comprendo.", rawText);
            }
        } else if (response.text() != null && !response.text().isEmpty()) {
            return Command.speak(response.text(), rawText);
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Router: processed by MiMo in {}ms", elapsed);
        return Command.unknown(rawText);
    }

    /**
     * Text normalization:
     * - lowercase
     * - remove accents
     * - remove punctuation
     * - collapse spaces
     * - remove wake word if present at start
     */
    String normalize(String text) {
        String result = text.toLowerCase().trim();

        // Remove accents using Normalizer
        result = Normalizer.normalize(result, Normalizer.Form.NFD);
        result = result.replaceAll("[\\p{InCombiningDiacriticalMarks}]", "");

        // Remove basic punctuation
        result = result.replaceAll("[¿?¡!.,;:']", "");

        // Collapse spaces
        result = result.replaceAll("\\s+", " ").trim();

        // Remove wake word at start if present
        String wakeWord = config.getWakeWord();
        if (result.startsWith(wakeWord + " ")) {
            result = result.substring(wakeWord.length()).trim();
        } else if (result.equals(wakeWord)) {
            result = "";
        }

        return result;
    }

    // deterministic matching methods removed as they are now handled by MiMo
}
