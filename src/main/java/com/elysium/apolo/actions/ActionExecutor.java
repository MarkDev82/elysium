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
            feedback.speak("No he entendido el comando.");
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
            case RESTART_APP -> executeRestartApp(command.argument());
            case SPEAK -> {
                feedback.speak(command.text() != null ? command.text() : "Procesado.");
                yield true;
            }
            case UNKNOWN -> {
                feedback.speak("No he entendido el comando.");
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

        feedback.speak("Abriendo " + appName + ".");
        boolean result = WindowsActions.openApplication(target);

        if (!result) {
            log.error("Could not open '{}'", appName);
            feedback.speak("No he podido abrir " + appName + ".");
        }

        return result;
    }

    private boolean executeMinimize() {
        feedback.speak("Mostrando escritorio.");
        return WindowsActions.showDesktop();
    }

    private boolean executeLock() {
        feedback.speak("Bloqueando el equipo.");
        return WindowsActions.lockWorkstation();
    }

    private boolean executeTurnOffScreen() {
        feedback.speak("Apagando la pantalla.");
        return WindowsActions.turnOffMonitor();
    }

    private boolean executeCloseApp() {
        feedback.speak("Cerrando la ventana.");
        return WindowsActions.closeForegroundApp();
    }

    private boolean executeRestartApp(String appName) {
        // Currently only supports Spotify, but can be extended
        if (!"spotify".equalsIgnoreCase(appName)) {
            log.warn("Restart app only supports 'spotify' for now, got: {}", appName);
            feedback.speak("Solo puedo reiniciar Spotify por ahora.");
            return false;
        }

        feedback.speak("Reiniciando Spotify.");
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
            feedback.speak("Spotify reiniciado.");
        } else {
            feedback.speak("No he podido reabrir Spotify.");
        }

        return reopened;
    }

    // --- Media and Volume ---

    private boolean executeMediaPlayPause() {
        feedback.speak("Reproduciendo/Pausando.");
        return WindowsActions.mediaPlayPause();
    }

    private boolean executeMediaNext() {
        feedback.speak("Siguiente pista.");
        return WindowsActions.mediaNext();
    }

    private boolean executeMediaPrev() {
        feedback.speak("Pista anterior.");
        return WindowsActions.mediaPrev();
    }

    private boolean executeVolumeUp() {
        feedback.speak("Subiendo volumen.");
        return WindowsActions.volumeUp();
    }

    private boolean executeVolumeDown() {
        feedback.speak("Bajando volumen.");
        return WindowsActions.volumeDown();
    }

    private boolean executeVolumeMute() {
        feedback.speak("Silenciando sistema.");
        return WindowsActions.volumeMute();
    }

    // --- Window Management and Productivity ---

    private boolean executeSnapLeft() {
        feedback.speak("Ajustando a la izquierda.");
        return WindowsActions.snapLeft();
    }

    private boolean executeSnapRight() {
        feedback.speak("Ajustando a la derecha.");
        return WindowsActions.snapRight();
    }

    private boolean executeMaximizeApp() {
        feedback.speak("Maximizando ventana.");
        return WindowsActions.maximizeApp();
    }

    private boolean executeTakeScreenshot() {
        feedback.speak("Abriendo herramienta de recortes.");
        return WindowsActions.takeScreenshot();
    }

    private boolean executeTypeClipboard() {
        feedback.speak("Pegando portapapeles.");
        return WindowsActions.typeClipboard();
    }
}
