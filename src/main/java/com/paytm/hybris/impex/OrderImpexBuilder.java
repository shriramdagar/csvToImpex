package com.paytm.hybris.impex;

import java.util.*;

/**
 * Builds SAP Commerce (Hybris) ImpEx matching the exact prototype of orderImportImpex
 * from an order export workbook.
 */
public class OrderImpexBuilder {

    public static String build(Map<String, XlsxReader.SheetData> sheets, String sourceFileName, String targetOrderId, boolean includeUnderlyingCart) {
        XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
        if (primSheet == null) {
            throw new IllegalArgumentException("CartPrimitive sheet not found in workbook.");
        }

        int orderIdCol = findCol(primSheet.getHeaders(), "orderId", "order_id");
        if (orderIdCol == -1) {
            throw new IllegalArgumentException("orderId column not found in CartPrimitive sheet.");
        }

        List<String> orderIds = new ArrayList<>();
        for (List<String> row : primSheet.getRows()) {
            String oId = getVal(row, orderIdCol);
            if (!oId.isEmpty() && !orderIds.contains(oId)) {
                orderIds.add(oId);
            }
        }

        if (orderIds.isEmpty()) {
            throw new IllegalArgumentException("No order records found in CartPrimitive sheet.");
        }

        List<String> selectedOrders = new ArrayList<>();
        if (targetOrderId != null && !targetOrderId.trim().isEmpty()) {
            if (orderIds.contains(targetOrderId.trim())) {
                selectedOrders.add(targetOrderId.trim());
            } else {
                throw new IllegalArgumentException("Order ID " + targetOrderId + " not found in file. Available: " + orderIds);
            }
        } else {
            selectedOrders.addAll(orderIds);
        }

        StringBuilder fullImpex = new StringBuilder();
        for (int i = 0; i < selectedOrders.size(); i++) {
            String oId = selectedOrders.get(i);
            String orderImpex = buildSingleOrder(sheets, sourceFileName, oId, includeUnderlyingCart);
            fullImpex.append(orderImpex);
            if (i < selectedOrders.size() - 1) {
                fullImpex.append("\n\n");
            }
        }

        return fullImpex.toString();
    }

