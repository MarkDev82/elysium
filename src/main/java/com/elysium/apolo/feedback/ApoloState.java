package com.elysium.apolo.feedback;

/**
 * Current state of the Apolo assistant.
 */
public enum ApoloState {
    IDLE("Idle"),
    LISTENING("Listening..."),
    WAKE_DETECTED("Wake word detected"),
    EXECUTING("Executing..."),
    ERROR("Error");

    private final String label;

    ApoloState(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
