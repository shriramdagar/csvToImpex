package com.paytm.hybris.impex;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.*;

/**
 * Main application for converting CSV, Excel (.xlsx/.xls), and Google Sheets to Hybris ImpEx for HAC.
 */
public class HybrisImpexConverter {

    public static void main(String[] args) {
        if (args.length == 0) {
            runInteractive();
            return;
        }

        String inputPath = null;
        String outputPath = null;
        String itemType = null;
        String mode = "INSERT_UPDATE";
        String sheetName = null;
        String targetOrderId = null;
        String targetCartCode = null;
        boolean copyToClipboard = false;
        boolean toStdout = false;
        boolean includeUnderlyingCart = true;
        boolean runOnHac = false;
        String hacUrl = null;
        String hacUser = null;
        String hacPass = null;
        String hacCookie = null;
        String hacCsrf = null;
        Set<String> uniqueCols = new HashSet<>();
        Set<String> excludeCols = new HashSet<>();
        List<String> macros = new ArrayList<>();
        Integer batchSize = null;

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];
            switch (arg) {
                case "--run-on-hac":
                case "--hac-run":
                    runOnHac = true;
                    break;
                case "--hac-url":
                    if (i + 1 < args.length) hacUrl = args[++i];
                    break;
                case "--hac-user":
                    if (i + 1 < args.length) hacUser = args[++i];
                    break;
                case "--hac-pass":
                    if (i + 1 < args.length) hacPass = args[++i];
                    break;
                case "--hac-cookie":
                    if (i + 1 < args.length) hacCookie = args[++i];
                    break;
                case "--hac-csrf":
                    if (i + 1 < args.length) hacCsrf = args[++i];
                    break;
                case "-o":
                case "--output":
                    if (i + 1 < args.length) outputPath = args[++i];
                    break;
                case "-t":
                case "--type":
                    if (i + 1 < args.length) itemType = args[++i];
                    break;
                case "-m":
                case "--mode":
                    if (i + 1 < args.length) mode = args[++i];
                    break;
                case "-s":
                case "--sheet":
                    if (i + 1 < args.length) sheetName = args[++i];
                    break;
                case "--orderId":
                case "--order-id":
                    if (i + 1 < args.length) targetOrderId = args[++i];
                    break;
                case "--cartCode":
                case "--cart-code":
                    if (i + 1 < args.length) targetCartCode = args[++i];
                    break;
                case "-u":
                case "--unique":
                    if (i + 1 < args.length) {
                        for (String u : args[++i].split(",")) {
                            if (!u.trim().isEmpty()) uniqueCols.add(u.trim());
                        }
                    }
                    break;
                case "-e":
                case "--exclude":
                    if (i + 1 < args.length) {
                        for (String e : args[++i].split(",")) {
                            if (!e.trim().isEmpty()) excludeCols.add(e.trim());
                        }
                    }
                    break;
                case "--macro":
                    if (i + 1 < args.length) macros.add(args[++i]);
                    break;
                case "-b":
                case "--batch-size":
                    if (i + 1 < args.length) batchSize = Integer.parseInt(args[++i]);
                    break;
                case "-c":
                case "--clipboard":
                    copyToClipboard = true;
                    break;
                case "--stdout":
                    toStdout = true;
                    break;
                case "--no-underlying-cart":
                    includeUnderlyingCart = false;
                    break;
                case "-h":
                case "--help":
                    printHelp();
                    return;
                default:
                    if (!arg.startsWith("-") && inputPath == null) {
                        inputPath = arg;
                    }
                    break;
            }
        }

        if (inputPath == null) {
            System.err.println("[ERROR] Input file or URL is required.");
            printHelp();
            System.exit(1);
        }

        try {
            String impexResult = process(
                    inputPath,
                    itemType,
                    mode,
                    sheetName,
                    targetOrderId,
                    targetCartCode,
                    uniqueCols,
                    excludeCols,
                    macros,
                    batchSize,
                    includeUnderlyingCart
            );

            if (toStdout) {
                System.out.println(impexResult);
            }

            if (outputPath == null && !toStdout) {
                if (!GoogleSheetDownloader.isGoogleSheetUrl(inputPath)) {
                    outputPath = inputPath.replaceAll("\\.[^.]+$", "") + ".impex";
                } else {
                    outputPath = "output.impex";
                }
            }

            if (outputPath != null) {
                Files.writeString(new File(outputPath).toPath(), impexResult);
                System.out.println("[SUCCESS] ImpEx successfully written to: " + outputPath);
            }

            if (copyToClipboard) {
                boolean copied = copyToClipboard(impexResult);
                if (copied) {
                    System.out.println("[SUCCESS] ImpEx copied to Clipboard! Ready to paste into HAC (Console -> ImpEx Import).");
                }
            }

            if (runOnHac) {
                com.paytm.hybris.impex.hac.HacConfig config = com.paytm.hybris.impex.hac.HacConfig.load();
                if (hacUrl != null) config.setImportUrl(hacUrl);
                if (hacUser != null) config.setUsername(hacUser);
                if (hacPass != null) config.setPassword(hacPass);
                if (hacCookie != null) config.setCookie(hacCookie);
                if (hacCsrf != null) config.setCsrfToken(hacCsrf);

                System.out.println("\n[HAC] Verifying HAC connection & credentials before import...");
                com.paytm.hybris.impex.hac.HacClient client = new com.paytm.hybris.impex.hac.HacClient();
                com.paytm.hybris.impex.hac.HacTestResult testRes = client.testConnection(config, msg -> System.out.println("  " + msg));
                if (!testRes.isSuccess()) {
                    System.err.println("[HAC ERROR] HAC connection verification failed: " + testRes.getMessage());
                    System.exit(1);
                }

                System.out.println("[HAC] Connected successfully! Running ImpEx on HAC...");
                com.paytm.hybris.impex.hac.HacImportResult importRes = client.importImpex(config, impexResult, msg -> System.out.println("  " + msg));
                if (importRes.isSuccess()) {
                    System.out.println("[HAC SUCCESS] " + importRes.getSummary());
                } else {
                    System.err.println("[HAC FAILED] " + importRes.getSummary());
                    if (importRes.getErrorDetails() != null) {
                        System.err.println("Details: " + importRes.getErrorDetails());
                    }
                    System.exit(1);
                }
            }

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    public static String process(
            String inputPath,
            String itemType,
            String mode,
            String sheetName,
            String targetOrderId,
            String targetCartCode,
            Set<String> uniqueCols,
            Set<String> excludeCols,
            List<String> macros,
            Integer batchSize,
            boolean includeUnderlyingCart
    ) throws Exception {

        byte[] rawBytes = null;
        File inputFile = null;
        boolean isGoogleSheet = GoogleSheetDownloader.isGoogleSheetUrl(inputPath);

        if (isGoogleSheet) {
            System.out.println("[INFO] Downloading Google Sheet export...");
            try {
                rawBytes = GoogleSheetDownloader.download(inputPath, true);
            } catch (Exception ex) {
                System.out.println("[WARN] XLSX download failed (" + ex.getMessage() + "), trying CSV export...");
                rawBytes = GoogleSheetDownloader.download(inputPath, false);
            }
        } else {
            inputFile = new File(inputPath);
            if (!inputFile.exists()) {
                throw new IllegalArgumentException("File not found: " + inputPath);
            }
        }

        // Determine if Excel
        boolean isExcel = false;
        if (inputFile != null) {
            String name = inputFile.getName().toLowerCase();
            if (name.endsWith(".xlsx") || name.endsWith(".xlsm")) {
                isExcel = true;
            }
        } else if (rawBytes != null && rawBytes.length >= 4) {
            // Check PK\x03\x04 zip header
            if (rawBytes[0] == 0x50 && rawBytes[1] == 0x4B && rawBytes[2] == 0x03 && rawBytes[3] == 0x04) {
                isExcel = true;
            }
        }

        if (isExcel) {
            Map<String, XlsxReader.SheetData> sheets = (inputFile != null)
                    ? XlsxReader.read(inputFile)
                    : XlsxReader.read(rawBytes);

            String fileName = (inputFile != null) ? inputFile.getName() : "GoogleSheet.xlsx";

            // Check if this is an Order export
            XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
            if (primSheet != null) {
                boolean hasOrderId = primSheet.getHeaders().stream()
                        .anyMatch(h -> h.equalsIgnoreCase("orderId") || h.equalsIgnoreCase("order_id"));

                if (hasOrderId) {
                    System.out.println("[INFO] Detected Hybris Order Export. Generating Order ImpEx prototype...");
                    return OrderImpexBuilder.build(sheets, fileName, targetOrderId, includeUnderlyingCart);
                } else {
                    System.out.println("[INFO] Detected Hybris Abandoned Cart Export. Generating Cart ImpEx prototype...");
                    return CartImpexBuilder.build(sheets, fileName, targetCartCode);
                }
            }

            // If a specific sheet was requested or generic Excel
            String targetSheet = (sheetName != null) ? sheetName : sheets.keySet().iterator().next();
            XlsxReader.SheetData sData = sheets.get(targetSheet);
            if (sData == null) {
                throw new IllegalArgumentException("Sheet not found: " + targetSheet);
            }

            String effectiveType = (itemType != null) ? itemType : inferItemType(targetSheet);
            return GenericImpexBuilder.build(
                    sData.getHeaders(),
                    sData.getRows(),
                    effectiveType,
                    mode,
                    uniqueCols,
                    excludeCols,
                    macros,
                    batchSize
            );

        } else {
            // CSV
            CsvReader.CsvData csvData = (inputFile != null)
                    ? CsvReader.read(inputFile)
                    : CsvReader.parse(new String(rawBytes, java.nio.charset.StandardCharsets.UTF_8));

            String effectiveType = (itemType != null) ? itemType : ((inputFile != null) ? inferItemType(inputFile.getName()) : "Item");
            return GenericImpexBuilder.build(
                    csvData.headers,
                    csvData.rows,
                    effectiveType,
                    mode,
                    uniqueCols,
                    excludeCols,
                    macros,
                    batchSize
            );
        }
    }

    public static String inferItemType(String name) {
        String clean = name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase();
        if (clean.contains("prerequsers") || clean.contains("customer")) return "Customer";
        if (clean.contains("prereqaddresses") || clean.contains("address")) return "Address";
        if (clean.contains("cartentries") || clean.contains("cartentry")) return "CartEntry";
        if (clean.contains("cart")) return "Cart";
        if (clean.contains("orderentries") || clean.contains("orderentry")) return "OrderEntry";
        if (clean.contains("order")) return "Order";
        if (clean.contains("product")) return "Product";
        if (clean.contains("price")) return "PriceRow";
        if (clean.contains("stock")) return "StockLevel";
        if (clean.contains("unit")) return "Unit";
        return "Item";
    }

    public static boolean copyToClipboard(String text) {
        try {
            // First try Windows clip command
            if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Process proc = Runtime.getRuntime().exec(new String[]{"clip"});
                try (OutputStream os = proc.getOutputStream()) {
                    os.write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                }
                return proc.waitFor() == 0;
            }
            // Fallback to AWT
            StringSelection selection = new StringSelection(text);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, selection);
            return true;
        } catch (Exception e) {
            System.err.println("[WARN] Clipboard copy failed: " + e.getMessage());
            return false;
        }
    }

    public static void runInteractive() {
        Scanner sc = new Scanner(System.in);
        System.out.println("========================================================================");
        System.out.println("  SAP Commerce (Hybris) ImpEx Converter (Java 25)");
        System.out.println("========================================================================");
        System.out.println("Supports: Hybris Cart & Order Exports, Generic CSV/Excel, Google Sheets\n");

        System.out.print("Enter file path or Google Sheet URL: ");
        String input = sc.nextLine().trim().replaceAll("^[\"']|[\"']$", "");
        if (input.isEmpty()) {
            System.out.println("[ERROR] Input path is required.");
            return;
        }

        try {
            File f = new File(input);
            String targetOrderId = null;
            String targetCartCode = null;

            if (f.exists() && f.getName().toLowerCase().endsWith(".xlsx")) {
                Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(f);
                XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
                if (primSheet != null) {
                    boolean hasOrderId = primSheet.getHeaders().stream()
                            .anyMatch(h -> h.equalsIgnoreCase("orderId") || h.equalsIgnoreCase("order_id"));
                    if (hasOrderId) {
                        System.out.println("\n[INFO] Detected Hybris Order Export file!");
                        int oCol = findCol(primSheet.getHeaders(), "orderId", "order_id");
                        List<String> orderIds = new ArrayList<>();
                        for (List<String> row : primSheet.getRows()) {
                            if (oCol < row.size() && !row.get(oCol).isEmpty() && !orderIds.contains(row.get(oCol))) {
                                orderIds.add(row.get(oCol));
                            }
                        }
                        System.out.println("Available Order IDs in file: " + orderIds);
                        System.out.print("Enter Order ID to convert (or press Enter for ALL): ");
                        String oChoice = sc.nextLine().trim();
                        if (!oChoice.isEmpty()) targetOrderId = oChoice;
                    } else {
                        System.out.println("\n[INFO] Detected Hybris Abandoned Cart Export file!");
                    }
                }
            }

            System.out.print("Output file path [default: output.impex]: ");
            String out = sc.nextLine().trim().replaceAll("^[\"']|[\"']$", "");
            if (out.isEmpty()) out = "output.impex";

            System.out.print("Copy result to Windows Clipboard for HAC Console? (y/n) [y]: ");
            String copyChoice = sc.nextLine().trim().toLowerCase();
            boolean copy = !"n".equals(copyChoice);

            System.out.println("\nGenerating ImpEx...");
            String impex = process(
                    input,
                    null,
                    "INSERT_UPDATE",
                    null,
                    targetOrderId,
                    targetCartCode,
                    Collections.emptySet(),
                    Collections.emptySet(),
                    Collections.emptyList(),
                    null,
                    true
            );

            Files.writeString(new File(out).toPath(), impex);
            System.out.println("[SUCCESS] ImpEx successfully written to: " + out);

            if (copy) {
                if (copyToClipboard(impex)) {
                    System.out.println("[SUCCESS] Copied to Clipboard! You can now paste directly into HAC (Console -> ImpEx Import).");
                }
            }

            // Preview
            String[] lines = impex.split("\n");
            System.out.println("\n--- ImpEx Preview (First 30 lines) ---");
            for (int i = 0; i < Math.min(lines.length, 30); i++) {
                System.out.println(lines[i]);
            }
            if (lines.length > 30) {
                System.out.println("... and " + (lines.length - 30) + " more lines.");
            }

        } catch (Exception e) {
            System.err.println("[ERROR] " + e.getMessage());
            e.printStackTrace();
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

    public static void printHelp() {
        System.out.println("Hybris ImpEx Converter (Java 25)");
        System.out.println("Usage:");
        System.out.println("  java HybrisImpexConverter.java <input> [options]");
        System.out.println("Options:");
        System.out.println("  -o, --output <path>       Output .impex file path");
        System.out.println("  -t, --type <itemType>     Hybris ItemType (for generic files)");
        System.out.println("  -m, --mode <mode>         ImpEx mode: INSERT_UPDATE, INSERT, UPDATE, REMOVE");
        System.out.println("  -u, --unique <cols>       Comma-separated unique key columns");
        System.out.println("  -e, --exclude <cols>      Comma-separated columns to exclude");
        System.out.println("  -s, --sheet <name>        Sheet name (for Excel)");
        System.out.println("  --orderId <id>            Specific order ID to extract from order export");
        System.out.println("  --cartCode <code>         Specific cart code to extract from cart export");
        System.out.println("  -c, --clipboard           Copy generated ImpEx directly to OS clipboard");
        System.out.println("  --stdout                  Print ImpEx directly to stdout");
        System.out.println("  --no-underlying-cart      Omit section 5 underlying Cart in Order export");
        System.out.println("  --run-on-hac              Directly execute generated ImpEx on HAC");
        System.out.println("  --hac-url <url>           HAC Import URL (default: CCv2 or localhost)");
        System.out.println("  --hac-user <username>     HAC username");
        System.out.println("  --hac-pass <password>     HAC password");
        System.out.println("  --hac-cookie <cookie>     HAC Session Cookie (JSESSIONID=...; ROUTE=...)");
        System.out.println("  --hac-csrf <token>        HAC CSRF Token");
    }
}
