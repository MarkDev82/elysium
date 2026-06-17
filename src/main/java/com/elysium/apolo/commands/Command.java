package com.elysium.apolo.commands;

/**
 * Represents a recognized command, normalized and resolved.
 */
public record Command(
        CommandType type,
        String argument,
        String text,
        String rawText
) {

    /**
     * Creates an OPEN_APP command with the application name.
     */
    public static Command openApp(String appName, String rawText) {
        return new Command(CommandType.OPEN_APP, appName, null, rawText);
    }

    /**
     * Creates a system command without additional argument.
     */
    public static Command system(CommandType type, String rawText) {
        return new Command(type, null, null, rawText);
    }

    /**
     * Creates a system command with an argument (e.g., for restart app).
     */
    public static Command system(CommandType type, String argument, String rawText) {
        return new Command(type, argument, null, rawText);
    }

    /**
     * Creates an unknown command.
     */
    public static Command unknown(String rawText) {
        return new Command(CommandType.UNKNOWN, null, null, rawText);
    }
    
    public static Command speak(String text, String rawText) {
        return new Command(CommandType.SPEAK, null, text, rawText);
    }

    public boolean isKnown() {
        return type != CommandType.UNKNOWN;
    }
}
