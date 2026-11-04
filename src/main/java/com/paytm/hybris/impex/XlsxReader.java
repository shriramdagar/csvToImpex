package com.paytm.hybris.impex;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Fast, zero-dependency OpenXML (.xlsx) parser using standard Java ZipFile and DOM parser.
 */
public class XlsxReader {

    public static class SheetData {
        private final String name;
        private final List<String> headers = new ArrayList<>();
        private final List<List<String>> rows = new ArrayList<>();

        public SheetData(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public List<String> getHeaders() {
            return headers;
        }

        public List<List<String>> getRows() {
            return rows;
        }

        public int getRowCount() {
            return rows.size();
        }
    }

    public static Map<String, SheetData> read(File file) throws Exception {
        try (ZipFile zipFile = new ZipFile(file)) {
            return readFromZip(zipFile);
        }
    }

    public static Map<String, SheetData> read(byte[] bytes) throws Exception {
        File tempFile = File.createTempFile("xlsx_read_", ".tmp");
        try {
            java.nio.file.Files.write(tempFile.toPath(), bytes);
            try (ZipFile zipFile = new ZipFile(tempFile)) {
                return readFromZip(zipFile);
            }
        } finally {
            tempFile.delete();
        }
    }

    private static Map<String, SheetData> readFromZip(ZipFile zipFile) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(false);
        DocumentBuilder db = dbf.newDocumentBuilder();

        // 1. Read shared strings
        List<String> sharedStrings = new ArrayList<>();
        ZipEntry sstEntry = zipFile.getEntry("xl/sharedStrings.xml");
        if (sstEntry != null) {
            try (InputStream is = zipFile.getInputStream(sstEntry)) {
                Document doc = db.parse(is);
                NodeList siNodes = doc.getElementsByTagName("si");
                for (int i = 0; i < siNodes.getLength(); i++) {
                    Element si = (Element) siNodes.item(i);
                    StringBuilder textBuf = new StringBuilder();
                    NodeList tNodes = si.getElementsByTagName("t");
                    for (int j = 0; j < tNodes.getLength(); j++) {
                        textBuf.append(tNodes.item(j).getTextContent());
                    }
                    sharedStrings.add(textBuf.toString());
                }
            }
        }

        // 2. Read workbook relationships to find sheet paths
        Map<String, String> relIdToPath = new HashMap<>();
        ZipEntry relsEntry = zipFile.getEntry("xl/_rels/workbook.xml.rels");
        if (relsEntry != null) {
            try (InputStream is = zipFile.getInputStream(relsEntry)) {
                Document doc = db.parse(is);
                NodeList relNodes = doc.getElementsByTagName("Relationship");
                for (int i = 0; i < relNodes.getLength(); i++) {
                    Element rel = (Element) relNodes.item(i);
                    String id = rel.getAttribute("Id");
                    String target = rel.getAttribute("Target");
                    if (!target.startsWith("xl/")) {
                        target = "xl/" + target;
                    }
                    relIdToPath.put(id, target);
                }
            }
        }

        // 3. Read workbook.xml for sheet names
        Map<String, SheetData> result = new LinkedHashMap<>();
        ZipEntry wbEntry = zipFile.getEntry("xl/workbook.xml");
        if (wbEntry == null) {
            return result;
        }

        List<SheetDef> sheetDefs = new ArrayList<>();
        try (InputStream is = zipFile.getInputStream(wbEntry)) {
            Document doc = db.parse(is);
            NodeList sheetNodes = doc.getElementsByTagName("sheet");
            for (int i = 0; i < sheetNodes.getLength(); i++) {
                Element s = (Element) sheetNodes.item(i);
                String name = s.getAttribute("name");
                String sheetId = s.getAttribute("sheetId");
                String rId = s.getAttribute("r:id");
                if (rId.isEmpty()) {
                    rId = s.getAttribute("id");
                }
                String targetPath = relIdToPath.get(rId);
                if (targetPath == null) {
                    targetPath = "xl/worksheets/sheet" + sheetId + ".xml";
                }
                sheetDefs.add(new SheetDef(name, targetPath));
            }
        }

