package com.paytm.hybris.impex;

/**
 * Utility for formatting and escaping values according to Hybris ImpEx specification.
 */
public class ImpexFormatter {

    public static String formatCell(Object val) {
        if (val == null) {
            return "";
        }

        String s = val.toString().trim();
        if (s.isEmpty()) {
            return "";
        }

        String sUpper = s.toUpperCase();
        if ("TRUE".equals(sUpper) || "FALSE".equals(sUpper)) {
            return sUpper;
        }

        // ImpEx Escaping:
        // Fields containing semicolons, double quotes, or newlines must be enclosed in quotes
        // Inner double quotes are escaped by doubling ("").
        boolean needsQuotes = s.contains(";") || s.contains("\"") || s.contains("\n") || s.contains("\r");

        if (s.contains("\"")) {
            s = s.replace("\"", "\"\"");
            needsQuotes = true;
        }

        if (needsQuotes) {
            return "\"" + s + "\"";
        }

        return s;
    }
}
