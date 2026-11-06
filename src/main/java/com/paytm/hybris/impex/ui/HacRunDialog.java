package com.paytm.hybris.impex.ui;

import com.paytm.hybris.impex.hac.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * Modal form dialog for executing ImpEx directly on SAP Commerce HAC.
 * Takes the ImpEx import URL, login credentials, tests connection & CSRF token,
 * and executes the script.
 */
public class HacRunDialog extends JDialog {

    private final String impexContent;
    private final HacConfig config;
    private final HacClient hacClient = new HacClient();

    // Form Fields
    private final JTextField urlField = new JTextField();
    private final JTextField usernameField = new JTextField();
    private final JPasswordField passwordField = new JPasswordField();
    private final JCheckBox rememberCheck = new JCheckBox("Remember Credentials", true);

    // Advanced / Options Fields
    private final JComboBox<String> validationCombo = new JComboBox<>(new String[]{"IMPORT_STRICT", "IMPORT_RELAXED"});
    private final JSpinner threadsSpinner = new JSpinner(new SpinnerNumberModel(16, 1, 64, 1));
    private final JCheckBox legacyCheck = new JCheckBox("Legacy Mode (Jalo)", false);
    private final JCheckBox codeExecCheck = new JCheckBox("Enable Code Execution (#%)", true);
    private final JTextField cookieField = new JTextField();
    private final JTextField csrfField = new JTextField();

    // Status & Output
    private final JLabel connectionBadge = new JLabel("Status: Not Connected");
    private final JTextArea logArea = new JTextArea();
    private final JLabel progressStatusLabel = new JLabel("Ready");

    // Action Buttons
    private final ImpexConverterUI.ColorButton testConnBtn = new ImpexConverterUI.ColorButton("Test Connection", new Color(71, 85, 105));
    private final ImpexConverterUI.ColorButton runBtn = new ImpexConverterUI.ColorButton("Run ImpEx on HAC", new Color(22, 163, 74));
    private final ImpexConverterUI.ColorButton closeBtn = new ImpexConverterUI.ColorButton("Close", new Color(148, 163, 184));
    private final ImpexConverterUI.ColorButton pasteCurlBtn = new ImpexConverterUI.ColorButton("Paste cURL", new Color(59, 130, 246));

    private boolean isExecuting = false;

    public HacRunDialog(Frame parent, String impexContent) {
        super(parent, "HAC Connection Settings & Runner - SAP Commerce", true);
        this.impexContent = impexContent != null ? impexContent : "";
        this.config = HacConfig.load();
        initUI();
        populateFields();
    }

