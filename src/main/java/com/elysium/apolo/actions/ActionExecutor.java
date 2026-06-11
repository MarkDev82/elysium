package com.elysium.apolo.actions;

import com.elysium.apolo.commands.Command;
import com.elysium.apolo.commands.CommandType;
import com.elysium.apolo.config.ApoloConfig;
import com.elysium.apolo.feedback.FeedbackService;
import com.elysium.apolo.integrations.windows.WindowsActions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Action executor: receives a resolved Command and executes the corresponding action.
 * Includes detailed timing per action.
 */
public final class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final ApoloConfig config;
    private final FeedbackService feedback;

    public ActionExecutor(ApoloConfig config, FeedbackService feedback) {
        this.config = config;
        this.feedback = feedback;
    }

    /**
     * Executes the action corresponding to the command.
     *
     * @return true if the action executed successfully
     */
    public boolean execute(Command command) {
        long start = System.currentTimeMillis();

        if (!command.isKnown()) {
            feedback.speak("I did not understand the command.");
            log.info("ActionExecutor: unknown command '{}' in {}ms",
                    command.rawText(), System.currentTimeMillis() - start);
            return false;
        }

        log.info("ActionExecutor: executing type={}, arg='{}'", command.type(), command.argument());

        boolean result = switch (command.type()) {
            case OPEN_APP -> executeOpenApp(command.argument());
            case MINIMIZE -> executeMinimize();
            case LOCK -> executeLock();
            case TURN_OFF_SCREEN -> executeTurnOffScreen();
            case CLOSE_APP -> executeCloseApp();
            case CODE_MODE -> executeCodeMode();
            case RESTART_APP -> executeRestartApp(command.argument());
            case UNKNOWN -> {
                feedback.speak("I did not understand the command.");
                yield false;
            }
        };

        long elapsed = System.currentTimeMillis() - start;
        log.info("ActionExecutor: {} -> {} in {}ms", command.type(), result ? "OK" : "FAIL", elapsed);
        return result;
    }

    private boolean executeOpenApp(String appName) {
        String target = config.getAppMappings().get(appName);

        if (target == null) {
            log.warn("App '{}' not found in mappings, using direct name", appName);
            target = appName;
        } else {
            log.debug("App '{}' resolved to: {}", appName, target);
        }

        feedback.speak("Opening " + appName + ".");
        boolean result = WindowsActions.openApplication(target);

        if (!result) {
            log.error("Could not open '{}'", appName);
            feedback.speak("Could not open " + appName + ".");
        }

        return result;
    }

    private boolean executeMinimize() {
        feedback.speak("Showing desktop.");
        return WindowsActions.showDesktop();
    }

    private boolean executeLock() {
        feedback.speak("Locking the computer.");
        return WindowsActions.lockWorkstation();
    }

    private boolean executeTurnOffScreen() {
        feedback.speak("Turning off the screen.");
        return WindowsActions.turnOffMonitor();
    }

    private boolean executeCloseApp() {
        feedback.speak("Closing the window.");
        return WindowsActions.closeForegroundApp();
    }

    private boolean executeRestartApp(String appName) {
        // Currently only supports Spotify, but can be extended
        if (!"spotify".equalsIgnoreCase(appName)) {
            log.warn("Restart app only supports 'spotify' for now, got: {}", appName);
            feedback.speak("I can only restart Spotify for now.");
            return false;
        }

        feedback.speak("Restarting Spotify.");
        log.info("Restarting Spotify: killing process and restarting");

        // 1. Kill Spotify process
        boolean killed = WindowsActions.killProcess("Spotify.exe");
        if (!killed) {
            log.warn("Failed to kill Spotify process, attempting to reopen anyway");
        }

        // Wait a moment for process to fully terminate
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 2. Reopen Spotify
        String spotifyPath = config.getAppMappings().get("spotify");
        if (spotifyPath == null) {
            log.error("Spotify path not found in mappings");
            feedback.speak("Could not find Spotify path.");
            return false;
        }

        boolean reopened = WindowsActions.openApplication(spotifyPath);
        if (reopened) {
            feedback.speak("Spotify restarted.");
        } else {
            feedback.speak("Failed to reopen Spotify.");
        }

        return reopened;
    }

    private boolean executeCodeMode() {
        feedback.speak("Setting up code mode.");
        log.info("Executing code mode - opening development workspace");
        
        boolean allSuccess = true;
        
        // 1. Minimize and show desktop
        log.info("Code mode: showing desktop");
        WindowsActions.showDesktop();
        
        // 2. Open Antigravity IDE
        String antigravityPath = config.getAppMappings().get("antigravity");
        if (antigravityPath != null) {
            log.info("Code mode: opening Antigravity IDE");
            if (!WindowsActions.openApplication(antigravityPath)) {
                log.warn("Code mode: failed to open Antigravity IDE");
                allSuccess = false;
            }
        } else {
            log.warn("Code mode: Antigravity IDE not found in mappings");
            allSuccess = false;
        }
        
        // 3. Open OpenCode
        String opencodePath = config.getAppMappings().get("opencode");
        if (opencodePath != null) {
            log.info("Code mode: opening OpenCode");
            if (!WindowsActions.openApplication(opencodePath)) {
                log.warn("Code mode: failed to open OpenCode");
                allSuccess = false;
            }
        } else {
            log.warn("Code mode: OpenCode not found in mappings");
            allSuccess = false;
        }
        
        // 4. Open Gemini in browser (instead of Antigravity Chat)
        String browserPath = config.getAppMappings().get("browser");
        if (browserPath == null) {
            browserPath = config.getAppMappings().get("chrome");
        }
        if (browserPath != null) {
            log.info("Code mode: opening Gemini in browser");
            String geminiUrl = "https://gemini.google.com";
            if (!WindowsActions.openApplication(browserPath + " " + geminiUrl)) {
                log.warn("Code mode: failed to open Gemini in browser");
                allSuccess = false;
            }
        } else {
            log.warn("Code mode: browser not found in mappings");
            allSuccess = false;
        }
        
        // 5. Open Spotify
        String spotifyPath = config.getAppMappings().get("spotify");
        if (spotifyPath != null) {
            log.info("Code mode: opening Spotify");
            if (!WindowsActions.openApplication(spotifyPath)) {
                log.warn("Code mode: failed to open Spotify");
                allSuccess = false;
            }
        } else {
            log.warn("Code mode: Spotify not found in mappings");
            allSuccess = false;
        }
        
        // 6. Open File Explorer in Desktop/DEV
        String workspacePath = config.getAppMappings().get("code workspace");
        if (workspacePath != null) {
            log.info("Code mode: opening File Explorer in {}", workspacePath);
            if (!WindowsActions.openApplication("explorer.exe " + workspacePath)) {
                log.warn("Code mode: failed to open File Explorer in workspace");
                allSuccess = false;
            }
        } else {
            log.warn("Code mode: workspace path not found in mappings");
            allSuccess = false;
        }
        
        // Always say "Code mode ready" regardless of partial failures
        feedback.speak("Code mode ready.");
        
        return allSuccess;
    }
}
