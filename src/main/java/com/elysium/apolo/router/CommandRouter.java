package com.elysium.apolo.router;

import com.elysium.apolo.commands.Command;
import com.elysium.apolo.commands.CommandType;
import com.elysium.apolo.config.ApoloConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.Normalizer;
import java.util.List;
import java.util.Map;

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

    public CommandRouter(ApoloConfig config) {
        this.config = config;
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

        // 1. Try system commands (ordered by specificity)
        Command systemCommand = matchSystemCommand(normalized, rawText);
        if (systemCommand != null) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("Router: system match in {}ms -> {}", elapsed, systemCommand.type());
            return systemCommand;
        }

        // 2. Try "open X" command
        Command openCommand = matchOpenCommand(normalized, rawText);
        if (openCommand != null) {
            long elapsed = System.currentTimeMillis() - start;
            log.info("Router: open match in {}ms -> app='{}'", elapsed, openCommand.argument());
            return openCommand;
        }

        long elapsed = System.currentTimeMillis() - start;
        log.info("Router: command not recognized in {}ms: '{}'", elapsed, rawText);
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

    /**
     * Attempts to match system commands.
     * Variations are tested in descending length order (most specific first).
     * Requires exact match or variation as prefix followed by space.
     */
    private Command matchSystemCommand(String normalized, String rawText) {
        Map<String, List<String>> aliases = config.getCommandAliases();

        CommandType[] systemTypes = {
                CommandType.MINIMIZE,
                CommandType.LOCK,
                CommandType.TURN_OFF_SCREEN,
                CommandType.CLOSE_APP,
                CommandType.CODE_MODE,
                CommandType.RESTART_APP
        };

        String[] canonicalKeys = {"minimize", "lock", "turn off the screen", "close", "code", "restart"};

        for (int i = 0; i < systemTypes.length; i++) {
            List<String> variations = aliases.get(canonicalKeys[i]);
            if (variations == null) continue;

            // Test each variation (already ordered by specificity in config)
            for (String variation : variations) {
                String normalizedVariation = normalize(variation);

                // Exact match
                if (normalized.equals(normalizedVariation)) {
                    log.debug("Exact match: '{}' == '{}'", normalized, normalizedVariation);
                    // For restart command, extract app name even on exact match
                    if (systemTypes[i] == CommandType.RESTART_APP) {
                        // Remove the base "restart" word to get the app name
                        String appName = normalized.replaceFirst("^restart\\s+", "").trim();
                        appName = removeArticles(appName);
                        log.debug("Restart exact match: app='{}'", appName);
                        return Command.system(systemTypes[i], appName, rawText);
                    }
                    return Command.system(systemTypes[i], rawText);
                }

                // Match as prefix followed by space (not arbitrary substring)
                if (normalized.startsWith(normalizedVariation + " ")) {
                    log.debug("Prefix match: '{}' starts with '{} '", normalized, normalizedVariation);
                    // For restart command, extract the app name
                    if (systemTypes[i] == CommandType.RESTART_APP) {
                        String appName = normalized.substring(normalizedVariation.length()).trim();
                        appName = removeArticles(appName);
                        log.debug("Restart match: app='{}'", appName);
                        return Command.system(systemTypes[i], appName, rawText);
                    }
                    return Command.system(systemTypes[i], rawText);
                }
            }
        }

        return null;
    }

    /**
     * Attempts to match "open X" command.
     */
    private Command matchOpenCommand(String normalized, String rawText) {
        List<String> openAliases = config.getCommandAliases().get("open");
        if (openAliases == null) return null;

        for (String alias : openAliases) {
            String normalizedAlias = normalize(alias);

            // Match: "open spotify" or "open the spotify"
            if (normalized.startsWith(normalizedAlias + " ") || normalized.equals(normalizedAlias)) {
                // Extract app name
                String appName;
                if (normalized.equals(normalizedAlias)) {
                    appName = "";
                } else {
                    appName = normalized.substring(normalizedAlias.length()).trim();
                }

                // Remove articles
                appName = removeArticles(appName);

                if (!appName.isEmpty()) {
                    // Verify app exists in mappings
                    String resolvedApp = resolveAppName(appName);
                    if (resolvedApp != null) {
                        log.debug("Open match: '{}' -> resolved app='{}'", normalized, resolvedApp);
                        return Command.openApp(resolvedApp, rawText);
                    } else {
                        // App not found in mappings but command recognized
                        log.info("'open' command recognized but app not mapped: '{}'", appName);
                        return Command.openApp(appName, rawText);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Removes common articles from app name.
     */
    private String removeArticles(String text) {
        return text
                .replaceAll("^the ", "")
                .replaceAll("^a ", "")
                .replaceAll("^an ", "")
                .trim();
    }

    /**
     * Resolves app name against configured mappings.
     * Returns the mapping key if found, null otherwise.
     *
     * Strategy:
     * 1. Exact match
     * 2. Containment match (key contains name or vice versa)
     * 3. Phonetic simple match (no spaces or accents)
     */
    private String resolveAppName(String appName) {
        Map<String, String> mappings = config.getAppMappings();

        // 1. Exact match
        if (mappings.containsKey(appName)) {
            return appName;
        }

        // 2. Containment match
        for (String key : mappings.keySet()) {
            if (key.contains(appName) || appName.contains(key)) {
                log.debug("Partial app match: '{}' contains/in '{}'", appName, key);
                return key;
            }
        }

        // 3. Normalized match (no spaces)
        String normalizedInput = appName.replaceAll("\\s+", "");
        for (String key : mappings.keySet()) {
            String normalizedKey = key.replaceAll("\\s+", "");
            if (normalizedKey.equals(normalizedInput) ||
                    normalizedKey.contains(normalizedInput) ||
                    normalizedInput.contains(normalizedKey)) {
                log.debug("Normalized app match: '{}' -> '{}'", appName, key);
                return key;
            }
        }

        return null;
    }
}
