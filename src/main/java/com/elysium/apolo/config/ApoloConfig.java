package com.elysium.apolo.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Central configuration for Apolo.
 * Manages application mappings, command aliases, and system settings.
 *
 * App resolution (hierarchical):
 * 1. Explicit path in apolo-config.properties
 * 2. Search in known Windows locations (Start Menu, AppData, Program Files)
 * 3. Direct executable name (trust PATH)
 * 4. Fallback to cmd /c start
 * 5. Clear error if nothing works
 */
public final class ApoloConfig {

    private static final Logger log = LoggerFactory.getLogger(ApoloConfig.class);

    private static final String MODELS_DIR_PROPERTY = "apolo.models.dir";
    private static final String DEFAULT_MODELS_DIR = "models";

    private final Map<String, String> appMappings;
    private final Map<String, List<String>> commandAliases;
    private final String modelPath;
    private final int commandWindowMillis;
    private final int wakeWordCooldownMillis;
    
    private String mimoApiKey;
    private String mimoApiUrl;
    private String mimoTtsUrl;

    public ApoloConfig() {
        Properties extProps = loadExternalProperties();
        this.mimoApiKey = extProps.getProperty("mimo.api.key", "");
        this.mimoApiUrl = extProps.getProperty("mimo.api.url", "https://token-plan-ams.xiaomimimo.com/v1/chat/completions");
        this.mimoTtsUrl = extProps.getProperty("mimo.tts.url", "https://token-plan-ams.xiaomimimo.com/v1/audio/speech");

        this.appMappings = loadAppMappings(extProps);
        this.commandAliases = loadCommandAliases();
        this.modelPath = resolveModelPath();
        this.commandWindowMillis = 5000;
        this.wakeWordCooldownMillis = 2000;
    }

    /**
     * Maps application names to paths or execution commands.
     * Paths are resolved hierarchically.
     */
    private Map<String, String> loadAppMappings(Properties extProps) {
        Map<String, String> map = new LinkedHashMap<>();

        // Default paths: try to resolve in order
        map.put("spotify", resolveAppPath("spotify",
                "Spotify.exe",
                List.of(
                        env("APPDATA") + "\\Spotify\\Spotify.exe",
                        env("LOCALAPPDATA") + "\\Microsoft\\WindowsApps\\Spotify.exe",
                        env("LOCALAPPDATA") + "\\Spotify\\Spotify.exe"
                )
        ));

        map.put("discord", resolveAppPath("discord",
                "Discord.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Discord\\Update.exe --processStart Discord.exe",
                        env("LOCALAPPDATA") + "\\Discord\\Discord.exe"
                )
        ));

