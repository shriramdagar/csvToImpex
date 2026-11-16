package com.paytm.hybris.impex;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class HybrisImpexConverterTest {

    @Test
    public void testAbandonedCartExportMatchesPrototype() throws Exception {
        File cartFile = new File("abandoned_cart_2026_11_02.xlsx");
        if (!cartFile.exists()) {
            cartFile = new File("abandoned_cart_hybris_export_2026-11-02_20-00_to_2026-11-03_19-59.xlsx");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(cartFile.exists(), "Sample cart export file not found; skipping");

        Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(cartFile);
        assertNotNull(sheets.get("CartPrimitive"));
        assertNotNull(sheets.get("PrereqUsers"));
        assertNotNull(sheets.get("CartEntries"));

        String impex = CartImpexBuilder.build(sheets, cartFile.getName(), null);
        assertNotNull(impex);

        // Verify key prototype sections
        assertTrue(impex.contains("ImpEx: Abandoned Cart Import for All Records"));
        assertTrue(impex.contains("1. MACROS & CATALOG CONFIGURATION"));
        assertTrue(impex.contains("$site = puertorico"));
        assertTrue(impex.contains("$store = puertoricoStore"));
        assertTrue(impex.contains("$productCatalog = puertoricoProductCatalog"));
        assertTrue(impex.contains("2.0 Product Offering Prerequisites"));
        assertTrue(impex.contains("INSERT_UPDATE Product; code[unique = true]"));
        assertTrue(impex.contains("2.1 Customers (from PrereqUsers & CartPrimitive)"));
        assertTrue(impex.contains("INSERT_UPDATE Customer; uid[unique = true]"));
        assertTrue(impex.contains("2.2 Addresses (Delivery & Billing Addresses for Puerto Rico)"));
        assertTrue(impex.contains("INSERT_UPDATE Address; &addrId"));
        assertTrue(impex.contains("2.3 Credit Score Based Risk"));
        assertTrue(impex.contains("INSERT_UPDATE CreditScoreBasedRisk; code[unique = true]"));
        assertTrue(impex.contains("2.4 Credit Score Tier"));
        assertTrue(impex.contains("INSERT_UPDATE CreditScore; code[unique = true]"));
        assertTrue(impex.contains("2.5 Delivery Mode"));
        assertTrue(impex.contains("INSERT_UPDATE ZoneDeliveryMode; code[unique = true]"));
        assertTrue(impex.contains("2.6 Payment Mode & Payment Info"));
        assertTrue(impex.contains("INSERT_UPDATE CreditCardPaymentInfo; &paymentInfo"));
        assertTrue(impex.contains("2.7 Composite Cart Prices"));
        assertTrue(impex.contains("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]"));
        assertTrue(impex.contains("2.8 Cart Total Prices"));
        assertTrue(impex.contains("INSERT TotalPrice; &tpId"));
        assertTrue(impex.contains("3. CART HEADERS"));
        assertTrue(impex.contains("INSERT_UPDATE Cart; code[unique = true]"));
        assertTrue(impex.contains("4. CART LINE ITEMS"));
        assertTrue(impex.contains("INSERT_UPDATE CartEntry; order(code)[unique = true]"));
        assertTrue(impex.contains("Mobile_Plan_Mix_More_PR_Test"));
    }

    @Test
    public void testOrderExportMatchesPrototype() throws Exception {
        File orderFile = new File("sample_orders.xlsx");
        if (!orderFile.exists()) {
            orderFile = new File("order_hybris_export_2026-11-06_00-00_to_2026-11-06_23-59.xlsx");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(orderFile.exists(), "Sample order export file not found; skipping");

        Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(orderFile);
        assertNotNull(sheets.get("CartPrimitive"));
        assertNotNull(sheets.get("PrereqUsers"));
        assertNotNull(sheets.get("PrereqAddresses"));
        assertNotNull(sheets.get("CartEntries"));

        // Generate for all orders
        String impex = OrderImpexBuilder.build(sheets, orderFile.getName(), null, true);
        assertNotNull(impex);

        // Verify all 6 reference sections
        assertTrue(impex.contains("ImpEx: Order Import for All Records"));
        assertTrue(impex.contains("1. MACROS & CATALOG CONFIGURATION"));
        assertTrue(impex.contains("$site = puertorico"));
        assertTrue(impex.contains("$store = puertoricoStore"));
        assertTrue(impex.contains("$productCatalog = puertoricoProductCatalog"));
        assertTrue(impex.contains("2.0 Product Offering Prerequisites"));
        assertTrue(impex.contains("INSERT_UPDATE Product; code[unique = true]"));
        assertTrue(impex.contains("2.1 Customers (from PrereqUsers & CartPrimitive)"));
        assertTrue(impex.contains("INSERT_UPDATE Customer; uid[unique = true]"));
        assertTrue(impex.contains("2.2 Addresses (Delivery & Billing Addresses for Puerto Rico)"));
        assertTrue(impex.contains("INSERT_UPDATE Address; &addrId"));
        assertTrue(impex.contains("2.3 Credit Score Based Risk"));
        assertTrue(impex.contains("INSERT_UPDATE CreditScoreBasedRisk; code[unique = true]"));
        assertTrue(impex.contains("2.4 Credit Score Tier"));
        assertTrue(impex.contains("INSERT_UPDATE CreditScore; code[unique = true]"));
        assertTrue(impex.contains("2.5 Delivery Mode"));
        assertTrue(impex.contains("INSERT_UPDATE ZoneDeliveryMode; code[unique = true]"));
        assertTrue(impex.contains("2.6 Payment Mode & Payment Info"));
        assertTrue(impex.contains("REMOVE CreditCardPaymentInfo[batchmode = true]; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE CreditCardPaymentInfo; &paymentInfo"));
        assertTrue(impex.contains("2.7 Composite Order Prices"));
        assertTrue(impex.contains("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]"));
        assertTrue(impex.contains("2.8 Order Total Prices"));
        assertTrue(impex.contains("INSERT TotalPrice; &tpId"));
        assertTrue(impex.contains("3. ORDER HEADERS"));
        assertTrue(impex.contains("INSERT_UPDATE Order; code[unique = true]"));
        assertTrue(impex.contains("4. ORDER LINE ITEMS"));
        assertTrue(impex.contains("INSERT_UPDATE OrderEntry; order(code)[unique = true]"));
        assertTrue(impex.contains("5. CART SNAPSHOT"));
        assertTrue(impex.contains("INSERT_UPDATE Cart; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE CartEntry; order(code)[unique = true]"));
        assertTrue(impex.contains("6. PROMOTION ENGINE OBJECTS"));
        assertTrue(impex.contains("INSERT PromotionResult; &promoResId"));
        assertTrue(impex.contains("INSERT_UPDATE PromotionOrderEntryAdjustAction; guid[unique = true]"));
        assertTrue(impex.contains("INSERT PromotionOrderEntryConsumed; code[unique = true]"));
    }

    @Test
    public void testSingleOrderExportMatchesPrototype() throws Exception {
        File orderFile = new File("sample_orders.xlsx");
        if (!orderFile.exists()) {
            orderFile = new File("order_hybris_export_2026-11-06_00-00_to_2026-11-06_23-59.xlsx");
        }
        org.junit.jupiter.api.Assumptions.assumeTrue(orderFile.exists(), "Sample order export file not found; skipping");

        Map<String, XlsxReader.SheetData> sheets = XlsxReader.read(orderFile);

        // Generate for single order 20887065
        String impex = OrderImpexBuilder.build(sheets, orderFile.getName(), "20887065", true);
        assertNotNull(impex);

        assertTrue(impex.contains("ImpEx: Order Import for Order ID: 20887065"));
        assertTrue(impex.contains("20887065"));
        assertTrue(impex.contains("INSERT_UPDATE Order; code[unique = true]"));
        assertTrue(impex.contains("INSERT_UPDATE OrderEntry; order(code)[unique = true]"));
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
