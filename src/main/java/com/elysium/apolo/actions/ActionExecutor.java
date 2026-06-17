package com.elysium.apolo.actions;

import com.elysium.apolo.commands.Command;
import com.elysium.apolo.commands.CommandType;
import com.elysium.apolo.config.ApoloConfig;
import com.elysium.apolo.feedback.FeedbackService;
import com.elysium.apolo.integrations.windows.WindowsActions;
import com.elysium.apolo.integrations.mimo.MiMoClient;
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
    private final MiMoClient mimoClient;

    public ActionExecutor(ApoloConfig config, FeedbackService feedback) {
        this.config = config;
        this.feedback = feedback;
        this.mimoClient = new MiMoClient(config);
    }

    /**
     * Executes the action corresponding to the command.
     *
     * @return true if the action executed successfully
     */
    public boolean execute(Command command) {
        long start = System.currentTimeMillis();

        if (!command.isKnown()) {
            feedback.speak("I didn't understand the command.");
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
            case MEDIA_PLAY_PAUSE -> executeMediaPlayPause();
            case MEDIA_NEXT -> executeMediaNext();
            case MEDIA_PREV -> executeMediaPrev();
            case VOLUME_UP -> executeVolumeUp();
            case VOLUME_DOWN -> executeVolumeDown();
            case VOLUME_MUTE -> executeVolumeMute();
            case SNAP_LEFT -> executeSnapLeft();
            case SNAP_RIGHT -> executeSnapRight();
            case MAXIMIZE_APP -> executeMaximizeApp();
            case TAKE_SCREENSHOT -> executeTakeScreenshot();
            case TYPE_CLIPBOARD -> executeTypeClipboard();
            case PROCESS_CLIPBOARD -> executeProcessClipboard(command.argument());
            case CLEAN_SYSTEM -> executeCleanSystem();
            case FOCUS_MODE -> executeFocusMode();
            case SMART_DICTATION -> executeSmartDictation(command.argument());
            case RESTART_APP -> executeRestartApp(command.argument());
            case SPEAK -> {
                feedback.speak(command.text() != null ? command.text() : "Processed.");
                yield true;
            }
            case UNKNOWN -> {
                feedback.speak("I didn't understand the command.");
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
            feedback.speak("I couldn't open " + appName + ".");
        }

        return result;
    }

    private boolean executeMinimize() {
        feedback.speak("Showing desktop.");
        return WindowsActions.showDesktop();
    }

    private boolean executeLock() {
        feedback.speak("Locking the workstation.");
        return WindowsActions.lockWorkstation();
    }

    private boolean executeTurnOffScreen() {
        feedback.speak("Turning off the screen.");
        return WindowsActions.turnOffMonitor();
    }

    private boolean executeCloseApp() {
        feedback.speak("Closing window.");
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
            feedback.speak("I couldn't reopen Spotify.");
        }

        return reopened;
    }

    // --- Media and Volume ---

    private boolean executeMediaPlayPause() {
        feedback.speak("Playing or Pausing.");
        return WindowsActions.mediaPlayPause();
    }

    private boolean executeMediaNext() {
        feedback.speak("Next track.");
        return WindowsActions.mediaNext();
    }

    private boolean executeMediaPrev() {
        feedback.speak("Previous track.");
        return WindowsActions.mediaPrev();
    }

    private boolean executeVolumeUp() {
        feedback.speak("Volume up.");
        return WindowsActions.volumeUp();
    }

    private boolean executeVolumeDown() {
        feedback.speak("Volume down.");
        return WindowsActions.volumeDown();
    }

    private boolean executeVolumeMute() {
        feedback.speak("Muting system.");
        return WindowsActions.volumeMute();
    }

    // --- Window Management and Productivity ---

    private boolean executeSnapLeft() {
        feedback.speak("Snapping left.");
        return WindowsActions.snapLeft();
    }

    private boolean executeSnapRight() {
        feedback.speak("Snapping right.");
        return WindowsActions.snapRight();
    }

    private boolean executeMaximizeApp() {
        feedback.speak("Maximizing window.");
        return WindowsActions.maximizeApp();
    }

    private boolean executeTakeScreenshot() {
        feedback.speak("Opening snipping tool.");
        return WindowsActions.takeScreenshot();
    }

    private boolean executeTypeClipboard() {
        feedback.speak("Pasting clipboard.");
        return WindowsActions.typeClipboard();
    }

    private boolean executeProcessClipboard(String instruction) {
        feedback.speak("Reading clipboard.");
        String content = WindowsActions.readClipboard();
        if (content == null || content.isBlank()) {
            feedback.speak("Your clipboard is empty.");
            return false;
        }

        // feedback.speak("Processing..."); // Optional, might overlap if not queued. Let's just block and wait.
        String response = mimoClient.processClipboardTask(instruction, content);
        feedback.speak(response);
        return true;
    }

    private boolean executeCleanSystem() {
        feedback.speak("Cleaning system recycle bin and temporary files.");
        boolean result = WindowsActions.cleanSystem();
        if (result) {
            feedback.speak("System clean complete.");
        } else {
            feedback.speak("There was a problem cleaning the system.");
        }
        return result;
    }

    private boolean executeFocusMode() {
        feedback.speak("Entering focus mode. Closing background taskbar windows.");
        boolean result = WindowsActions.enterFocusMode();
        if (result) {
            feedback.speak("Focus mode activated.");
        } else {
            feedback.speak("There was a problem entering focus mode.");
        }
        return result;
    }

    private boolean executeSmartDictation(String generatedText) {
        if (generatedText == null || generatedText.isBlank()) {
            feedback.speak("I couldn't generate the text.");
            return false;
        }

        // Save current clipboard
        String oldClipboard = WindowsActions.readClipboard();

        // Inject new text into clipboard
        boolean writeSuccess = WindowsActions.writeClipboard(generatedText);
        if (!writeSuccess) {
            feedback.speak("There was a problem writing to the clipboard.");
            return false;
        }

        // Wait a tiny bit for the OS to register clipboard change
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Simulate Ctrl+V to paste into active window
        boolean pasteSuccess = WindowsActions.typeClipboard();

        // Wait for paste to finish before restoring old clipboard
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Restore old clipboard (if it wasn't null)
        if (oldClipboard != null && !oldClipboard.isBlank()) {
            WindowsActions.writeClipboard(oldClipboard);
        } else {
            // Write empty string if it was empty to avoid leaving generated text
            WindowsActions.writeClipboard("");
        }

        if (pasteSuccess) {
            feedback.speak("Dictation complete.");
            return true;
        } else {
            feedback.speak("There was a problem pasting the text.");
            return false;
        }
    }
}