        map.put("whatsapp", resolveAppPath("whatsapp",
                "WhatsApp.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Programs\\WhatsApp\\WhatsApp.exe",
                        env("LOCALAPPDATA") + "\\WhatsApp\\WhatsApp.exe",
                        env("ProgramFiles") + "\\WhatsApp\\WhatsApp.exe"
                )
        ));
        // Phonetic aliases for WhatsApp (small model confusion)
        map.put("whats up", resolveAppPath("whatsapp",
                "WhatsApp.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Programs\\WhatsApp\\WhatsApp.exe",
                        env("LOCALAPPDATA") + "\\WhatsApp\\WhatsApp.exe",
                        env("ProgramFiles") + "\\WhatsApp\\WhatsApp.exe"
                )
        ));
        map.put("what's up", resolveAppPath("whatsapp",
                "WhatsApp.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Programs\\WhatsApp\\WhatsApp.exe",
                        env("LOCALAPPDATA") + "\\WhatsApp\\WhatsApp.exe",
                        env("ProgramFiles") + "\\WhatsApp\\WhatsApp.exe"
                )
        ));

        map.put("opencode", resolveAppPath("opencode",
                "opencode",
                List.of()
        ));

        // Perplexity: formal browser app case (opens via URL)
        map.put("perplexity", "https://www.perplexity.ai");
        // Phonetic aliases for small model recognition:
        map.put("perplexity", "https://www.perplexity.ai");
        map.put("perplexy", "https://www.perplexity.ai");
        map.put("perplexi", "https://www.perplexity.ai");

        map.put("explorer", "explorer.exe");
        map.put("file explorer", "explorer.exe");
        map.put("notepad", "notepad.exe");
        map.put("calculator", "calc.exe");
        map.put("browser", resolveAppPath("browser",
                "msedge.exe",
                List.of(
                        env("ProgramFiles(x86)") + "\\Microsoft\\Edge\\Application\\msedge.exe",
                        env("ProgramFiles") + "\\Microsoft\\Edge\\Application\\msedge.exe"
                )
        ));
        map.put("chrome", resolveAppPath("chrome",
                "chrome.exe",
                List.of(
                        env("ProgramFiles") + "\\Google\\Chrome\\Application\\chrome.exe",
                        env("ProgramFiles(x86)") + "\\Google\\Chrome\\Application\\chrome.exe",
                        env("LOCALAPPDATA") + "\\Google\\Chrome\\Application\\chrome.exe"
                )
        ));
        map.put("terminal", resolveAppPath("terminal",
                "wt.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Microsoft\\WindowsApps\\wt.exe"
                )
        ));

        // Antigravity IDE for code mode
        map.put("antigravity", resolveAppPath("antigravity",
                "Antigravity IDE.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Programs\\Antigravity IDE\\Antigravity IDE.exe",
                        env("LOCALAPPDATA") + "\\Programs\\antigravity\\Antigravity.exe"
                )
        ));

        // Antigravity Chat for code mode (if it exists as separate app)
        map.put("antigravity chat", resolveAppPath("antigravity chat",
                "Antigravity Chat.exe",
                List.of(
                        env("LOCALAPPDATA") + "\\Programs\\Antigravity IDE\\Antigravity Chat.exe",
                        env("LOCALAPPDATA") + "\\Programs\\antigravity\\Antigravity Chat.exe"
                )
        ));

        // Code mode workspace folder
        map.put("code workspace", env("USERPROFILE") + "\\Desktop\\DEV");

        // Load overrides from external file (they have priority)
        loadExternalMappings(map, extProps);

        log.info("App mappings loaded: {} entries", map.size());
        for (var entry : map.entrySet()) {
            log.debug("  {} -> {}", entry.getKey(), entry.getValue());
        }

        return Collections.unmodifiableMap(map);
    }

    /**
     * Resolves an app path by searching known locations.
     * Returns the first existing path, or the fallback.
     */
    private String resolveAppPath(String name, String exeName, List<String> candidatePaths) {
        // 1. Search in candidate paths
        for (String candidate : candidatePaths) {
            if (candidate == null || candidate.isBlank()) continue;
            // For paths with arguments (like Discord Update.exe), verify only the exe part
            String pathOnly = candidate.split(" ")[0];
            if (Files.exists(Path.of(pathOnly))) {
                log.debug("App '{}' resolved to: {}", name, candidate);
                return candidate;
            }
        }

        // 2. Search in Start Menu (.lnk shortcuts)
        String startMenuPath = findInStartMenu(exeName);
        if (startMenuPath != null) {
            log.debug("App '{}' found in Start Menu: {}", name, startMenuPath);
            return startMenuPath;
        }

        // 3. Search in AppData\Roaming\Microsoft\Windows\Start Menu
        String startMenuPath2 = findInStartMenuLegacy(exeName);
        if (startMenuPath2 != null) {
            log.debug("App '{}' found in Start Menu (legacy): {}", name, startMenuPath2);
            return startMenuPath2;
        }

        // 4. Fallback: executable name (trust PATH)
        log.debug("App '{}' using fallback: {}", name, exeName);
        return exeName;
    }

    /**
     * Searches for a .lnk in the Windows Start Menu.
     */
    private String findInStartMenu(String exeName) {
        try {
            // Search in Start Menu folders
            List<Path> startMenuDirs = List.of(
                    Path.of(env("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs"),
                    Path.of(env("ProgramData"), "Microsoft", "Windows", "Start Menu", "Programs")
            );

            for (Path dir : startMenuDirs) {
                if (!Files.isDirectory(dir)) continue;
                Optional<Path> found = Files.walk(dir, 3)
                        .filter(p -> p.toString().toLowerCase().endsWith(".lnk"))
                        .filter(p -> p.getFileName().toString().toLowerCase()
                                .contains(exeName.replace(".exe", "").toLowerCase()))
                        .findFirst();
                if (found.isPresent()) {
                    return found.get().toString();
                }
            }
        } catch (IOException e) {
            log.debug("Error searching Start Menu: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Alternative search in legacy Start Menu.
     */
    private String findInStartMenuLegacy(String exeName) {
        try {
            Path appData = Path.of(env("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs");
            if (!Files.isDirectory(appData)) return null;

            try (var stream = Files.walk(appData, 4)) {
                return stream
                        .filter(p -> p.toString().toLowerCase().endsWith(".lnk"))
                        .filter(p -> p.getFileName().toString().toLowerCase()
                                .contains(exeName.replace(".exe", "").toLowerCase()))
                        .map(Path::toString)
                        .findFirst()
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value != null ? value : "";
    }

    private Properties loadExternalProperties() {
        Properties props = new Properties();
        Path configFile = Path.of("apolo-config.properties");
        if (Files.exists(configFile)) {
            try (InputStream is = Files.newInputStream(configFile)) {
                props.load(is);
            } catch (IOException e) {
                log.warn("Error reading apolo-config.properties: {}", e.getMessage());
            }
        }
        return props;
    }

    private void loadExternalMappings(Map<String, String> map, Properties props) {
        int overrides = 0;
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("app.")) {
                String appName = key.substring(4).toLowerCase();
                map.put(appName, props.getProperty(key));
                overrides++;
            }
        }
        log.info("Loaded {} overrides from apolo-config.properties", overrides);
    }

    /**
     * Command aliases: variations that map to the same canonical command.
     * Ordered from most specific to least specific for correct matching.
     */
    private Map<String, List<String>> loadCommandAliases() {
        Map<String, List<String>> aliases = new LinkedHashMap<>();

        // "open" and variants
        aliases.put("open", List.of(
                "open the", "open a", "open", "launch",
                "start", "run"
        ));

        // Minimize / show desktop
        aliases.put("minimize", List.of(
                "show the desktop", "show desktop", "show this top",
                "go to desktop", "minimize all",
                "minimize everything",
                "minimize", "minimise", "desktop"
        ));

        // Lock
        aliases.put("lock", List.of(
                "lock the computer", "lock the pc",
                "lock the session", "lock session",
                "lock computer", "lock pc",
                "lock"
        ));

        // Turn off screen
        aliases.put("turn off the screen", List.of(
                "turn off the screen", "turn off screen",
                "turn off the monitor", "turn off monitor",
                "screen off", "monitor off"
        ));

        // Close app
        aliases.put("close", List.of(
                "close the application", "close application",
                "close the window", "close window",
                "close the program",
                "close app", "close it",
                "close"
        ));

        // Code mode - refined command for development workspace
        aliases.put("code", List.of(
                "code", "code mode", "start coding", "coding mode",
                "development mode", "dev mode"
        ));

        // Restart app
        aliases.put("restart", List.of(
                "restart spotify", "restart app", "restart application",
                "restart"
        ));

        return Collections.unmodifiableMap(aliases);
    }

    private String resolveModelPath() {
        String fromProp = System.getProperty(MODELS_DIR_PROPERTY);
        if (fromProp != null && Files.isDirectory(Path.of(fromProp))) {
            return fromProp;
        }

        // Search for Spanish model in models/ directory
        Path defaultPath = Path.of(DEFAULT_MODELS_DIR, "vosk-model-small-es-0.42");
        if (Files.isDirectory(defaultPath)) {
            return defaultPath.toString();
        }

        // Search for any vosk model in models/
        Path modelsDir = Path.of(DEFAULT_MODELS_DIR);
        if (Files.isDirectory(modelsDir)) {
            try (var stream = Files.list(modelsDir)) {
                Optional<Path> firstModel = stream
                        .filter(Files::isDirectory)
                        .filter(p -> p.getFileName().toString().startsWith("vosk-model"))
                        .findFirst();
                if (firstModel.isPresent()) {
                    return firstModel.get().toString();
                }
            } catch (IOException e) {
                // Ignore
            }
        }

        return DEFAULT_MODELS_DIR + "/vosk-model-small-es-0.42";
    }

    // --- Getters ---

    public Map<String, String> getAppMappings() {
        return appMappings;
    }

    public Map<String, List<String>> getCommandAliases() {
        return commandAliases;
    }

    public String getModelPath() {
        return modelPath;
    }

    public int getCommandWindowMillis() {
        return commandWindowMillis;
    }

    public int getWakeWordCooldownMillis() {
        return wakeWordCooldownMillis;
    }

    public String getWakeWord() {
        return "apolo";
    }

    public String getMimoApiKey() {
        return mimoApiKey;
    }

    public String getMimoApiUrl() {
        return mimoApiUrl;
    }

    public String getMimoTtsUrl() {
        return mimoTtsUrl;
    }
}
