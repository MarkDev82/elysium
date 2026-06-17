package com.elysium.apolo.integrations.windows;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Acciones concretas sobre Windows.
 * Cada método resuelve una acción específica usando el mecanismo más fiable.
 *
 * AUDITORÍA Alt+F4 (closeForegroundApp):
 * - Funciona en la mayoría de ventanas estándar (Win32, WinForms, WPF, UWP, Electron)
 * - FALLA si la ventana intercepta Alt+F4 (ej: algunos juegos, apps con confirmación propia)
 * - FALLA si no hay ventana en foco (escritorio vacío)
 * - FALLA si la ventana tiene un diálogo modal abierto (cierra el diálogo, no la app)
 * - NO cierra procesos en background sin ventana visible
 * - Fallback PowerShell WScript.Shell.SendKeys es equivalente en fiabilidad
 */
public final class WindowsActions {

    private static final Logger log = LoggerFactory.getLogger(WindowsActions.class);

    private WindowsActions() {
    }

    /**
     * Abre una aplicación o URL.
     * Estrategia jerárquica:
     * 1. Si es una URL (empieza por http), abrir con Desktop.browse
     * 2. Si es una ruta absoluta que existe, ejecutar directamente
     * 3. Si es un .lnk (acceso directo), ejecutar con cmd /c start
     * 4. Si contiene argumentos, separar y ejecutar
     * 5. Si es un .exe, ejecutar con ProcessBuilder
     * 6. Intentar como comando directo en PATH
     * 7. Fallback: cmd /c start
     */
    public static boolean openApplication(String target) {
        long start = System.currentTimeMillis();
        log.info("Abriendo aplicación: {}", target);

        try {
            boolean result;

            // Case 1: URL
            if (target.startsWith("http://") || target.startsWith("https://")) {
                log.debug("  -> Opening as URL");
                Desktop.getDesktop().browse(URI.create(target));
                result = true;
            }
            // Case 2: Absolute path that exists (first token is an existing file)
            else if (Files.exists(Path.of(target.split("\\s+")[0]))) {
                log.debug("  -> Absolute path found: {}", target);
                result = executeWithArgs(target);
            }
            // Case 3: Shortcut .lnk
            else if (target.endsWith(".lnk")) {
                log.debug("  -> Opening shortcut: {}", target);
                result = executeCommand("cmd", "/c", "start", "", target);
            }
            // Case 4: Command starting with known executable (.exe in first token)
            else {
                String firstToken = target.split("\\s+")[0].toLowerCase();
                boolean isKnownExe = firstToken.endsWith(".exe") || 
                                     firstToken.equals("explorer.exe") ||
                                     firstToken.equals("cmd.exe") ||
                                     firstToken.equals("powershell.exe") ||
                                     firstToken.equals("rundll32.exe");
                
                if (isKnownExe) {
                    log.debug("  -> Known executable with args: {}", target);
                    result = executeWithArgs(target);
                    if (!result) {
                        result = executeCommand("cmd", "/c", "start", "", target);
                    }
                } else {
                    // Case 5: Simple command (trust PATH)
                    log.debug("  -> Trying as PATH command: {}", target);
                    result = executeCommand(target);
                    if (!result) {
                        log.debug("  -> Fallback cmd /c start: {}", target);
                        result = executeCommand("cmd", "/c", "start", "", target);
                    }
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.info("openApplication('{}') -> {} in {}ms", target, result ? "OK" : "FAIL", elapsed);
            return result;

        } catch (Exception e) {
            log.error("Error opening '{}': {}", target, e.getMessage());
            return false;
        }
    }

    /**
     * Ejecuta un comando que puede tener argumentos (ej: "Update.exe --processStart Discord.exe")
     */
    private static boolean executeWithArgs(String fullCommand) {
        String[] parts = fullCommand.split("\\s+");
        if (parts.length == 0) return false;
        return executeCommand(parts);
    }

    /**
     * Minimizes all windows (show desktop, equivalent to Win+D).
     * Uses PowerShell COM for Shell.Application.ToggleDesktop.
     */
    public static boolean showDesktop() {
        long start = System.currentTimeMillis();
        log.info("Showing desktop");
        try {
            boolean result = executePowerShell(
                    "$shell = New-Object -ComObject Shell.Application; $shell.ToggleDesktop()"
            );
            long elapsed = System.currentTimeMillis() - start;
            log.info("showDesktop() -> {} in {}ms", result ? "OK" : "FAIL", elapsed);
            return result;
        } catch (Exception e) {
            log.error("Error showing desktop: {}", e.getMessage());
            boolean fallback = simulateWinKey(KeyEvent.VK_D);
            long elapsed = System.currentTimeMillis() - start;
            log.info("showDesktop() fallback -> {} in {}ms", fallback ? "OK" : "FAIL", elapsed);
            return fallback;
        }
    }

    /**
     * Locks the Windows session.
     * Uses rundll32 user32.dll,LockWorkStation (reliable native method).
     */
    public static boolean lockWorkstation() {
        long start = System.currentTimeMillis();
        log.info("Locking workstation");
        try {
            boolean result = executeCommand("rundll32.exe", "user32.dll,LockWorkStation");
            long elapsed = System.currentTimeMillis() - start;
            log.info("lockWorkstation() -> {} in {}ms", result ? "OK" : "FAIL", elapsed);
            return result;
        } catch (Exception e) {
            log.error("Error locking workstation: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Turns off the monitor.
     * Uses PowerShell to send WM_SYSCOMMAND with SC_MONITORPOWER.
     */
    public static boolean turnOffMonitor() {
        long start = System.currentTimeMillis();
        log.info("Turning off monitor");
        try {
            // Use Add-Type with MemberDefinition for more reliable P/Invoke
            String psCommand = "Add-Type -MemberDefinition '[DllImport(\"user32.dll\")] public static extern IntPtr SendMessage(IntPtr hWnd, int hMsg, int wParam, int lParam);' -Name 'Win32' -Namespace 'Win32' -PassThru | ForEach-Object { $_.SendMessage([IntPtr]0xFFFF, 0x0112, 0xF170, 2) }";
            boolean result = executePowerShell(psCommand);
            long elapsed = System.currentTimeMillis() - start;
            log.info("turnOffMonitor() -> {} in {}ms", result ? "OK" : "FAIL", elapsed);
            return result;
        } catch (Exception e) {
            log.error("Error turning off monitor: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Closes the application in the foreground.
     * Simulates Alt+F4 via Robot with delay for reliability.
     *
     * Documented limitations:
     * - Does not work if no window is in focus
     * - If the app has a confirmation dialog, Alt+F4 may close the dialog but not the app
     * - Some apps (games, fullscreen apps) intercept Alt+F4
     * - The 50ms delay between press/release is needed for Windows to register the keystroke
     */
    public static boolean closeForegroundApp() {
        long start = System.currentTimeMillis();
        log.info("Closing foreground application (Alt+F4)");
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(50); // 50ms between events for reliability

            robot.keyPress(KeyEvent.VK_ALT);
            robot.keyPress(KeyEvent.VK_F4);
            robot.keyRelease(KeyEvent.VK_F4);
            robot.keyRelease(KeyEvent.VK_ALT);

            long elapsed = System.currentTimeMillis() - start;
            log.info("closeForegroundApp() -> OK (Alt+F4 sent) in {}ms", elapsed);
            return true;
        } catch (AWTException e) {
            log.warn("Robot failed for Alt+F4, trying PowerShell fallback: {}", e.getMessage());
            boolean fallback = executePowerShell(
                    "$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys('%%{F4}')"
            );
            long elapsed = System.currentTimeMillis() - start;
            log.info("closeForegroundApp() fallback -> {} in {}ms", fallback ? "OK" : "FAIL", elapsed);
            return fallback;
        }
    }

    // --- Media and Volume Controls ---

    public static boolean mediaPlayPause() {
        log.info("Media: Play/Pause");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]179)");
    }

    public static boolean mediaNext() {
        log.info("Media: Next");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]176)");
    }

    public static boolean mediaPrev() {
        log.info("Media: Previous");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]177)");
    }

    public static boolean volumeUp() {
        log.info("Volume: Up");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]175)");
    }

    public static boolean volumeDown() {
        log.info("Volume: Down");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]174)");
    }

    public static boolean volumeMute() {
        log.info("Volume: Mute");
        return executePowerShell("$wshell = New-Object -ComObject WScript.Shell; $wshell.SendKeys([char]173)");
    }

    // --- Window Management ---

    public static boolean snapLeft() {
        log.info("Snapping window left");
        return simulateWinKey(KeyEvent.VK_LEFT);
    }

    public static boolean snapRight() {
        log.info("Snapping window right");
        return simulateWinKey(KeyEvent.VK_RIGHT);
    }

    public static boolean maximizeApp() {
        log.info("Maximizing window");
        return simulateWinKey(KeyEvent.VK_UP);
    }

    public static boolean takeScreenshot() {
        log.info("Taking screenshot (Win+Shift+S)");
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(50);
            robot.keyPress(KeyEvent.VK_WINDOWS);
            robot.keyPress(KeyEvent.VK_SHIFT);
            robot.keyPress(KeyEvent.VK_S);
            robot.keyRelease(KeyEvent.VK_S);
            robot.keyRelease(KeyEvent.VK_SHIFT);
            robot.keyRelease(KeyEvent.VK_WINDOWS);
            return true;
        } catch (AWTException e) {
            log.error("Error taking screenshot: {}", e.getMessage());
            return false;
        }
    }

    public static boolean typeClipboard() {
        log.info("Typing clipboard content (Ctrl+V)");
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(50);
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            return true;
        } catch (AWTException e) {
            log.error("Error typing clipboard: {}", e.getMessage());
            return false;
        }
    }

    public static String readClipboard() {
        log.info("Reading clipboard content");
        try {
            return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        } catch (UnsupportedFlavorException | IOException e) {
            log.error("Error reading clipboard: {}", e.getMessage());
            return null;
        }
    }

    public static boolean writeClipboard(String text) {
        log.info("Writing to clipboard");
        try {
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            return true;
        } catch (Exception e) {
            log.error("Error writing clipboard: {}", e.getMessage());
            return false;
        }
    }

    // --- System Maintenance ---

    public static boolean cleanSystem() {
        log.info("Cleaning system (Empty Recycle Bin & Temp Files)");
        // Run both commands sequentially in PowerShell, ignore locked file errors and force exit 0
        String command = "try { Clear-RecycleBin -Force -ErrorAction SilentlyContinue } catch {}; " +
                         "try { Remove-Item -Path \"$env:TEMP\\*\" -Recurse -Force -ErrorAction SilentlyContinue } catch {}; " +
                         "exit 0";
        return executePowerShell(command, 60);
    }

    public static boolean enterFocusMode() {
        log.info("Entering Focus Mode (Closing all taskbar windows except Apolo)");
        // Get all main windows, exclude Java (Apolo), cmd, powershell, Code, and WindowsTerminal, then gracefully close them
        String command = "$processes = Get-Process | Where-Object { " +
                         "$_.MainWindowHandle -ne 0 " +
                         "-and $_.ProcessName -notmatch 'java' " +
                         "-and $_.ProcessName -notmatch 'cmd' " +
                         "-and $_.ProcessName -notmatch 'powershell' " +
                         "-and $_.ProcessName -notmatch 'WindowsTerminal' " +
                         "-and $_.ProcessName -notmatch 'Code' }; " +
                         "foreach ($p in $processes) { $p.CloseMainWindow() | Out-Null }";
        return executePowerShell(command, 10);
    }

    // --- Helper methods ---

    private static boolean executeCommand(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            // Don't wait: apps should open asynchronously
            return true;
        } catch (IOException e) {
            log.debug("executeCommand failed [{}]: {}", String.join(" ", command), e.getMessage());
            return false;
        }
    }

    private static boolean executePowerShell(String command) {
        return executePowerShell(command, 10);
    }

    private static boolean executePowerShell(String command, int timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive", "-Command", command
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("PowerShell timeout after {}s", timeoutSeconds);
                process.destroyForcibly();
                return false;
            }
            int exitCode = process.exitValue();
            if (exitCode != 0) {
                // Capture stderr for diagnostics
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()))) {
                    String error = reader.readLine();
                    if (error != null) {
                        log.debug("PowerShell stderr: {}", error);
                    }
                }
            }
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            log.debug("executePowerShell failed: {}", e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static boolean simulateWinKey(int keyCode) {
        try {
            Robot robot = new Robot();
            robot.setAutoDelay(50);
            robot.keyPress(KeyEvent.VK_WINDOWS);
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            robot.keyRelease(KeyEvent.VK_WINDOWS);
            return true;
        } catch (AWTException e) {
            log.error("Error simulating Win key: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Kills a process by name using taskkill.
     *
     * @param processName the name of the process to kill (e.g., "Spotify.exe")
     * @return true if the process was killed successfully
     */
    public static boolean killProcess(String processName) {
        log.info("Killing process: {}", processName);
        try {
            // Use taskkill /F /IM to forcefully kill the process
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", processName);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            if (!finished) {
                log.warn("taskkill timeout after 10s");
                process.destroyForcibly();
                return false;
            }
            int exitCode = process.exitValue();
            // taskkill returns 0 on success, 128 if process not found
            boolean success = exitCode == 0 || exitCode == 128;
            if (success) {
                log.info("Process {} killed successfully (exit code: {})", processName, exitCode);
            } else {
                log.warn("Failed to kill process {} (exit code: {})", processName, exitCode);
            }
            return success;
        } catch (IOException | InterruptedException e) {
            log.error("Error killing process {}: {}", processName, e.getMessage());
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
