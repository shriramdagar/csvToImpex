package com.paytm.hybris.impex.ui;

import com.paytm.hybris.impex.*;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.nio.file.Files;
import java.util.*;
import java.util.List;

/**
 * Modern Swing User Interface for the Hybris ImpEx Converter.
 * If run with CLI arguments, delegates to headless CLI mode.
 */
public class ImpexConverterUI extends JFrame {

    /**
     * Custom colorful button with smooth rounded corners, anti-aliased text,
     * and responsive hover/pressed states without relying on native Look-and-Feel.
     */
    public static class ColorButton extends JButton {
        private final Color normalBg;
        private final Color hoverBg;
        private final Color pressedBg;
        private final Color borderColor;
        private final Color textColor;
        private final Color disabledBg = new Color(241, 245, 249);
        private final Color disabledBorder = new Color(226, 232, 240);
        private final Color disabledFg = new Color(148, 163, 184);

        public ColorButton(String text, Color baseColor) {
            this(text,
                    baseColor,
                    new Color(
                            Math.min(255, (int)(baseColor.getRed() * 1.15) + 15),
                            Math.min(255, (int)(baseColor.getGreen() * 1.15) + 15),
                            Math.min(255, (int)(baseColor.getBlue() * 1.15) + 15)
                    ),
                    new Color(
                            Math.max(0, (int)(baseColor.getRed() * 0.82)),
                            Math.max(0, (int)(baseColor.getGreen() * 0.82)),
                            Math.max(0, (int)(baseColor.getBlue() * 0.82))
                    ),
                    null,
                    Color.WHITE
            );
        }

        public ColorButton(String text, Color normalBg, Color hoverBg, Color pressedBg, Color borderColor, Color textColor) {
            super(text);
            this.normalBg = normalBg;
            this.hoverBg = hoverBg;
            this.pressedBg = pressedBg;
            this.borderColor = borderColor;
            this.textColor = textColor;

            setFont(new Font("Segoe UI", Font.BOLD, 13));
            setForeground(textColor);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(8, 20, 8, 20));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            if (!isEnabled()) {
                g2.setColor(disabledBg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
                if (disabledBorder != null) {
                    g2.setColor(disabledBorder);
                    g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
                }
                setForeground(disabledFg);
            } else {
                Color currentBg;
                if (getModel().isPressed()) {
                    currentBg = pressedBg;
                } else if (getModel().isRollover()) {
                    currentBg = hoverBg;
                } else {
                    currentBg = normalBg;
                }
                g2.setColor(currentBg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);

                if (borderColor != null) {
                    g2.setColor(borderColor);
                    g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
                }
                setForeground(textColor);
            }

            g2.dispose();
            super.paintComponent(g);
        }
    }

    private final JTextField filePathField = new JTextField();
    private final JTextField urlField = new JTextField();
    private final JRadioButton fileRadio = new JRadioButton("Local File (.xlsx / .csv)", true);
    private final JRadioButton urlRadio = new JRadioButton("Google Sheet URL");
    private final ColorButton browseBtn = new ColorButton("Browse...", new Color(71, 85, 105)); // Slate

    private final JLabel detectionBadge = new JLabel("Please select an input file or URL");

    // Dynamic Options and Action Container (Convert button placed on upper right next to selection)
    private final TitledBorder optionsBorder = BorderFactory.createTitledBorder(
            BorderFactory.createLineBorder(new Color(180, 200, 220)),
            "Options",
            TitledBorder.LEFT,
            TitledBorder.TOP,
            new Font("Segoe UI", Font.BOLD, 12),
            new Color(40, 70, 120)
    );
    private final JPanel optionsCardPanel = new JPanel(new CardLayout());
    private final JPanel optionsAndActionsPanel = new JPanel(new BorderLayout(10, 0));

    // Order Export options
    private final JComboBox<String> orderIdCombo = new JComboBox<>();
    private final JCheckBox includeCartCheck = new JCheckBox("Include Underlying Cart (Section 5)", true);

    // Cart Export options
    private final JComboBox<String> cartCodeCombo = new JComboBox<>();

