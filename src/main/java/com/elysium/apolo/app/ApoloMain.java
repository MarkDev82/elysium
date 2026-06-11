package com.elysium.apolo.app;

import com.elysium.apolo.actions.ActionExecutor;
import com.elysium.apolo.audio.AudioCapture;
import com.elysium.apolo.commands.Command;
import com.elysium.apolo.config.ApoloConfig;
import com.elysium.apolo.feedback.ApoloState;
import com.elysium.apolo.feedback.FeedbackService;
import com.elysium.apolo.overlay.StatusOverlay;
import com.elysium.apolo.router.CommandRouter;
import com.elysium.apolo.speech.SpeechService;
import com.elysium.apolo.wakeword.WakeWordDetector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.LineUnavailableException;
import java.io.IOException;

/**
 * Main entry point for Apolo.
 * Orchestrates the complete cycle with detailed phase timing.
 */
public final class ApoloMain {

    private static final Logger log = LoggerFactory.getLogger(ApoloMain.class);

    private final ApoloConfig config;
    private final AudioCapture audioCapture;
    private final SpeechService speechService;
    private final CommandRouter router;
    private final ActionExecutor executor;
    private final FeedbackService feedback;
    private final WakeWordDetector wakeWordDetector;
    private final StatusOverlay overlay;
    private volatile boolean running = false;

    // Global metrics
    private int totalCommands = 0;
    private int successfulCommands = 0;
    private int failedCommands = 0;
    private long totalWakeToCommandMs = 0;
    private long totalCommandToActionMs = 0;

    public ApoloMain() {
        this.config = new ApoloConfig();
        this.audioCapture = new AudioCapture();
        this.speechService = new SpeechService(config);
        this.router = new CommandRouter(config);
        this.feedback = new FeedbackService();
        this.executor = new ActionExecutor(config, feedback);
        this.wakeWordDetector = new WakeWordDetector(speechService, audioCapture, config, feedback);
        this.overlay = new StatusOverlay();
    }

    /**
     * Starts Apolo.
     */
    public void start() {
        long bootStart = System.currentTimeMillis();

        log.info("========================================");
        log.info("  ELYSIUM - Apolo V1");
        log.info("  Voice automation assistant");
        log.info("========================================");

        try {
            // 1. Initialize overlay
            overlay.initialize();
            overlay.connectTo(feedback);

            // 2. Initialize Vosk model
            log.info("[BOOT] Loading voice model...");
            long modelStart = System.currentTimeMillis();
            speechService.initialize();
            log.info("[BOOT] Model loaded in {}ms", System.currentTimeMillis() - modelStart);

            // 3. Start audio capture
            log.info("[BOOT] Initializing microphone...");
            long audioStart = System.currentTimeMillis();
            audioCapture.start();
            log.info("[BOOT] Microphone ready in {}ms", System.currentTimeMillis() - audioStart);

            // 4. Start wake word detector
            running = true;
            wakeWordDetector.start(this::onWakeWordDetected);

            long bootTime = System.currentTimeMillis() - bootStart;
            log.info("[BOOT] Apolo ready in {}ms total", bootTime);
            log.info("Say '{}' to activate.", config.getWakeWord());

            feedback.speak("Apolo ready.");

            // Shutdown hook
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "apolo-shutdown"));

            // Main thread waits
            while (running) {
                Thread.sleep(1000);
            }

        } catch (IOException e) {
            log.error("Fatal error initializing Apolo: {}", e.getMessage(), e);
            System.err.println("\n=== ERROR ===");
            System.err.println(e.getMessage());
            System.err.println("\nMake sure you have downloaded the English Vosk model.");
            System.exit(1);
        } catch (LineUnavailableException e) {
            log.error("Could not access microphone: {}", e.getMessage(), e);
            System.err.println("\n=== ERROR ===");
            System.err.println("Could not access microphone.");
            AudioCapture.listAvailableMicrophones();
            System.exit(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("Apolo interrupted");
        }
    }

    /**
     * Callback when wake word is detected.
     * Opens the command window with detailed timing.
     */
    private void onWakeWordDetected() {
        long wakeTime = System.currentTimeMillis();
        log.info("--- [TIMING] Wake word detected at t=0 ---");

        feedback.setState(ApoloState.LISTENING);

        // Listen for command
        long listenStart = System.currentTimeMillis();
        String recognizedText = speechService.listenForCommand(audioCapture);
        long listenTime = System.currentTimeMillis() - listenStart;

        log.info("[TIMING] Command listening: {}ms", listenTime);

        if (recognizedText == null || recognizedText.isBlank()) {
            log.info("No command received. Returning to idle.");
            feedback.speak("I did not hear you.");
            feedback.setState(ApoloState.IDLE);
            return;
        }

        feedback.setState(ApoloState.EXECUTING);

        // Route the command
        long routeStart = System.currentTimeMillis();
        Command command = router.route(recognizedText);
        long routeTime = System.currentTimeMillis() - routeStart;

        log.info("[TIMING] Routing: {}ms -> type={}, arg={}",
                routeTime, command.type(), command.argument());

        // Execute
        long execStart = System.currentTimeMillis();
        boolean success = executor.execute(command);
        long execTime = System.currentTimeMillis() - execStart;

        log.info("[TIMING] Action execution: {}ms -> {}", execTime, success ? "OK" : "FAIL");

        // Metrics
        totalCommands++;
        long wakeToCommand = listenStart - wakeTime;
        long commandToAction = execStart - listenStart;
        totalWakeToCommandMs += wakeToCommand;
        totalCommandToActionMs += commandToAction;

        if (success) {
            successfulCommands++;
        } else {
            failedCommands++;
            if (command.isKnown()) {
                log.warn("Action failed for command: {}", command);
            }
        }

        log.info("[TIMING] Total wake->action: {}ms | wake->listen: {}ms | listen->action: {}ms",
                System.currentTimeMillis() - wakeTime, wakeToCommand, commandToAction);
        log.info("[METRICS] Commands: {} total, {} OK, {} FAIL",
                totalCommands, successfulCommands, failedCommands);

        feedback.setState(ApoloState.IDLE);
        log.info("--- Returning to idle ---");
    }

    /**
     * Shuts down Apolo cleanly.
     */
    public void shutdown() {
        log.info("Shutting down Apolo...");
        running = false;
        wakeWordDetector.stop();
        audioCapture.stop();

        log.info("=== FINAL SESSION METRICS ===");
        log.info("Total commands: {}", totalCommands);
        log.info("Successful commands: {}", successfulCommands);
        log.info("Failed commands: {}", failedCommands);
        if (totalCommands > 0) {
            log.info("Success rate: {}%", String.format("%.1f", (successfulCommands * 100.0 / totalCommands)));
            log.info("Average latency wake->listen: {}ms",
                    totalWakeToCommandMs / totalCommands);
            log.info("Average latency listen->action: {}ms",
                    totalCommandToActionMs / totalCommands);
        }

        feedback.speak("Apolo deactivated.");
        feedback.shutdown();
        speechService.close();
        overlay.dispose();
        log.info("Apolo shut down successfully.");
    }

    /**
     * Main entry point.
     */
    public static void main(String[] args) {
        ApoloMain apolo = new ApoloMain();
        apolo.start();
    }
}