    private void initUI() {
        setSize(740, 580);
        setMinimumSize(new Dimension(650, 480));
        setLocationRelativeTo(getParent());

        JPanel root = new JPanel(new BorderLayout(10, 10));
        root.setBorder(new EmptyBorder(12, 14, 12, 14));
        root.setBackground(new Color(248, 250, 252));

        // 1. Header Banner
        JPanel headerPanel = new JPanel(new BorderLayout(8, 4));
        headerPanel.setBackground(new Color(24, 43, 73));
        headerPanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel title = new JLabel("SAP Commerce HAC - ImpEx Execution");
        title.setFont(new Font("Segoe UI", Font.BOLD, 17));
        title.setForeground(Color.WHITE);

        JLabel subtitle = new JLabel("Authenticate with HAC, verify connection & CSRF token, and run ImpEx directly.");
        subtitle.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        subtitle.setForeground(new Color(203, 213, 225));

        headerPanel.add(title, BorderLayout.NORTH);
        headerPanel.add(subtitle, BorderLayout.SOUTH);

        // 2. Main Form Content Panel
        JPanel centerPanel = new JPanel();
        centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));
        centerPanel.setOpaque(false);

        // 2a. Connection & Credentials Card
        JPanel credCard = new JPanel(new GridBagLayout());
        credCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                " HAC Endpoint & Login Credentials ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(30, 58, 138)
        ));
        credCard.setOpaque(false);

        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(5, 8, 5, 8);
        gbc.fill = GridBagConstraints.HORIZONTAL;

        // Row 0: URL
        gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.0;
        credCard.add(new JLabel("HAC Import URL:"), gbc);

        gbc.gridx = 1; gbc.gridy = 0; gbc.weightx = 1.0;
        urlField.setToolTipText("e.g. https://<hybris-host>/hac/console/impex/import");
        credCard.add(urlField, gbc);

        gbc.gridx = 2; gbc.gridy = 0; gbc.weightx = 0.0;
        pasteCurlBtn.setToolTipText("Paste a cURL command copied from browser DevTools to auto-fill URL, cookies & CSRF");
        credCard.add(pasteCurlBtn, gbc);

        // Row 1: Username
        gbc.gridx = 0; gbc.gridy = 1; gbc.weightx = 0.0;
        credCard.add(new JLabel("Username:"), gbc);

        gbc.gridx = 1; gbc.gridy = 1; gbc.weightx = 1.0;
        credCard.add(usernameField, gbc);

        gbc.gridx = 2; gbc.gridy = 1; gbc.weightx = 0.0;
        credCard.add(rememberCheck, gbc);

        // Row 2: Password
        gbc.gridx = 0; gbc.gridy = 2; gbc.weightx = 0.0;
        credCard.add(new JLabel("Password:"), gbc);

        gbc.gridx = 1; gbc.gridy = 2; gbc.weightx = 1.0;
        credCard.add(passwordField, gbc);

        gbc.gridx = 2; gbc.gridy = 2; gbc.weightx = 0.0;
        credCard.add(testConnBtn, gbc);

        // Row 3: Status Badge
        gbc.gridx = 0; gbc.gridy = 3; gbc.weightx = 0.0;
        credCard.add(new JLabel("Connection Status:"), gbc);

        gbc.gridx = 1; gbc.gridy = 3; gbc.gridwidth = 2; gbc.weightx = 1.0;
        connectionBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        connectionBadge.setForeground(new Color(100, 116, 139));
        credCard.add(connectionBadge, gbc);
        gbc.gridwidth = 1;

        centerPanel.add(credCard);
        centerPanel.add(Box.createVerticalStrut(8));

        // 2b. Advanced Settings (Collapsible / Titled)
        JPanel advPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
        advPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(226, 232, 240)),
                " Import Options & Overrides ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 11),
                new Color(71, 85, 105)
        ));
        advPanel.setOpaque(false);

        advPanel.add(new JLabel("Validation:"));
        advPanel.add(validationCombo);
        advPanel.add(new JLabel("Threads:"));
        threadsSpinner.setPreferredSize(new Dimension(55, 24));
        advPanel.add(threadsSpinner);
        advPanel.add(legacyCheck);
        advPanel.add(codeExecCheck);

        centerPanel.add(advPanel);
        centerPanel.add(Box.createVerticalStrut(8));

        // 2c. Live Execution Console
        logArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        logArea.setEditable(false);
        logArea.setBackground(new Color(15, 23, 42)); // Dark console background
        logArea.setForeground(new Color(241, 245, 249)); // Bright white/light text
        logArea.setCaretColor(Color.WHITE);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createLineBorder(new Color(203, 213, 225)));

        JPanel logCard = new JPanel(new BorderLayout());
        logCard.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(203, 213, 225)),
                " HAC Execution Console & Output ",
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("Segoe UI", Font.BOLD, 12),
                new Color(30, 58, 138)
        ));
        logCard.setOpaque(false);
        logCard.add(logScroll, BorderLayout.CENTER);
        logCard.setPreferredSize(new Dimension(680, 220));

        centerPanel.add(logCard);

        // 3. Bottom Action Bar
        JPanel bottomBar = new JPanel(new BorderLayout(10, 0));
        bottomBar.setOpaque(false);
        bottomBar.setBorder(new EmptyBorder(6, 4, 4, 4));

        progressStatusLabel.setFont(new Font("Segoe UI", Font.ITALIC, 12));
        progressStatusLabel.setForeground(new Color(71, 85, 105));
        bottomBar.add(progressStatusLabel, BorderLayout.WEST);

        JPanel btnGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        btnGroup.setOpaque(false);
        btnGroup.add(closeBtn);
        btnGroup.add(runBtn);
        bottomBar.add(btnGroup, BorderLayout.EAST);

        root.add(headerPanel, BorderLayout.NORTH);
        root.add(centerPanel, BorderLayout.CENTER);
        root.add(bottomBar, BorderLayout.SOUTH);

        setContentPane(root);

        // Event Listeners
        pasteCurlBtn.addActionListener(e -> showPasteCurlDialog());
        testConnBtn.addActionListener(e -> doTestConnection());
        runBtn.addActionListener(e -> doRunOnHac());
        closeBtn.addActionListener(e -> dispose());

        int readyLines = impexContent.isEmpty() ? 0 : impexContent.split("\r\n|\r|\n", -1).length;
        log("HAC Settings ready. ImpEx lines ready to run: " + readyLines);
    }

    private void populateFields() {
        urlField.setText(config.getImportUrl());
        usernameField.setText(config.getUsername());
        passwordField.setText(config.getPassword());
        rememberCheck.setSelected(config.isRememberSettings());
        validationCombo.setSelectedItem(config.getValidationEnum());
        threadsSpinner.setValue(config.getMaxThreads());
        legacyCheck.setSelected(config.isLegacyMode());
        codeExecCheck.setSelected(config.isEnableCodeExecution());
        cookieField.setText(config.getCookie());
        csrfField.setText(config.getCsrfToken());
    }

    private void saveCurrentSettings() {
        config.setImportUrl(urlField.getText().trim());
        config.setUsername(usernameField.getText().trim());
        config.setPassword(new String(passwordField.getPassword()));
        config.setRememberSettings(rememberCheck.isSelected());
        config.setValidationEnum((String) validationCombo.getSelectedItem());
        config.setMaxThreads((Integer) threadsSpinner.getValue());
        config.setLegacyMode(legacyCheck.isSelected());
        config.setEnableCodeExecution(codeExecCheck.isSelected());
        config.save();
    }

    private void log(String msg) {
        String timestamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
        SwingUtilities.invokeLater(() -> {
            logArea.append("[" + timestamp + "] " + msg + "\n");
            logArea.setCaretPosition(logArea.getDocument().getLength());
        });
    }

    private void setWorking(boolean working, String status) {
        this.isExecuting = working;
        progressStatusLabel.setText(status);
        runBtn.setEnabled(!working);
        testConnBtn.setEnabled(!working);
        pasteCurlBtn.setEnabled(!working);
        setCursor(working ? Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR) : Cursor.getDefaultCursor());
    }

    private void doTestConnection() {
        if (isExecuting) return;
        saveCurrentSettings();

        String url = config.getEffectiveImportUrl();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please enter the HAC Import URL.", "URL Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        connectionBadge.setText("● Status: Checking connection...");
        connectionBadge.setForeground(new Color(202, 138, 4)); // Yellow / Amber
        setWorking(true, "Testing connection to HAC...");

        new Thread(() -> {
            try {
                log("Testing connection & credentials for: " + url);
                HacTestResult result = hacClient.testConnection(config, this::log);

                SwingUtilities.invokeLater(() -> {
                    if (result.isSuccess()) {
                        connectionBadge.setText("✔ Connected: " + result.getMessage());
                        connectionBadge.setForeground(new Color(22, 163, 74)); // Green
                        log("Connection test SUCCESS: " + result.getMessage() + " (" + result.getLatencyMs() + " ms)");
                        JOptionPane.showMessageDialog(this, "Connected successfully to HAC!\n" + result.getMessage(), "Connection Success", JOptionPane.INFORMATION_MESSAGE);
                    } else {
                        connectionBadge.setText("✖ Connection Failed: " + result.getMessage());
                        connectionBadge.setForeground(new Color(220, 38, 38)); // Red
                        log("Connection test FAILED: " + result.getMessage());
                        JOptionPane.showMessageDialog(this, "HAC Connection Failed:\n" + result.getMessage(), "Connection Error", JOptionPane.ERROR_MESSAGE);
                    }
                });
            } finally {
                SwingUtilities.invokeLater(() -> setWorking(false, "Ready"));
            }
        }).start();
    }

    private void doRunOnHac() {
        if (isExecuting) return;

        String scriptToRun = this.impexContent;
        if (scriptToRun == null || scriptToRun.trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "No ImpEx script to run. Please convert a file or generate ImpEx in the main window first.", "Script Empty", JOptionPane.WARNING_MESSAGE);
            return;
        }

        saveCurrentSettings();

        String url = config.getEffectiveImportUrl();
        if (url.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify the HAC Import URL.", "URL Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        connectionBadge.setText("● Status: Verifying connection & CSRF...");
        connectionBadge.setForeground(new Color(202, 138, 4));
        setWorking(true, "Verifying HAC connection before import...");

        new Thread(() -> {
            try {
                log("==================================================");
                log("STARTING HAC IMPEX RUN WORKFLOW");
                log("Target: " + url);
                log("==================================================");

                // Step 1: Pre-flight connection & CSRF verification
                log("[Step 1/2] Verifying HAC connection & obtaining active CSRF token...");
                HacTestResult testRes = hacClient.testConnection(config, this::log);

                if (!testRes.isSuccess()) {
                    SwingUtilities.invokeLater(() -> {
                        connectionBadge.setText("✖ Connection Failed: Cannot run ImpEx.");
                        connectionBadge.setForeground(new Color(220, 38, 38));
                        log("[ABORTED] HAC connection verification failed: " + testRes.getMessage());
                        JOptionPane.showMessageDialog(
                                this,
                                "HAC Connection Check Failed!\n\n" + testRes.getMessage() +
                                        "\n\nImpEx execution was aborted to prevent errors.",
                                "HAC Not Connected",
                                JOptionPane.ERROR_MESSAGE
                        );
                    });
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    connectionBadge.setText("✔ Connected: CSRF token verified");
                    connectionBadge.setForeground(new Color(22, 163, 74));
                });

                // Step 2: Run ImpEx
                log("[Step 2/2] Executing ImpEx on HAC...");
                HacImportResult importRes = hacClient.importImpex(config, scriptToRun, this::log);

                SwingUtilities.invokeLater(() -> {
                    if (importRes.isSuccess()) {
                        log("==================================================");
                        log("✔ SUCCESS: " + importRes.getSummary());
                        log("==================================================");
                        JOptionPane.showMessageDialog(
                                this,
                                "ImpEx Import Finished Successfully!\n\n" +
                                        "Summary: " + importRes.getSummary() + "\n" +
                                        "Duration: " + importRes.getDurationMs() + " ms\n" +
                                        "Lines: " + importRes.getTotalLines(),
                                "Import Success",
                                JOptionPane.INFORMATION_MESSAGE
                        );
                    } else {
                        log("==================================================");
                        log("✖ IMPORT FAILED: " + importRes.getSummary());
                        if (importRes.getErrorDetails() != null) {
                            log("Error Details:\n" + importRes.getErrorDetails());
                        }
                        log("==================================================");
                        JOptionPane.showMessageDialog(
                                this,
                                "ImpEx Import Failed on HAC!\n\n" +
                                        importRes.getSummary() +
                                        (importRes.getErrorDetails() != null && !importRes.getErrorDetails().isEmpty()
                                                ? "\n\nDetails:\n" + importRes.getErrorDetails()
                                                : ""),
                                "Import Failed",
                                JOptionPane.ERROR_MESSAGE
                        );
                    }
                });

            } catch (Exception ex) {
                log("[FATAL ERROR] " + ex.getMessage());
                SwingUtilities.invokeLater(() -> {
                    JOptionPane.showMessageDialog(this, "Execution error: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                });
            } finally {
                SwingUtilities.invokeLater(() -> setWorking(false, "Ready"));
            }
        }).start();
    }

    private void showPasteCurlDialog() {
        JTextArea curlArea = new JTextArea(10, 50);
        curlArea.setFont(new Font("Consolas", Font.PLAIN, 12));
        curlArea.setLineWrap(true);
        curlArea.setWrapStyleWord(false);

        JPanel p = new JPanel(new BorderLayout(5, 5));
        p.add(new JLabel("Paste cURL command from Chrome DevTools (Network tab -> Copy as cURL):"), BorderLayout.NORTH);
        p.add(new JScrollPane(curlArea), BorderLayout.CENTER);

        int res = JOptionPane.showConfirmDialog(this, p, "Paste cURL Command", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (res == JOptionPane.OK_OPTION) {
            String text = curlArea.getText().trim();
            if (!text.isEmpty()) {
                boolean parsed = config.parseFromCurl(text);
                if (parsed) {
                    populateFields();
                    log("Parsed URL, Cookie, and CSRF token from cURL command.");
                    JOptionPane.showMessageDialog(this, "cURL successfully parsed!\nURL, Cookies, and CSRF token have been updated.", "cURL Parsed", JOptionPane.INFORMATION_MESSAGE);
                } else {
                    JOptionPane.showMessageDialog(this, "Could not extract HAC endpoint or parameters from the provided command.", "Parse Warning", JOptionPane.WARNING_MESSAGE);
                }
            }
        }
    }
}
