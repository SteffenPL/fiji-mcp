package com.fiji.mcp.bridge;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.MenuBar;
import java.awt.RenderingHints;

/**
 * Visual execution lock for the IJ main frame. Shows a semi-transparent
 * glass pane over the toolbar window plus a centered status card with a
 * Cancel button, and disables the AWT menu bar. Defense-in-depth alongside
 * {@link DialogWatchdog} — not a hard guarantee, just an emphatic visual
 * "agent at work" cue and a closing of the obvious user-interference
 * vectors (menu/toolbar clicks).
 *
 * <p>Two-phase init: constructed before {@link ExecutionReporter} exists
 * (so the cancel hook can call back into reporter.kill), then
 * {@link #setCancelHook(Runnable)} is called once the reporter is ready.
 *
 * <p>Both {@link #acquire()} and {@link #release()} are safe to call from
 * any thread — they route through {@code SwingUtilities.invokeLater} if
 * not already on the EDT.
 */
public class ExecutionLock {

    private final Frame ijFrame;
    private volatile Runnable cancelHook = () -> {};
    private final LockGlassPane glassPane;
    private MenuBar originalMenuBar;
    private long acquireMillis;

    public ExecutionLock(Frame ijFrame) {
        this.ijFrame = ijFrame;
        this.glassPane = new LockGlassPane();
        if (ijFrame instanceof javax.swing.JFrame) {
            ((javax.swing.JFrame) ijFrame).setGlassPane(glassPane);
        }
    }

    public void setCancelHook(Runnable cancelHook) {
        this.cancelHook = cancelHook == null ? () -> {} : cancelHook;
    }

    public void acquire() {
        runOnEdt(() -> {
            try {
                acquireMillis = System.currentTimeMillis();
                glassPane.startTimer();
                glassPane.setVisible(true);
                MenuBar mb = ijFrame.getMenuBar();
                if (mb != null) {
                    originalMenuBar = mb;
                    for (int i = 0; i < mb.getMenuCount(); i++) {
                        mb.getMenu(i).setEnabled(false);
                    }
                }
                ijFrame.repaint();
            } catch (Throwable t) {
                System.err.println("[fiji-mcp] lock acquire (EDT) failed: " + t);
            }
        });
    }

    public void release() {
        runOnEdt(() -> {
            try {
                glassPane.stopTimer();
                glassPane.setVisible(false);
                if (originalMenuBar != null) {
                    for (int i = 0; i < originalMenuBar.getMenuCount(); i++) {
                        originalMenuBar.getMenu(i).setEnabled(true);
                    }
                    originalMenuBar = null;
                }
                ijFrame.repaint();
            } catch (Throwable t) {
                System.err.println("[fiji-mcp] lock release (EDT) failed: " + t);
            }
        });
    }

    private void runOnEdt(Runnable r) {
        if (SwingUtilities.isEventDispatchThread()) {
            r.run();
        } else {
            SwingUtilities.invokeLater(r);
        }
    }

    private class LockGlassPane extends JPanel {
        private final JLabel statusLabel = new JLabel("Bridge executing", SwingConstants.CENTER);
        private final Timer secondTimer;

        LockGlassPane() {
            setOpaque(false);
            setLayout(new BorderLayout());

            JPanel card = new JPanel();
            card.setLayout(new javax.swing.BoxLayout(card, javax.swing.BoxLayout.Y_AXIS));
            card.setBackground(new Color(40, 40, 40, 230));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                    BorderFactory.createEmptyBorder(20, 30, 20, 30)));

            statusLabel.setForeground(Color.WHITE);
            statusLabel.setFont(statusLabel.getFont().deriveFont(Font.BOLD, 14f));
            statusLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            card.add(statusLabel);

            card.add(Box.createRigidArea(new Dimension(0, 10)));

            JButton cancel = new JButton("Cancel");
            cancel.setAlignmentX(Component.CENTER_ALIGNMENT);
            cancel.addActionListener(e -> {
                try { cancelHook.run(); }
                catch (Throwable t) {
                    System.err.println("[fiji-mcp] lock cancel hook threw: " + t);
                }
            });
            card.add(cancel);

            JPanel center = new JPanel(new FlowLayout(FlowLayout.CENTER));
            center.setOpaque(false);
            center.add(card);
            add(center, BorderLayout.CENTER);

            secondTimer = new Timer(1000, e -> updateStatus());
            secondTimer.setRepeats(true);
        }

        void startTimer() {
            updateStatus();
            secondTimer.start();
        }

        void stopTimer() {
            secondTimer.stop();
        }

        private void updateStatus() {
            long elapsed = (System.currentTimeMillis() - acquireMillis) / 1000;
            statusLabel.setText("Bridge executing \u2014 " + elapsed + "s elapsed");
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.55f));
            g2.setColor(new Color(20, 20, 20));
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