    // Generic options
    private final JTextField itemTypeField = new JTextField("Product", 14);
    private final JComboBox<String> modeCombo = new JComboBox<>(new String[]{"INSERT_UPDATE", "INSERT", "UPDATE", "REMOVE"});
    private final JTextField uniqueColsField = new JTextField("code", 14);

    // Vibrant Colorful Action Buttons (without icons or symbols)
    private final ColorButton convertBtn = new ColorButton("Convert", new Color(22, 163, 74));   // Emerald Green
    private final ColorButton copyBtn = new ColorButton("Copy", new Color(37, 99, 235));        // Royal Blue
    private final ColorButton saveBtn = new ColorButton("Save as Impex", new Color(79, 70, 229)); // Indigo

    private final JTextArea previewArea = new JTextArea();
    private final JLabel statusLabel = new JLabel("Ready");

    private File currentSelectedFile = null;
    private String currentDetectedType = "NONE"; // CART, ORDER, GENERIC

    public ImpexConverterUI() {
        super("SAP Commerce (Hybris) ImpEx Converter - HAC Console");
        initUI();
    }

    private void initUI() {
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1000, 750);
        setMinimumSize(new Dimension(850, 600));
        setLocationRelativeTo(null);

        JPanel mainPanel = new JPanel(new BorderLayout(10, 10));
        mainPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 1. Header banner
        JPanel headerPanel = new JPanel(new BorderLayout());
        headerPanel.setBackground(new Color(24, 43, 73));
        headerPanel.setBorder(new EmptyBorder(12, 16, 12, 16));

        JLabel titleLabel = new JLabel("SAP Commerce (Hybris) ImpEx Converter");
        titleLabel.setFont(new Font("Segoe UI", Font.BOLD, 18));
        titleLabel.setForeground(Color.WHITE);

        headerPanel.add(titleLabel, BorderLayout.CENTER);

        // 2. Input Configuration Panel
        JPanel topConfigPanel = new JPanel();
        topConfigPanel.setLayout(new BoxLayout(topConfigPanel, BoxLayout.Y_AXIS));

        // Input Source Selector
        JPanel sourceBox = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        ButtonGroup bg = new ButtonGroup();
        bg.add(fileRadio);
        bg.add(urlRadio);
        sourceBox.add(new JLabel("Input Source:"));
        sourceBox.add(fileRadio);
        sourceBox.add(urlRadio);

        // File Row
        JPanel fileRow = new JPanel(new BorderLayout(8, 0));
        fileRow.setBorder(new EmptyBorder(2, 6, 2, 6));
        fileRow.add(new JLabel("File Path: "), BorderLayout.WEST);
        fileRow.add(filePathField, BorderLayout.CENTER);
        fileRow.add(browseBtn, BorderLayout.EAST);

        // URL Row
        JPanel urlRow = new JPanel(new BorderLayout(8, 0));
        urlRow.setBorder(new EmptyBorder(2, 6, 2, 6));
        urlRow.add(new JLabel("Sheet URL:"), BorderLayout.WEST);
        urlRow.add(urlField, BorderLayout.CENTER);
        urlRow.setVisible(false);

        // Dynamic Options Box & Action Buttons
        initDynamicOptions();

        // Detection Badge Bar
        JPanel badgePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        detectionBadge.setFont(new Font("Segoe UI", Font.BOLD, 12));
        detectionBadge.setForeground(new Color(30, 100, 180));
        badgePanel.add(detectionBadge);

        topConfigPanel.add(sourceBox);
        topConfigPanel.add(fileRow);
        topConfigPanel.add(urlRow);
        topConfigPanel.add(badgePanel);
        topConfigPanel.add(optionsAndActionsPanel);

        // 3. ImpEx Preview Area with Copy & Save buttons directly above preview
        JPanel previewHeaderPanel = new JPanel(new BorderLayout());
        previewHeaderPanel.setBorder(new EmptyBorder(4, 2, 4, 2));

        JLabel previewTitle = new JLabel("Generated ImpEx Preview");
        previewTitle.setFont(new Font("Segoe UI", Font.BOLD, 12));
        previewTitle.setForeground(new Color(40, 70, 120));

        JPanel previewActionPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        copyBtn.setEnabled(false);
        saveBtn.setEnabled(false);
        previewActionPanel.add(copyBtn);
        previewActionPanel.add(saveBtn);