        // 4. Parse each sheet
        for (SheetDef sd : sheetDefs) {
            ZipEntry wsEntry = zipFile.getEntry(sd.path);
            if (wsEntry == null) {
                continue;
            }

            SheetData sheetData = new SheetData(sd.name);
            try (InputStream is = zipFile.getInputStream(wsEntry)) {
                Document doc = db.parse(is);
                NodeList rowNodes = doc.getElementsByTagName("row");
                boolean isFirstRow = true;

                for (int r = 0; r < rowNodes.getLength(); r++) {
                    Element rowEl = (Element) rowNodes.item(r);
                    NodeList cellNodes = rowEl.getElementsByTagName("c");
                    if (cellNodes.getLength() == 0) continue;

                    Map<Integer, String> colToVal = new TreeMap<>();
                    int maxCol = -1;

                    for (int c = 0; c < cellNodes.getLength(); c++) {
                        Element cellEl = (Element) cellNodes.item(c);
                        String ref = cellEl.getAttribute("r");
                        int colIndex = c;
                        if (!ref.isEmpty()) {
                            String colLetters = ref.replaceAll("[^A-Z]", "");
                            colIndex = columnNameToIndex(colLetters);
                        }
                        if (colIndex > maxCol) {
                            maxCol = colIndex;
                        }

                        String type = cellEl.getAttribute("t");
                        String value = "";

                        if ("s".equals(type)) {
                            NodeList vList = cellEl.getElementsByTagName("v");
                            if (vList.getLength() > 0) {
                                int sIdx = Integer.parseInt(vList.item(0).getTextContent().trim());
                                if (sIdx >= 0 && sIdx < sharedStrings.size()) {
                                    value = sharedStrings.get(sIdx);
                                }
                            }
                        } else if ("inlineStr".equals(type)) {
                            NodeList tList = cellEl.getElementsByTagName("t");
                            if (tList.getLength() > 0) {
                                value = tList.item(0).getTextContent();
                            }
                        } else if ("b".equals(type)) {
                            NodeList vList = cellEl.getElementsByTagName("v");
                            if (vList.getLength() > 0) {
                                String b = vList.item(0).getTextContent().trim();
                                value = "1".equals(b) ? "TRUE" : "FALSE";
                            }
                        } else {
                            NodeList vList = cellEl.getElementsByTagName("v");
                            if (vList.getLength() > 0) {
                                value = vList.item(0).getTextContent().trim();
                            }
                        }

                        colToVal.put(colIndex, value);
                    }

                    if (maxCol >= 0) {
                        List<String> rowValues = new ArrayList<>(maxCol + 1);
                        for (int i = 0; i <= maxCol; i++) {
                            rowValues.add(colToVal.getOrDefault(i, ""));
                        }

                        // Check if row has any non-empty content
                        boolean hasData = rowValues.stream().anyMatch(v -> !v.trim().isEmpty());
                        if (hasData) {
                            if (isFirstRow) {
                                sheetData.headers.addAll(rowValues);
                                isFirstRow = false;
                            } else {
                                sheetData.rows.add(rowValues);
                            }
                        }
                    }
                }
            }

            // Remove trailing empty header columns
            while (!sheetData.headers.isEmpty() && sheetData.headers.get(sheetData.headers.size() - 1).trim().isEmpty()) {
                sheetData.headers.remove(sheetData.headers.size() - 1);
            }

            result.put(sd.name, sheetData);
        }

        return result;
    }

    public static int columnNameToIndex(String colName) {
        int index = 0;
        for (int i = 0; i < colName.length(); i++) {
            index = index * 26 + (colName.charAt(i) - 'A' + 1);
        }
        return index - 1;
    }

    private static class SheetDef {
        final String name;
        final String path;

        SheetDef(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }
}
