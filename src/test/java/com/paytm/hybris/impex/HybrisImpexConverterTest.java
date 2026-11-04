package com.paytm.hybris.impex;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HybrisImpexConverterTest {

    @Test
    public void testAbandonedCartExportMatchesPrototype() throws Exception {
        File cartFile = new File("abandoned_cart_hybris_export_2026-11-02_20-00_to_2026-11-03_19-59.xlsx");
        org.junit.jupiter.api.Assumptions.assumeTrue(cartFile.exists(), "Sample cart export file not found; skipping");

        Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(cartFile);
        assertNotNull(sheets.get("CartPrimitive"));
        assertNotNull(sheets.get("PrereqUsers"));
        assertNotNull(sheets.get("CartEntries"));

        String impex = CartImpexBuilder.build(sheets, cartFile.getName(), null);
        assertNotNull(impex);

        // Verify key prototype sections
        assertTrue(impex.contains("ImpEx: Improved Cart Import with Promotion Engine Objects"));
        assertTrue(impex.contains("1. MACROS & CATALOG CONFIGURATION"));
        assertTrue(impex.contains("$cartCode = 03112026160503"));
        assertTrue(impex.contains("$customerUid = CUST-20261103-160536-ae7e48ff4f00"));
        assertTrue(impex.contains("2. PREREQUISITES: CUSTOMER, ADDRESSES, RISKS, PRICING & COUPONS"));
        assertTrue(impex.contains("INSERT_UPDATE Customer; uid[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE Address; &cartAddr"));
        assertTrue(impex.contains("ADDR_$cartCode"));
        assertTrue(impex.contains("INSERT_UPDATE CreditScore; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]"));
        assertTrue(impex.contains("INSERT TotalPrice; &tpId"));
        assertTrue(impex.contains("3. CART HEADER"));
        assertTrue(impex.contains("INSERT_UPDATE Cart; code[unique = true]"));
        assertTrue(impex.contains("4. CART LINE ITEMS"));
        assertTrue(impex.contains("INSERT_UPDATE CartEntry; order(code)[unique = true]"));
        assertTrue(impex.contains("Mobile_Plan_Mix_More_PR_Test"));
    }

    @Test
    public void testOrderExportMatchesPrototype() throws Exception {
        File orderFile = new File("order_hybris_export_2026-11-02_20-00_to_2026-11-03_19-59.xlsx");
        org.junit.jupiter.api.Assumptions.assumeTrue(orderFile.exists(), "Sample order export file not found; skipping");

        Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(orderFile);
        assertNotNull(sheets.get("CartPrimitive"));
        assertNotNull(sheets.get("PrereqUsers"));
        assertNotNull(sheets.get("PrereqAddresses"));
        assertNotNull(sheets.get("CartEntries"));

        // Generate for specific order 20887047
        String impex = OrderImpexBuilder.build(sheets, orderFile.getName(), "20887047", true);
        assertNotNull(impex);

        // Verify prototype sections
        assertTrue(impex.contains("ImpEx: Order Import for Order ID: 20887047"));
        assertTrue(impex.contains("$orderCode = 20887047"));
        assertTrue(impex.contains("$cartCode = 03112026155417"));
        assertTrue(impex.contains("$customerUid = CUST-20261103-155501-12a3d21da9c0"));
        assertTrue(impex.contains("INSERT_UPDATE Customer; uid[unique = true]"));
        assertTrue(impex.contains("Hilda  Alvarez"));
        assertTrue(impex.contains("INSERT_UPDATE Address; &deliveryAddr"));
        assertTrue(impex.contains("INSERT_UPDATE Address; &billingAddr"));
        assertTrue(impex.contains("INSERT_UPDATE ZoneDeliveryMode; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE StandardPaymentMode; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE CreditCardPaymentInfo; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE Order; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE OrderEntry; order(code)[unique = true]"));
        assertTrue(impex.contains("5. OPTIONAL: UNDERLYING CART & CART ENTRIES"));
        assertTrue(impex.contains("INSERT_UPDATE Cart; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE CartEntry; order(code)[unique = true]"));
    }

    @Test
    public void testGenericCsvConversion() {
        String csv = "code,name,price\nPROD_1,Wireless Headphones,99.99\nPROD_2,\"Keyboard; Mechanical\",129.99\n";
        CsvReader.CsvData data = CsvReader.parse(csv);
        assertEquals(3, data.headers.size());
        assertEquals(2, data.rows.size());

        String impex = GenericImpexBuilder.build(
                data.headers,
                data.rows,
                "Product",
                "INSERT_UPDATE",
                Set.of("code"),
                Set.of("price"),
                List.of("lang=en"),
                null
        );

        assertTrue(impex.contains("$lang=en"));
        assertTrue(impex.contains("INSERT_UPDATE Product;code[unique=true];name"));
        assertFalse(impex.contains(";price"));
        assertTrue(impex.contains(";PROD_1;Wireless Headphones"));
        assertTrue(impex.contains(";PROD_2;\"Keyboard; Mechanical\""));
    }

    @Test
    public void testGoogleSheetUrlParsing() {
        String url = "https://docs.google.com/spreadsheets/d/1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms/edit#gid=0";
        assertEquals("1BxiMVs0XRA5nFMdKvBdBZjgmUUqptlbs74OgvE2upms", GoogleSheetDownloader.extractDocId(url));
        assertEquals("0", GoogleSheetDownloader.extractGid(url));
    }
}