        previewHeaderPanel.add(previewTitle, BorderLayout.WEST);
        previewHeaderPanel.add(previewActionPanel, BorderLayout.EAST);

        previewArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        previewArea.setBackground(new Color(250, 250, 252));
        previewArea.setEditable(false);
        JScrollPane scrollPane = new JScrollPane(previewArea);
        scrollPane.setBorder(BorderFactory.createLineBorder(new Color(180, 200, 220)));

        JPanel centerPanel = new JPanel(new BorderLayout(0, 4));
        centerPanel.add(previewHeaderPanel, BorderLayout.NORTH);
        centerPanel.add(scrollPane, BorderLayout.CENTER);

        // 4. Status Bar (South)
        JPanel statusPanel = new JPanel(new BorderLayout());
        statusPanel.setBorder(new EmptyBorder(4, 6, 4, 6));
        statusLabel.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        statusPanel.add(statusLabel, BorderLayout.WEST);

        // Assemble Window
        JPanel topContainer = new JPanel(new BorderLayout());
        topContainer.add(headerPanel, BorderLayout.NORTH);
        topContainer.add(topConfigPanel, BorderLayout.CENTER);
        mainPanel.add(topContainer, BorderLayout.NORTH);
        mainPanel.add(centerPanel, BorderLayout.CENTER);
        mainPanel.add(statusPanel, BorderLayout.SOUTH);

        setContentPane(mainPanel);

        // Setup Events & Listeners
        fileRadio.addActionListener(e -> {
            fileRow.setVisible(true);
            urlRow.setVisible(false);
            onFileChanged();
        });
        urlRadio.addActionListener(e -> {
            fileRow.setVisible(false);
            urlRow.setVisible(true);
            detectionBadge.setText("Google Sheets URL Mode");
            switchOptionsCard("GENERIC", "Google Sheet ImpEx Mapping");
        });

        browseBtn.addActionListener(e -> chooseFile());

        filePathField.addActionListener(e -> onFileChanged());
        filePathField.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { onFileChanged(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { onFileChanged(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { onFileChanged(); }
        });

        convertBtn.addActionListener(e -> doConvert());
        copyBtn.addActionListener(e -> doCopy());
        saveBtn.addActionListener(e -> doSave());

        // Drag & drop support
        setDropTarget(new DropTarget() {
            public synchronized void drop(DropTargetDropEvent evt) {
                try {
                    evt.acceptDrop(DnDConstants.ACTION_COPY);
                    @SuppressWarnings("unchecked")
                    List<File> droppedFiles = (List<File>) evt.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (droppedFiles != null && !droppedFiles.isEmpty()) {
                        filePathField.setText(droppedFiles.get(0).getAbsolutePath());
                        fileRadio.setSelected(true);
                        fileRow.setVisible(true);
                        urlRow.setVisible(false);
                        onFileChanged();
                    }
                } catch (Exception ex) {
                    ex.printStackTrace();
                }
            }
        });
    }

    private void initDynamicOptions() {
        // Card 1: Order Export Options
        JPanel orderCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        orderCard.add(new JLabel("Select Order ID:"));
        orderIdCombo.setPreferredSize(new Dimension(200, 26));
        orderCard.add(orderIdCombo);
        orderCard.add(includeCartCheck);

        // Card 2: Cart Export Options
        JPanel cartCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        cartCard.add(new JLabel("Select Cart Code:"));
        cartCodeCombo.setPreferredSize(new Dimension(200, 26));
        cartCard.add(cartCodeCombo);

        // Card 3: Generic Options
        JPanel genericCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        genericCard.add(new JLabel("Item Type:"));
        genericCard.add(itemTypeField);
        genericCard.add(new JLabel("Mode:"));
        genericCard.add(modeCombo);
        genericCard.add(new JLabel("Unique Column:"));
        genericCard.add(uniqueColsField);

        // Empty card
        JPanel emptyCard = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 6));
        emptyCard.add(new JLabel("Select a file above to configure options"));

        optionsCardPanel.add(emptyCard, "NONE");
        optionsCardPanel.add(orderCard, "ORDER");
        optionsCardPanel.add(cartCard, "CART");
        optionsCardPanel.add(genericCard, "GENERIC");

