package com.elysium.apolo.overlay;

import com.elysium.apolo.feedback.ApoloState;
import com.elysium.apolo.feedback.FeedbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;

/**
 * Minimal visual overlay: a small status indicator in the screen corner.
 * Not a full UI, just a color dot that changes based on state.
 */
public final class StatusOverlay {

    private static final Logger log = LoggerFactory.getLogger(StatusOverlay.class);

    private JFrame frame;
    private JPanel indicator;
    private volatile boolean visible = false;

    /**
     * Initializes the overlay. Must be called from Swing EDT.
     */
    public void initialize() {
        SwingUtilities.invokeLater(this::createUI);
    }

    private void createUI() {
        frame = new JFrame("Apolo");
        frame.setUndecorated(true);
        frame.setAlwaysOnTop(true);
        frame.setFocusableWindowState(false);
        frame.setType(Window.Type.UTILITY);
        frame.setBackground(new Color(0, 0, 0, 0));

        indicator = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillOval(2, 2, getWidth() - 4, getHeight() - 4);
                g2.dispose();
            }
        };
        indicator.setPreferredSize(new Dimension(20, 20));
        indicator.setOpaque(false);
        indicator.setBackground(getColorForState(ApoloState.IDLE));

        frame.setContentPane(indicator);
        frame.pack();

        // Position in top-right corner
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice().getDefaultConfiguration()
        );
        int x = screenSize.width - insets.right - 40;
        int y = insets.top + 10;
        frame.setLocation(x, y);

        log.info("Status overlay initialized");
    }

    /**
     * Updates the visual indicator based on state.
     */
    public void updateState(ApoloState state) {
        if (indicator == null) return;

        SwingUtilities.invokeLater(() -> {
            indicator.setBackground(getColorForState(state));
            if (!visible && state != ApoloState.IDLE) {
                frame.setVisible(true);
                visible = true;
            } else if (visible && state == ApoloState.IDLE) {
                // Keep visible but dimmed
            }
            indicator.repaint();
        });
    }

    private Color getColorForState(ApoloState state) {
        return switch (state) {
            case IDLE -> new Color(80, 80, 80, 120);           // Dim gray
            case LISTENING -> new Color(0, 150, 255, 200);     // Blue
            case WAKE_DETECTED -> new Color(0, 255, 100, 220); // Green
            case EXECUTING -> new Color(255, 180, 0, 220);     // Orange
            case ERROR -> new Color(255, 50, 50, 220);         // Red
        };
    }

    /**
     * Connects the overlay as a listener to FeedbackService.
     */
    public void connectTo(FeedbackService feedback) {
        feedback.setStateListener(this::updateState);
    }

    public void dispose() {
        if (frame != null) {
            SwingUtilities.invokeLater(() -> {
                frame.setVisible(false);
                frame.dispose();
            });
        }
    }
}
