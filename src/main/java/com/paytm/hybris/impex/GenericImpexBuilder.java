package com.paytm.hybris.impex;

import java.util.*;

/**
 * Builds generic ImpEx from any CSV, Excel, or Google Sheet table data.
 */
public class GenericImpexBuilder {

    public static String build(
            List<String> headers,
            List<List<String>> rows,
            String itemType,
            String mode,
            Set<String> uniqueColumns,
            Set<String> excludeColumns,
            List<String> macros,
            Integer batchSize
    ) {
        if (headers == null || headers.isEmpty()) {
            throw new IllegalArgumentException("Header list is empty.");
        }
        if (itemType == null || itemType.trim().isEmpty()) {
            itemType = "Item";
        }
        if (mode == null || mode.trim().isEmpty()) {
            mode = "INSERT_UPDATE";
        }

        Set<String> normExcludes = new HashSet<>();
        if (excludeColumns != null) {
            for (String e : excludeColumns) {
                normExcludes.add(e.trim().toLowerCase());
            }
        }
        normExcludes.add("cartid"); // always exclude internal tracking ID

        Set<String> normUniques = new HashSet<>();
        if (uniqueColumns != null) {
            for (String u : uniqueColumns) {
                normUniques.add(u.trim().toLowerCase());
            }
        }

        List<Integer> validIndices = new ArrayList<>();
        List<String> cleanHeaders = new ArrayList<>();

        for (int i = 0; i < headers.size(); i++) {
            String h = headers.get(i).trim();
            if (h.isEmpty()) continue;

            String baseName = h.split("[(\\[]")[0].trim().toLowerCase();
            if (normExcludes.contains(baseName) || normExcludes.contains(h.toLowerCase())) {
                continue;
            }

            if ((normUniques.contains(baseName) || normUniques.contains(h.toLowerCase())) && !h.toLowerCase().contains("unique")) {
                h = h + "[unique=true]";
            }

            validIndices.add(i);
            cleanHeaders.add(h);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# ========================================================================\n");
        sb.append("# SAP Commerce (Hybris) ImpEx Script\n");
        sb.append("# Generated for HAC Console (Console -> ImpEx Import)\n");
        sb.append("# Target Type: ").append(itemType).append(" | Mode: ").append(mode).append("\n");
        sb.append("# ========================================================================\n\n");

        if (macros != null && !macros.isEmpty()) {
            sb.append("# --- Macros ---\n");
            for (String m : macros) {
                String cleanM = m.trim();
                if (!cleanM.startsWith("$")) {
                    cleanM = "$" + cleanM;
                }
                sb.append(cleanM).append("\n");
            }
            sb.append("\n");
        }

        String headerLine = mode + " " + itemType + ";" + String.join(";", cleanHeaders);
        sb.append(headerLine).append("\n");

        int rowCount = rows.size();
        for (int r = 0; r < rowCount; r++) {
            List<String> row = rows.get(r);
            List<String> cells = new ArrayList<>();
            for (int colIdx : validIndices) {
                String val = (colIdx < row.size()) ? row.get(colIdx) : "";
                cells.add(ImpexFormatter.formatCell(val));
            }
            sb.append(";").append(String.join(";", cells)).append("\n");

            if (batchSize != null && batchSize > 0 && (r + 1) % batchSize == 0 && (r + 1) < rowCount) {
                sb.append("\n# --- Batch break (").append(r + 1).append("/").append(rowCount).append(") ---\n");
                sb.append(headerLine).append("\n");
            }
        }

        return sb.toString();
    }
}