        // Action buttons row: Convert button placed on upper right next to selection
        JPanel actionsPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        actionsPanel.add(convertBtn);

        optionsAndActionsPanel.setBorder(optionsBorder);
        optionsAndActionsPanel.add(optionsCardPanel, BorderLayout.CENTER);
        optionsAndActionsPanel.add(actionsPanel, BorderLayout.EAST);
    }

    private void switchOptionsCard(String cardName, String title) {
        CardLayout cl = (CardLayout) optionsCardPanel.getLayout();
        cl.show(optionsCardPanel, cardName);
        optionsBorder.setTitle(title);
        optionsAndActionsPanel.repaint();
    }

    private void chooseFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setCurrentDirectory(new File("."));
        chooser.setFileFilter(new FileNameExtensionFilter("Excel & CSV Files (*.xlsx, *.csv)", "xlsx", "xlsm", "csv"));
        int res = chooser.showOpenDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            filePathField.setText(f.getAbsolutePath());
            onFileChanged();
        }
    }

    private void onFileChanged() {
        String path = filePathField.getText().trim().replaceAll("^[\"']|[\"']$", "");
        if (path.isEmpty()) {
            detectionBadge.setText("Please select an input file or URL");
            switchOptionsCard("NONE", "Options");
            currentDetectedType = "NONE";
            return;
        }

        File f = new File(path);
        if (!f.exists()) {
            detectionBadge.setText("[Notice] File does not exist");
            switchOptionsCard("NONE", "Options");
            currentDetectedType = "NONE";
            return;
        }

        currentSelectedFile = f;
        String name = f.getName().toLowerCase();

        if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
            try {
                Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(f);
                XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
                if (primSheet != null) {
                    boolean hasOrderId = primSheet.getHeaders().stream()
                            .anyMatch(h -> h.equalsIgnoreCase("orderId") || h.equalsIgnoreCase("order_id"));

                    if (hasOrderId) {
                        currentDetectedType = "ORDER";
                        detectionBadge.setText("Hybris Order Export Detected: " + f.getName());
                        detectionBadge.setForeground(new Color(15, 120, 50));

                        // Populate Order IDs
                        orderIdCombo.removeAllItems();
                        orderIdCombo.addItem("[ALL Orders in File]");
                        int oCol = findCol(primSheet.getHeaders(), "orderId", "order_id");
                        for (List<String> row : primSheet.getRows()) {
                            if (oCol < row.size()) {
                                String oVal = row.get(oCol);
                                if (!oVal.isEmpty()) {
                                    boolean exists = false;
                                    for (int i = 0; i < orderIdCombo.getItemCount(); i++) {
                                        if (oVal.equals(orderIdCombo.getItemAt(i))) exists = true;
                                    }
                                    if (!exists) orderIdCombo.addItem(oVal);
                                }
                            }
                        }
                        switchOptionsCard("ORDER", "Hybris Order Export Options");
                        return;
                    } else {
                        currentDetectedType = "CART";
                        detectionBadge.setText("Hybris Abandoned Cart Export Detected: " + f.getName());
                        detectionBadge.setForeground(new Color(15, 120, 50));

                        // Populate Cart Codes
                        cartCodeCombo.removeAllItems();
                        cartCodeCombo.addItem("[ALL Carts in File]");
                        int cCol = findCol(primSheet.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
                        for (List<String> row : primSheet.getRows()) {
                            if (cCol < row.size()) {
                                String cVal = row.get(cCol);
                                if (!cVal.isEmpty()) {
                                    boolean exists = false;
                                    for (int i = 0; i < cartCodeCombo.getItemCount(); i++) {
                                        if (cVal.equals(cartCodeCombo.getItemAt(i))) exists = true;
                                    }
                                    if (!exists) cartCodeCombo.addItem(cVal);
                                }
                            }
                        }
                        switchOptionsCard("CART", "Hybris Cart Export Options");
                        return;
                    }
                }
            } catch (Exception ex) {
                // fall through to generic
            }
        }

        // Generic Excel or CSV
        currentDetectedType = "GENERIC";
        detectionBadge.setText("Generic Table File Detected: " + f.getName());
        detectionBadge.setForeground(new Color(30, 80, 150));
        itemTypeField.setText(HybrisImpexConverter.inferItemType(f.getName()));
        switchOptionsCard("GENERIC", "ImpEx Table Mapping");
    }

    private void doConvert() {
        String input = fileRadio.isSelected()
                ? filePathField.getText().trim().replaceAll("^[\"']|[\"']$", "")
                : urlField.getText().trim();

        if (input.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Please specify an input file or URL.", "Input Required", JOptionPane.WARNING_MESSAGE);
            return;
        }

        statusLabel.setText("Converting...");
        setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));

        SwingUtilities.invokeLater(() -> {
            try {
                String targetOrderId = null;
                String targetCartCode = null;

                if ("ORDER".equals(currentDetectedType)) {
                    String selected = (String) orderIdCombo.getSelectedItem();
                    if (selected != null && !selected.startsWith("[")) {
                        targetOrderId = selected;
                    }
                } else if ("CART".equals(currentDetectedType)) {
                    String selected = (String) cartCodeCombo.getSelectedItem();
                    if (selected != null && !selected.startsWith("[")) {
                        targetCartCode = selected;
                    }
                }

                Set<String> uniqueCols = new HashSet<>();
                String uText = uniqueColsField.getText().trim();
                if (!uText.isEmpty()) {
                    for (String u : uText.split(",")) {
                        if (!u.trim().isEmpty()) uniqueCols.add(u.trim());
                    }
                }

                String impexResult = HybrisImpexConverter.process(
                        input,
                        itemTypeField.getText().trim(),
                        (String) modeCombo.getSelectedItem(),
                        null,
                        targetOrderId,
                        targetCartCode,
                        uniqueCols,
                        Collections.emptySet(),
                        Collections.emptyList(),
                        null,
                        includeCartCheck.isSelected()
                );

                previewArea.setText(impexResult);
                previewArea.setCaretPosition(0);
                copyBtn.setEnabled(true);
                saveBtn.setEnabled(true);

                int lineCount = impexResult.split("\n").length;
                statusLabel.setText("Converted successfully! Total lines: " + lineCount + ". Ready to copy or save.");

            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Conversion failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
                statusLabel.setText("Conversion failed: " + ex.getMessage());
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
        });
    }

    private void doCopy() {
        String text = previewArea.getText();
        if (text.isEmpty()) return;

        boolean success = HybrisImpexConverter.copyToClipboard(text);
        if (success) {
            statusLabel.setText("Copied to Clipboard! Ready to paste into HAC Console (Console -> ImpEx Import).");
            JOptionPane.showMessageDialog(this, "ImpEx copied to Clipboard!\nSwitch to HAC (Console -> ImpEx Import) and press Ctrl+V.", "Copied", JOptionPane.INFORMATION_MESSAGE);
        } else {
            statusLabel.setText("Failed to copy to clipboard.");
        }
    }

    private void doSave() {
        String text = previewArea.getText();
        if (text.isEmpty()) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File("import.impex"));
        chooser.setFileFilter(new FileNameExtensionFilter("ImpEx Files (*.impex)", "impex"));
        int res = chooser.showSaveDialog(this);
        if (res == JFileChooser.APPROVE_OPTION) {
            try {
                File target = chooser.getSelectedFile();
                if (!target.getName().toLowerCase().endsWith(".impex")) {
                    target = new File(target.getAbsolutePath() + ".impex");
                }
                Files.writeString(target.toPath(), text);
                statusLabel.setText("Saved to: " + target.getAbsolutePath());
                JOptionPane.showMessageDialog(this, "File successfully saved to:\n" + target.getAbsolutePath(), "Saved", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Failed to save file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static int findCol(List<String> headers, String... candidates) {
        for (String c : candidates) {
            String normC = c.replaceAll("\\s+", "").toLowerCase();
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).replaceAll("\\s+", "").toLowerCase();
                if (h.equals(normC)) return i;
            }
        }
        return -1;
    }

    public static void main(String[] args) {
        if (args != null && args.length > 0) {
            // CLI mode
            HybrisImpexConverter.main(args);
        } else {
            // UI mode
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}

            SwingUtilities.invokeLater(() -> {
                ImpexConverterUI ui = new ImpexConverterUI();
                ui.setVisible(true);
            });
        }
    }
}