    private static String buildSingleOrder(Map<String, XlsxReader.SheetData> sheets, String sourceFileName, String orderId, boolean includeUnderlyingCart) {
        StringBuilder sb = new StringBuilder();

        // 1. Gather all attributes across Cart sheets for this order
        Map<String, String> orderData = new HashMap<>();
        String[] cartSheets = new String[]{"CartPrimitive", "CartNonPrimitive", "CartItemTypeRefs", "CartCollectionRefs", "CartEnumRefs"};
        String cartCode = "";
        String cartId = "";
        String encryptedOrderId = "";

        for (String sName : cartSheets) {
            XlsxReader.SheetData sData = sheets.get(sName);
            if (sData == null) continue;
            int oCol = findCol(sData.getHeaders(), "orderId", "order_id");
            for (List<String> row : sData.getRows()) {
                String rowOId = getVal(row, oCol);
                if (orderId.equals(rowOId)) {
                    for (int c = 0; c < sData.getHeaders().size(); c++) {
                        String h = sData.getHeaders().get(c).trim();
                        String v = getVal(row, c);
                        if (!v.isEmpty()) {
                            orderData.put(h, v);
                            String normH = h.replaceAll("\\s+", "").toLowerCase();
                            orderData.put(normH, v);
                        }
                    }
                    if (cartCode.isEmpty()) {
                        int cCol = findCol(sData.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
                        cartCode = getVal(row, cCol);
                    }
                    if (cartId.isEmpty()) {
                        int idCol = findCol(sData.getHeaders(), "cartId");
                        cartId = getVal(row, idCol);
                    }
                    if (encryptedOrderId.isEmpty()) {
                        int encCol = findCol(sData.getHeaders(), "encryptedOrderId");
                        encryptedOrderId = getVal(row, encCol);
                    }
                }
            }
        }

        // Customer sheet
        Map<String, String> userData = new HashMap<>();
        XlsxReader.SheetData userSheet = sheets.get("PrereqUsers");
        if (userSheet != null) {
            int oCol = findCol(userSheet.getHeaders(), "orderId");
            int idCol = findCol(userSheet.getHeaders(), "cartId");
            for (List<String> row : userSheet.getRows()) {
                String rOId = getVal(row, oCol);
                String rCartId = getVal(row, idCol);
                if ((!orderId.isEmpty() && orderId.equals(rOId)) || (!cartId.isEmpty() && cartId.equals(rCartId))) {
                    for (int c = 0; c < userSheet.getHeaders().size(); c++) {
                        userData.put(userSheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    break;
                }
            }
        }

        // Addresses sheet
        List<Map<String, String>> addresses = new ArrayList<>();
        XlsxReader.SheetData addrSheet = sheets.get("PrereqAddresses");
        if (addrSheet != null) {
            int oCol = findCol(addrSheet.getHeaders(), "orderId");
            int idCol = findCol(addrSheet.getHeaders(), "cartId");
            for (List<String> row : addrSheet.getRows()) {
                String rOId = getVal(row, oCol);
                String rCartId = getVal(row, idCol);
                if ((!orderId.isEmpty() && orderId.equals(rOId)) || (!cartId.isEmpty() && cartId.equals(rCartId))) {
                    Map<String, String> addrMap = new HashMap<>();
                    for (int c = 0; c < addrSheet.getHeaders().size(); c++) {
                        addrMap.put(addrSheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    addresses.add(addrMap);
                }
            }
        }

        // Delivery Mode & Payment Mode
        String deliveryMode = orderData.getOrDefault("deliveryMode(code)", "standard-gross");
        String paymentMode = orderData.getOrDefault("paymentMode(code)", "creditcard");

        // Order entries
        List<Map<String, String>> entries = new ArrayList<>();
        XlsxReader.SheetData entrySheet = sheets.get("CartEntries");
        if (entrySheet != null) {
            int oCol = findCol(entrySheet.getHeaders(), "orderId");
            int cCol = findCol(entrySheet.getHeaders(), "order(code)[unique = true]", "order(code)", "order");
            for (List<String> row : entrySheet.getRows()) {
                String rOId = getVal(row, oCol);
                String rCode = getVal(row, cCol);
                if ((!orderId.isEmpty() && orderId.equals(rOId)) || (!cartCode.isEmpty() && cartCode.equals(rCode))) {
                    Map<String, String> eMap = new LinkedHashMap<>();
                    for (int c = 0; c < entrySheet.getHeaders().size(); c++) {
                        eMap.put(entrySheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    entries.add(eMap);
                }
            }
        }

        // Macro variables
        String guid = orderData.getOrDefault("guid", cartId);
        String customerUid = userData.getOrDefault("uid", orderData.getOrDefault("user(uid)", ""));
        String customerName = userData.getOrDefault("name", "Customer Name");
        String site = orderData.getOrDefault("site(uid)", "puertorico");
        String store = orderData.getOrDefault("store(uid)", "puertoricoStore");
        String productCatalog = site.contains("Store") ? site.replace("Store", "") + "ProductCatalog" : site + "ProductCatalog";
        String currency = orderData.getOrDefault("currency(isocode)", "USD");

        // Addresses split
        Map<String, String> deliveryAddr = null;
        Map<String, String> billingAddr = null;
        for (Map<String, String> a : addresses) {
            boolean isShip = "TRUE".equalsIgnoreCase(a.get("shippingAddress"));
            boolean isBill = "TRUE".equalsIgnoreCase(a.get("billingAddress"));
            if (isShip && deliveryAddr == null) deliveryAddr = a;
            if (isBill && billingAddr == null) billingAddr = a;
        }
        if (deliveryAddr == null && !addresses.isEmpty()) deliveryAddr = addresses.get(0);
        if (billingAddr == null && !addresses.isEmpty()) billingAddr = addresses.get(addresses.size() - 1);

        String deliveryAddrId = deliveryAddr != null ? deliveryAddr.getOrDefault("id[unique = true]", "PR_ADDR_12624") : "PR_ADDR_12624";
        String billingAddrId = billingAddr != null ? billingAddr.getOrDefault("id[unique = true]", "PR_ADDR_12623") : "PR_ADDR_12623";

        // -----------------------------------------------------------------------------------------
        // Header comments
        // -----------------------------------------------------------------------------------------
        sb.append("# =========================================================================================\n");
        sb.append("# ImpEx: Order Import for Order ID: ").append(orderId).append("\n");
        sb.append("# Associated Cart Code: ").append(cartCode).append(" | Cart GUID: ").append(guid).append("\n");
        sb.append("# Encrypted Order ID: ").append(encryptedOrderId).append("\n");
        sb.append("# Target Tenant: Puerto Rico (PR)\n");
        sb.append("# Source Export File: ").append(sourceFileName).append("\n");
        sb.append("# Sheets Mapped:\n");
        sb.append("#   - PrereqUsers -> Customer (").append(customerName).append(" / ").append(customerUid).append(")\n");
        sb.append("#   - PrereqAddresses -> Address (Delivery: ").append(deliveryAddrId).append(", Billing: ").append(billingAddrId).append(")\n");
        sb.append("#   - PrereqDeliveryModes -> ZoneDeliveryMode (").append(deliveryMode).append(")\n");
        sb.append("#   - PrereqPaymentModes -> StandardPaymentMode (").append(paymentMode).append(") & CreditCardPaymentInfo (Braintree)\n");
        sb.append("#   - CartPrimitive, CartNonPrimitive, CartItemTypeRefs, CartEnumRefs -> Order Header (").append(orderId).append(")\n");
        sb.append("#   - CartEntries -> OrderEntry (");
        for (int i = 0; i < entries.size(); i++) {
            sb.append("Entry ").append(i).append(": ").append(entries.get(i).getOrDefault("product(code, $onlineCatalogVersion)", ""));
            if (i < entries.size() - 1) sb.append(", ");
        }
        sb.append(")\n");
        sb.append("# =========================================================================================\n\n");

        // -----------------------------------------------------------------------------------------
        // 1. MACROS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 1. MACROS & CATALOG CONFIGURATION\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("$orderCode = ").append(orderId).append("\n");
        sb.append("$cartCode = ").append(cartCode).append("\n");
        sb.append("$cartGuid = ").append(guid).append("\n");
        sb.append("$customerUid = ").append(customerUid).append("\n");
        sb.append("$site = ").append(site).append("\n");
        sb.append("$store = ").append(store).append("\n");
        sb.append("$productCatalog = ").append(productCatalog).append("\n");
        sb.append("$onlineCatalogVersion = catalogversion(catalog(id[default = $productCatalog]), version[default = 'Online'])[unique = true, default = $productCatalog:Online]\n");
        sb.append("$defaultCurrency = ").append(currency).append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 2. PREREQUISITES
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 2. PREREQUISITES: CUSTOMER, ADDRESSES, RISKS, DELIVERY/PAYMENT MODES & PRICING\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        // 2.1 Customer
        String contactEmail = userData.getOrDefault("contactEmail", orderData.getOrDefault("customerEmail", ""));
        String mobilePhone = orderData.getOrDefault("phoneNumber", "(787)000-0000");
        String groups = userData.getOrDefault("groups(uid)", "customergroup");
        String sessionLang = userData.getOrDefault("sessionLanguage(isocode)", "en");
        if (sessionLang.contains("-")) sessionLang = sessionLang.split("-")[0];
        String existingCust = orderData.getOrDefault("isCustomerExisting", "FALSE");

        sb.append("# 2.1 Customer (from PrereqUsers & CartPrimitive)\n");
        sb.append("INSERT_UPDATE Customer; uid[unique = true] ; name                 ; contactEmail           ; mobilePhone   ; customerID   ; groups(uid)   ; sessionLanguage(isocode); sessionCurrency(isocode); existingCustomer; dateOfBirth[dateformat = yyyy-MM-dd]; correlationId\n");
        sb.append("                      ; $customerUid       ; ").append(pad(customerName, 20)).append(" ; ").append(pad(contactEmail, 22)).append(" ; ").append(pad(mobilePhone, 13)).append(" ; $customerUid ; ").append(pad(groups, 13)).append(" ; ").append(pad(sessionLang, 23)).append(" ; $defaultCurrency        ; ").append(pad(existingCust, 15)).append(" ; 1990-01-01                          ; CORR-$orderCode\n\n");

        // 2.2 Addresses
        sb.append("# 2.2 Addresses (from PrereqAddresses)\n");
        sb.append("# Delivery / Shipping Address (").append(deliveryAddrId).append(")\n");
        sb.append("INSERT_UPDATE Address; &deliveryAddr  ; id[unique = true]; owner(Customer.uid)[unique = true]; firstname ; lastname     ; streetname ; streetnumber ; postalcode ; town   ; country(isocode) ; billingAddress ; shippingAddress\n");
        if (deliveryAddr != null) {
            sb.append("                     ; ").append(pad(deliveryAddrId, 14)).append(" ; ").append(pad(deliveryAddrId, 16)).append(" ; $customerUid                      ; ").append(pad(deliveryAddr.getOrDefault("firstname", "ANNA"), 9)).append(" ; ").append(pad(deliveryAddr.getOrDefault("lastname", "Test"), 12)).append(" ; ").append(pad(deliveryAddr.getOrDefault("streetname", "Calle 39"), 10)).append(" ; ").append(pad(deliveryAddr.getOrDefault("streetnumber", "834"), 12)).append(" ; ").append(pad(deliveryAddr.getOrDefault("postalcode", "00778"), 10)).append(" ; ").append(pad(deliveryAddr.getOrDefault("town", "GURABO"), 6)).append(" ; ").append(pad(deliveryAddr.getOrDefault("country(isocode)", "PR"), 16)).append(" ; FALSE          ; TRUE\n\n");
        } else {
            sb.append("                     ; ").append(pad(deliveryAddrId, 14)).append(" ; ").append(pad(deliveryAddrId, 16)).append(" ; $customerUid                      ; ANNA      ; Test         ; Calle 39   ; 834          ; 00778      ; GURABO ; PR               ; FALSE          ; TRUE\n\n");
        }

        sb.append("# Billing Address (").append(billingAddrId).append(")\n");
        sb.append("INSERT_UPDATE Address; &billingAddr   ; id[unique = true]; owner(Customer.uid)[unique = true]; firstname ; lastname     ; streetname ; streetnumber ; postalcode ; town   ; country(isocode) ; billingAddress ; shippingAddress\n");
        if (billingAddr != null) {
            sb.append("                     ; ").append(pad(billingAddrId, 14)).append(" ; ").append(pad(billingAddrId, 16)).append(" ; $customerUid                      ; ").append(pad(billingAddr.getOrDefault("firstname", "ANNA"), 9)).append(" ; ").append(pad(billingAddr.getOrDefault("lastname", "Test"), 12)).append(" ; ").append(pad(billingAddr.getOrDefault("streetname", "Calle 39"), 10)).append(" ; ").append(pad(billingAddr.getOrDefault("streetnumber", "834"), 12)).append(" ; ").append(pad(billingAddr.getOrDefault("postalcode", "00778"), 10)).append(" ; ").append(pad(billingAddr.getOrDefault("town", "Gurabo"), 6)).append(" ; ").append(pad(billingAddr.getOrDefault("country(isocode)", "PR"), 16)).append(" ; TRUE           ; FALSE\n\n");
        } else {
            sb.append("                     ; ").append(pad(billingAddrId, 14)).append(" ; ").append(pad(billingAddrId, 16)).append(" ; $customerUid                      ; ANNA      ; Test         ; Calle 39   ; 834          ; 00778      ; Gurabo ; PR               ; TRUE           ; FALSE\n\n");
        }

        // 2.3 Credit Score Based Risk
        String riskCode = orderData.getOrDefault("creditScoreRisk(code)", "A_0_6");
        String riskLetter = orderData.getOrDefault("creditScoreLetter", "A");
        sb.append("# 2.3 Credit Score Based Risk (Tenure: 0-6 months, Letter: ").append(riskLetter).append(")\n");
        sb.append("INSERT_UPDATE CreditScoreBasedRisk; code[unique = true]; minScoreRange; maxScoreRange; minTenureAge; maxTenureAge; letterMapping\n");
        sb.append("                                  ; ").append(pad(riskCode, 18)).append(" ; 300          ; 850          ; 0           ; 6           ; ").append(riskLetter).append("\n\n");

        // 2.4 Credit Score Tier
        String tierCode = orderData.getOrDefault("creditScoreLevel(code)", "CS_TIER_A");
        sb.append("# 2.4 Credit Score Tier (Range: 700-850, Contract: WITHCONTRACT, Letter: ").append(riskLetter).append(")\n");
        sb.append("INSERT_UPDATE CreditScore; code[unique = true]; minScoreRange; maxScoreRange; contractStatus(code); pack    ; paperBilling; letterMapping\n");
        sb.append("                         ; ").append(pad(tierCode, 18)).append(" ; 700          ; 850          ; WITHCONTRACT        ; DEFAULT ; 0.0         ; ").append(riskLetter).append("\n\n");

        // 2.5 Delivery Mode
        sb.append("# 2.5 Delivery Mode (Standard Delivery)\n");
        sb.append("INSERT_UPDATE ZoneDeliveryMode; code[unique = true]; net ; active\n");
        sb.append("                              ; ").append(pad(deliveryMode, 18)).append(" ; TRUE; TRUE\n\n");

        // 2.6 Payment Mode & Payment Info
        sb.append("# 2.6 Payment Mode & Payment Info\n");
        sb.append("INSERT_UPDATE StandardPaymentMode; code[unique = true]; active; paymentInfoType(code)\n");
        sb.append("                                 ; ").append(pad(paymentMode, 18)).append(" ; TRUE  ; CreditCardPaymentInfo\n\n");

        sb.append("INSERT_UPDATE CreditCardPaymentInfo; code[unique = true] ; user(uid)    ; billingAddress(&billingAddr); ccOwner             ; subscriptionId\n");
        sb.append("                                   ; PAY_INFO_$orderCode ; $customerUid ; ").append(pad(billingAddrId, 22)).append(" ; \"").append(customerName).append("\" ; \"SUB-").append(orderId).append("\"\n\n");

        // 2.7 Composite Order Price
        sb.append("# 2.7 Composite Order Price (TUA Price for Order)\n");
        sb.append("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]; name                               ; catalogCode\n");
        sb.append("                                            ; price_$orderCode ; \"Order Composite Price $orderCode\" ; $productCatalog\n\n");

        // 2.8 Order Total Prices
        String recPrice = orderData.getOrDefault("orderMonthlyPrice", orderData.getOrDefault("totalRecurringPrice", "93.05"));
        String duePrice = orderData.getOrDefault("amountDueToday", "0.00");
        sb.append("# 2.8 Order Total Prices (Recurring = $").append(recPrice).append(", Due Today = $").append(duePrice).append(")\n");
        sb.append("INSERT TotalPrice; &tpId             ; priceType(code) ; value ; isTaxIncluded; totalTax\n");
        sb.append("                 ; tp_rec_$orderCode ; RECURRING       ; ").append(pad(recPrice, 5)).append(" ; TRUE         ; 0.00\n");
        sb.append("                 ; tp_due_$orderCode ; AMOUNT_DUE_TODAY; ").append(pad(duePrice, 5)).append(" ; TRUE         ; 0.00\n\n");

        // -----------------------------------------------------------------------------------------
        // 3. ORDER HEADER
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 3. ORDER HEADER (from CartPrimitive, CartNonPrimitive, CartItemTypeRefs, CartEnumRefs)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String[] orderHeaderCols = new String[]{
                "code[unique = true]", "guid", "user(uid)", "savedBy(uid)", "currency(isocode)", "site(uid)", "store(uid)",
                "date[dateformat = dd.MM.yyyy HH:mm:ss]", "net[default = true]", "calculated[default = true]", "subtotal",
                "totalPrice", "totalTax", "totalTaxValuesInternal", "totalDiscounts", "price(id)", "prices(&tpId)",
                "deliveryAddress(&deliveryAddr)", "paymentAddress(&billingAddr)", "selectedAddress(&deliveryAddr)",
                "deliveryMode(code)", "paymentMode(code)", "paymentInfo(code)", "status(code)", "orderStatus(code)",
                "paymentStatus(code)", "paymentGateway(code)", "paymentType(code)", "planTypes(code)", "creditCheck",
                "creditCheckEnum(code)", "creditScore", "creditScoreLetter", "creditScoreRisk(code)", "creditScoreLevel(code)",
                "llaCreditRiskRating", "vertexApiCheck", "vertexApiCheckEnum(code)", "orderVertexTaxAmnt", "deliveryMethod",
                "deliveryType", "storeId", "storeName", "selectedStoreID", "storePickupAddress1", "storePickupAddress2",
                "storePickupCity", "storePickupZip", "phoneNumber", "customerEmail", "name", "autoPayActive", "billPreference",
                "devicePlanCode", "devicePrice", "devicePriceType", "isC2CRequestReceived", "customerDrop", "sourceSystem",
                "locale", "numberOfExistingLine", "numberOfFinancialDevices", "numberOfLines", "orderFeesAmount", "orderMonthlyPrice",
                "totalOneTimePrice", "totalRecurringPrice", "amountDueToday", "amountDueFirstBill", "isSSNSkipped", "paymentJsonConsent"
        };

        sb.append("INSERT_UPDATE Order; ").append(String.join("; ", orderHeaderCols)).append("\n");

        List<String> orderValCells = new ArrayList<>();
        for (String col : orderHeaderCols) {
            String val = "";
            switch (col) {
                case "code[unique = true]": val = "$orderCode"; break;
                case "guid": val = "$cartGuid"; break;
                case "user(uid)":
                case "savedBy(uid)": val = "$customerUid"; break;
                case "currency(isocode)": val = "$defaultCurrency"; break;
                case "site(uid)": val = "$site"; break;
                case "store(uid)": val = "$store"; break;
                case "net[default = true]":
                case "calculated[default = true]": val = "TRUE"; break;
                case "price(id)": val = "price_$orderCode"; break;
                case "prices(&tpId)": val = "tp_rec_$orderCode,tp_due_$orderCode"; break;
                case "deliveryAddress(&deliveryAddr)":
                case "selectedAddress(&deliveryAddr)": val = deliveryAddrId; break;
                case "paymentAddress(&billingAddr)": val = billingAddrId; break;
                case "paymentInfo(code)": val = "PAY_INFO_$orderCode"; break;
                case "customerEmail": val = contactEmail; break;
                case "name": val = customerName; break;
                default:
                    val = orderData.getOrDefault(col, orderData.getOrDefault(col.replaceAll("\\s+", "").toLowerCase(), ""));
                    break;
            }
            orderValCells.add(ImpexFormatter.formatCell(val));
        }

        sb.append("                   ; ").append(String.join(" ; ", orderValCells)).append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 4. ORDER LINE ITEMS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 4. ORDER LINE ITEMS (OrderEntry from CartEntries sheet)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String[] entryHeaderCols = new String[]{
                "order(code)[unique = true]", "entryNumber[unique = true]", "product(code, $onlineCatalogVersion)", "quantity",
                "unit(code)", "basePrice", "totalPrice", "monthlyPrice", "entryRecurringPrice", "tax13Price", "taxValuesInternal",
                "processType(code)", "calculated", "giveAway", "rejected", "numberOfLines"
        };

        sb.append("INSERT_UPDATE OrderEntry; ").append(String.join("; ", entryHeaderCols)).append("\n");

        for (int i = 0; i < entries.size(); i++) {
            Map<String, String> entry = entries.get(i);
            List<String> entryCells = new ArrayList<>();
            for (String col : entryHeaderCols) {
                String val = "";
                switch (col) {
                    case "order(code)[unique = true]": val = "$orderCode"; break;
                    case "entryNumber[unique = true]": val = entry.getOrDefault("entryNumber[unique = true]", String.valueOf(i)); break;
                    case "calculated": val = "TRUE"; break;
                    case "giveAway":
                    case "rejected": val = "FALSE"; break;
                    default:
                        val = entry.getOrDefault(col, "");
                        break;
                }
                entryCells.add(ImpexFormatter.formatCell(val));
            }
            sb.append("                        ; ").append(String.join(" ; ", entryCells)).append("\n");
        }

        // -----------------------------------------------------------------------------------------
        // 5. OPTIONAL: UNDERLYING CART & CART ENTRIES
        // -----------------------------------------------------------------------------------------
        if (includeUnderlyingCart && !cartCode.isEmpty()) {
            sb.append("\n# -----------------------------------------------------------------------------------------\n");
            sb.append("# 5. OPTIONAL: UNDERLYING CART & CART ENTRIES (Cart Code: ").append(cartCode).append(")\n");
            sb.append("# Note: In SAP Commerce, orders originate from carts. Include this section if you also\n");
            sb.append("# require the source Cart record present in the database.\n");
            sb.append("# -----------------------------------------------------------------------------------------\n");

            sb.append("INSERT_UPDATE Cart; ").append(String.join("; ", orderHeaderCols)).append("\n");

            List<String> cartValCells = new ArrayList<>();
            for (String col : orderHeaderCols) {
                String val = "";
                switch (col) {
                    case "code[unique = true]": val = "$cartCode"; break;
                    case "guid": val = "$cartGuid"; break;
                    case "user(uid)":
                    case "savedBy(uid)": val = "$customerUid"; break;
                    case "currency(isocode)": val = "$defaultCurrency"; break;
                    case "site(uid)": val = "$site"; break;
                    case "store(uid)": val = "$store"; break;
                    case "net[default = true]":
                    case "calculated[default = true]": val = "TRUE"; break;
                    case "price(id)": val = "price_$orderCode"; break;
                    case "prices(&tpId)": val = "tp_rec_$orderCode,tp_due_$orderCode"; break;
                    case "deliveryAddress(&deliveryAddr)":
                    case "selectedAddress(&deliveryAddr)": val = deliveryAddrId; break;
                    case "paymentAddress(&billingAddr)": val = billingAddrId; break;
                    case "paymentInfo(code)": val = "PAY_INFO_$orderCode"; break;
                    case "customerEmail": val = contactEmail; break;
                    case "name": val = customerName; break;
                    default:
                        val = orderData.getOrDefault(col, orderData.getOrDefault(col.replaceAll("\\s+", "").toLowerCase(), ""));
                        break;
                }
                cartValCells.add(ImpexFormatter.formatCell(val));
            }
            sb.append("                  ; ").append(String.join(" ; ", cartValCells)).append("\n\n");

            sb.append("INSERT_UPDATE CartEntry; ").append(String.join("; ", entryHeaderCols)).append("\n");
            for (int i = 0; i < entries.size(); i++) {
                Map<String, String> entry = entries.get(i);
                List<String> entryCells = new ArrayList<>();
                for (String col : entryHeaderCols) {
                    String val = "";
                    switch (col) {
                        case "order(code)[unique = true]": val = "$cartCode"; break;
                        case "entryNumber[unique = true]": val = entry.getOrDefault("entryNumber[unique = true]", String.valueOf(i)); break;
                        case "calculated": val = "TRUE"; break;
                        case "giveAway":
                        case "rejected": val = "FALSE"; break;
                        default:
                            val = entry.getOrDefault(col, "");
                            break;
                    }
                    entryCells.add(ImpexFormatter.formatCell(val));
                }
                sb.append("                       ; ").append(String.join(" ; ", entryCells)).append("\n");
            }
        }

        return sb.toString();
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

    private static String getVal(List<String> row, int col) {
        if (col >= 0 && col < row.size()) {
            return row.get(col) != null ? row.get(col).trim() : "";
        }
        return "";
    }

    private static String pad(String s, int len) {
        if (s == null) s = "";
        if (s.length() >= len) return s;
        return s + " ".repeat(len - s.length());
    }
}
