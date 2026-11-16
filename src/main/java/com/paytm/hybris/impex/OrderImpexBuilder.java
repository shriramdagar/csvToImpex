package com.paytm.hybris.impex;

import java.util.*;

/**
 * Builds SAP Commerce (Hybris) ImpEx matching the exact specification of order_import_2026-11-06.impex
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

        // Cache order data for each order across all relevant sheets
        Map<String, Map<String, String>> ordersData = new LinkedHashMap<>();
        Map<String, String> orderCartCodes = new LinkedHashMap<>();
        Map<String, String> orderCartGuids = new LinkedHashMap<>();
        Map<String, Map<String, String>> usersData = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> addressesData = new LinkedHashMap<>();
        Map<String, List<Map<String, String>>> entriesData = new LinkedHashMap<>();

        String[] cartSheets = new String[]{"CartPrimitive", "CartNonPrimitive", "CartItemTypeRefs", "CartCollectionRefs", "CartEnumRefs"};

        for (String oId : selectedOrders) {
            Map<String, String> oData = new LinkedHashMap<>();
            String cCode = "";
            String cGuid = "";

            for (String sName : cartSheets) {
                XlsxReader.SheetData sData = sheets.get(sName);
                if (sData == null) continue;
                int oCol = findCol(sData.getHeaders(), "orderId", "order_id");
                for (List<String> row : sData.getRows()) {
                    String rowOId = getVal(row, oCol);
                    if (oId.equals(rowOId)) {
                        for (int c = 0; c < sData.getHeaders().size(); c++) {
                            String h = sData.getHeaders().get(c).trim();
                            String v = getVal(row, c);
                            if (!v.isEmpty()) {
                                oData.put(h, v);
                                oData.put(h.replaceAll("\\s+", "").toLowerCase(), v);
                            }
                        }
                        if (cCode.isEmpty()) {
                            int cCol = findCol(sData.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
                            cCode = getVal(row, cCol);
                        }
                        if (cGuid.isEmpty()) {
                            int idCol = findCol(sData.getHeaders(), "cartId", "guid");
                            cGuid = getVal(row, idCol);
                        }
                    }
                }
            }
            ordersData.put(oId, oData);
            orderCartCodes.put(oId, cCode);
            orderCartGuids.put(oId, cGuid);

            // User data
            Map<String, String> uData = new LinkedHashMap<>();
            XlsxReader.SheetData userSheet = sheets.get("PrereqUsers");
            if (userSheet != null) {
                int oCol = findCol(userSheet.getHeaders(), "orderId");
                int idCol = findCol(userSheet.getHeaders(), "cartId");
                for (List<String> row : userSheet.getRows()) {
                    String rOId = getVal(row, oCol);
                    String rCartId = getVal(row, idCol);
                    if ((!oId.isEmpty() && oId.equals(rOId)) || (!cGuid.isEmpty() && cGuid.equals(rCartId))) {
                        for (int c = 0; c < userSheet.getHeaders().size(); c++) {
                            uData.put(userSheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        break;
                    }
                }
            }
            usersData.put(oId, uData);

            // Address data
            List<Map<String, String>> aList = new ArrayList<>();
            XlsxReader.SheetData addrSheet = sheets.get("PrereqAddresses");
            if (addrSheet != null) {
                int oCol = findCol(addrSheet.getHeaders(), "orderId");
                int idCol = findCol(addrSheet.getHeaders(), "cartId");
                for (List<String> row : addrSheet.getRows()) {
                    String rOId = getVal(row, oCol);
                    String rCartId = getVal(row, idCol);
                    if ((!oId.isEmpty() && oId.equals(rOId)) || (!cGuid.isEmpty() && cGuid.equals(rCartId))) {
                        Map<String, String> addrMap = new LinkedHashMap<>();
                        for (int c = 0; c < addrSheet.getHeaders().size(); c++) {
                            addrMap.put(addrSheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        aList.add(addrMap);
                    }
                }
            }
            addressesData.put(oId, aList);

            // Entries data
            List<Map<String, String>> eList = new ArrayList<>();
            XlsxReader.SheetData entrySheet = sheets.get("CartEntries");
            if (entrySheet != null) {
                int oCol = findCol(entrySheet.getHeaders(), "orderId");
                int cCol = findCol(entrySheet.getHeaders(), "order(code)[unique = true]", "order(code)", "order");
                for (List<String> row : entrySheet.getRows()) {
                    String rOId = getVal(row, oCol);
                    String rCode = getVal(row, cCol);
                    if ((!oId.isEmpty() && oId.equals(rOId)) || (!cCode.isEmpty() && cCode.equals(rCode))) {
                        Map<String, String> eMap = new LinkedHashMap<>();
                        for (int c = 0; c < entrySheet.getHeaders().size(); c++) {
                            eMap.put(entrySheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        eList.add(eMap);
                    }
                }
            }
            entriesData.put(oId, eList);
        }

        // Global macros derived from first order
        String firstOId = selectedOrders.get(0);
        Map<String, String> firstOrder = ordersData.get(firstOId);
        String site = firstOrder.getOrDefault("site(uid)", "puertorico");
        String store = firstOrder.getOrDefault("store(uid)", site + "Store");
        String productCatalog = site.contains("Store") ? site.replace("Store", "") + "ProductCatalog" : site + "ProductCatalog";
        String currency = firstOrder.getOrDefault("currency(isocode)", "USD");

        // Count total entries
        int totalLineItems = 0;
        for (String oId : selectedOrders) {
            totalLineItems += entriesData.get(oId).size();
        }

        StringBuilder sb = new StringBuilder();

        // =========================================================================================
        // Top Header
        // =========================================================================================
        sb.append("# =========================================================================================\n");
        if (selectedOrders.size() > 1) {
            sb.append("# ImpEx: Order Import for All Records\n");
        } else {
            sb.append("# ImpEx: Order Import for Order ID: ").append(firstOId).append("\n");
        }
        sb.append("# Target Tenant: Puerto Rico (PR)\n");
        sb.append("# Source Export File: ").append(sourceFileName).append("\n");
        sb.append("# Orders Included: ").append(selectedOrders.size()).append(" Orders (").append(totalLineItems).append(" Entry Line Items)\n");
        for (int i = 0; i < selectedOrders.size(); i++) {
            String oId = selectedOrders.get(i);
            String cCode = orderCartCodes.get(oId);
            Map<String, String> uData = usersData.get(oId);
            Map<String, String> oData = ordersData.get(oId);
            String uName = uData.getOrDefault("name", oData.getOrDefault("name", ""));
            String uNameComment = uName.replaceAll("\\s+", " ").trim();
            String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));
            String risk = oData.getOrDefault("creditScoreRisk(code)", oData.getOrDefault("creditScoreRisk(id)", ""));
            String creditDesc = !risk.isEmpty() ? "Credit: " + risk : "Prepaid: No Credit";
            sb.append("#   ").append(i + 1).append(". Order ").append(oId)
              .append(" (Cart: ").append(cCode).append(" | User: ").append(uNameComment).append(" / ").append(uUid).append(" | ").append(creditDesc).append(")\n");
        }
        sb.append("# =========================================================================================\n\n");

        // -----------------------------------------------------------------------------------------
        // 1. MACROS & CATALOG CONFIGURATION
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 1. MACROS & CATALOG CONFIGURATION\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("$site = ").append(site).append("\n");
        sb.append("$store = ").append(store).append("\n");
        sb.append("$productCatalog = ").append(productCatalog).append("\n");
        sb.append("$onlineCatalogVersion = catalogversion(catalog(id[default = $productCatalog]), version[default = 'Online'])[unique = true, default = $productCatalog:Online]\n");
        sb.append("$defaultCurrency = ").append(currency).append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 2. PREREQUISITES
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 2. PREREQUISITES: PRODUCTS, CUSTOMERS, ADDRESSES, RISKS, DELIVERY/PAYMENT MODES & PRICING\n");
        sb.append("# -----------------------------------------------------------------------------------------\n\n");

        // 2.0 Product Offering Prerequisites
        List<String> distinctProducts = new ArrayList<>();
        for (String oId : selectedOrders) {
            for (Map<String, String> entry : entriesData.get(oId)) {
                String pCode = entry.getOrDefault("product(code, $onlineCatalogVersion)", entry.getOrDefault("product", ""));
                if (!pCode.isEmpty() && !distinctProducts.contains(pCode)) {
                    distinctProducts.add(pCode);
                }
            }
        }

        sb.append("# 2.0 Product Offering Prerequisites (Ensures catalog items exist)\n");
        sb.append("INSERT_UPDATE Product; code[unique = true]        ; catalogVersion(catalog(id), version)[unique = true]; name[lang = en]            ; unit(code)[default = 'pieces']; approvalStatus(code)[default = 'approved']\n");
        for (String pCode : distinctProducts) {
            String pName = formatProductName(pCode);
            sb.append("                     ; ").append(pad(pCode, 29))
              .append("; $productCatalog:Online                             ; \"").append(pName).append("\"   ;                               ;\n");
        }
        sb.append("\n");

        // 2.1 Customers
        sb.append("# 2.1 Customers (from PrereqUsers & CartPrimitive)\n");
        sb.append("INSERT_UPDATE Customer; uid[unique = true]                    ; name            ; contactEmail                   ; mobilePhone   ; customerID                            ; groups(uid)   ; sessionLanguage(isocode); sessionCurrency(isocode); existingCustomer; dateOfBirth[dateformat = yyyy-MM-dd]; correlationId\n");
        for (String oId : selectedOrders) {
            Map<String, String> uData = usersData.get(oId);
            Map<String, String> oData = ordersData.get(oId);
            String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));
            String uName = uData.getOrDefault("name", oData.getOrDefault("name", ""));
            String email = uData.getOrDefault("contactEmail", oData.getOrDefault("customerEmail", ""));
            String phone = oData.getOrDefault("phoneNumber", "(787)000-0000");
            String lang = uData.getOrDefault("sessionLanguage(isocode)", "en");
            if (lang.contains("-")) lang = lang.split("-")[0];
            String exist = oData.getOrDefault("isCustomerExisting", "FALSE");
            if (exist.isEmpty()) exist = "FALSE";

            sb.append("                      ; ").append(pad(uUid, 39))
              .append("; ").append(pad(uName, 17))
              .append("; ").append(pad(email, 31))
              .append("; ").append(pad(phone, 14))
              .append("; ").append(pad(uUid, 39))
              .append("; customergroup ; ").append(pad(lang, 24))
              .append("; $defaultCurrency        ; ").append(pad(exist, 16))
              .append("; 1990-01-01                          ; CORR-").append(oId).append("\n");
        }
        sb.append("\n");

        // 2.2 Addresses
        sb.append("# 2.2 Addresses (Delivery & Billing Addresses for Puerto Rico)\n");
        sb.append("INSERT_UPDATE Address; &addrId       ; id[unique = true]; owner(Customer.uid)[unique = true]    ; firstname ; lastname ; streetname ; streetnumber ; postalcode ; town      ; country(isocode) ; billingAddress ; shippingAddress\n");
        List<String> seenAddrIds = new ArrayList<>();
        for (String oId : selectedOrders) {
            List<Map<String, String>> addrs = addressesData.get(oId);
            Map<String, String> uData = usersData.get(oId);
            Map<String, String> oData = ordersData.get(oId);
            String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));

            for (Map<String, String> a : addrs) {
                String aId = a.getOrDefault("id[unique = true]", a.getOrDefault("id", ""));
                if (aId.isEmpty() || seenAddrIds.contains(aId)) continue;
                seenAddrIds.add(aId);

                String fn = a.getOrDefault("firstname", "");
                String ln = a.getOrDefault("lastname", "");
                String sn = a.getOrDefault("streetname", "");
                String snum = a.getOrDefault("streetnumber", "");
                if (snum.isEmpty()) snum = "525";
                String pc = a.getOrDefault("postalcode", "");
                String town = a.getOrDefault("town", "");
                String country = a.getOrDefault("country(isocode)", "");
                if (country.isEmpty()) country = "PR";
                String isBill = a.getOrDefault("billingAddress", "TRUE");
                String isShip = a.getOrDefault("shippingAddress", "TRUE");

                sb.append("                     ; ").append(pad(aId, 14))
                  .append("; ").append(pad(aId, 14))
                  .append("; ").append(pad(uUid, 39))
                  .append("; ").append(pad(fn, 11))
                  .append("; ").append(pad(ln, 13))
                  .append("; \"").append(sn).append("\"")
                  .append(" ; ").append(pad(snum, 13))
                  .append("; ").append(pad(pc, 11))
                  .append("; ").append(pad(town, 11))
                  .append("; ").append(pad(country, 17))
                  .append("; ").append(pad(isBill, 15))
                  .append("; ").append(isShip).append("\n");
            }
        }
        sb.append("\n");

        // 2.3 Credit Score Based Risk
        List<String> seenRisks = new ArrayList<>();
        for (String oId : selectedOrders) {
            Map<String, String> oData = ordersData.get(oId);
            String r = oData.getOrDefault("creditScoreRisk(code)", oData.getOrDefault("creditScoreRisk(id)", ""));
            if (!r.isEmpty() && !seenRisks.contains(r)) {
                seenRisks.add(r);
            }
        }

        if (!seenRisks.isEmpty()) {
            sb.append("# 2.3 Credit Score Based Risk (Prerequisite schema)\n");
            sb.append("INSERT_UPDATE CreditScoreBasedRisk; code[unique = true]; minScoreRange; maxScoreRange; minTenureAge; maxTenureAge; letterMapping\n");
            for (String r : seenRisks) {
                String letter = r.split("_")[0];
                sb.append("                                  ; ").append(pad(r, 19))
                  .append("; 300          ; 850          ; 0           ; 6           ; ").append(letter).append("\n");
            }
            sb.append("\n");

            // 2.4 Credit Score Tier
            sb.append("# 2.4 Credit Score Tier (Prerequisite schema)\n");
            sb.append("INSERT_UPDATE CreditScore; code[unique = true]; minScoreRange; maxScoreRange; contractStatus(code); pack    ; paperBilling; letterMapping\n");
            List<String> seenTiers = new ArrayList<>();
            for (String r : seenRisks) {
                String letter = r.split("_")[0];
                String tier = "CS_TIER_" + letter;
                if (!seenTiers.contains(tier)) {
                    seenTiers.add(tier);
                    String minRange = "B".equals(letter) ? "600" : ("A".equals(letter) ? "750" : "500");
                    String maxRange = "B".equals(letter) ? "749" : ("A".equals(letter) ? "850" : "599");
                    sb.append("                         ; ").append(pad(tier, 19))
                      .append("; ").append(pad(minRange, 13))
                      .append("; ").append(pad(maxRange, 13))
                      .append("; WITHCONTRACT        ; DEFAULT ; 0.0         ; ").append(letter).append("\n");
                }
            }
            sb.append("\n");
        }

        // 2.5 Delivery Mode
        sb.append("# 2.5 Delivery Mode (Standard Delivery)\n");
        sb.append("INSERT_UPDATE ZoneDeliveryMode; code[unique = true]; net ; active\n");
        sb.append("                              ; standard-gross     ; TRUE; TRUE\n\n");

        // 2.6 Payment Mode & Payment Info
        sb.append("# 2.6 Payment Mode & Payment Info\n");
        sb.append("INSERT_UPDATE StandardPaymentMode; code[unique = true]; active; paymentInfoType(code)\n");
        sb.append("                                 ; creditcard         ; TRUE  ; CreditCardPaymentInfo\n\n");

        sb.append("# Clear existing payment infos if re-running\n");
        sb.append("REMOVE CreditCardPaymentInfo[batchmode = true]; code[unique = true]\n");
        for (String oId : selectedOrders) {
            String cCode = orderCartCodes.get(oId);
            sb.append("                                              ; PAY_INFO_").append(oId).append("\n");
            if (includeUnderlyingCart && !cCode.isEmpty()) {
                sb.append("                                              ; PAY_INFO_").append(cCode).append("\n");
            }
        }
        sb.append("\n");

        sb.append("INSERT_UPDATE CreditCardPaymentInfo; &paymentInfo           ; code[unique = true]    ; user(uid)[unique = true]              ; billingAddress(&addrId) ; ccOwner         ; number             ; type(code); validToMonth; validToYear; subscriptionId\n");
        for (String oId : selectedOrders) {
            String cCode = orderCartCodes.get(oId);
            Map<String, String> uData = usersData.get(oId);
            Map<String, String> oData = ordersData.get(oId);
            String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));
            String uName = uData.getOrDefault("name", oData.getOrDefault("name", ""));
            List<Map<String, String>> addrs = addressesData.get(oId);
            String billAddrId = !addrs.isEmpty() ? addrs.get(0).getOrDefault("id[unique = true]", "PR_ADDR_15201") : "PR_ADDR_15201";

            // Order pay info
            sb.append("                                   ; ").append(pad("PAY_INFO_" + oId, 24))
              .append("; ").append(pad("PAY_INFO_" + oId, 24))
              .append("; ").append(pad(uUid, 38))
              .append("; ").append(pad(billAddrId, 16))
              .append("; \"").append(uName).append("\"     ; \"************1111\" ; visa      ; 12          ; 2030       ; \"SUB-").append(oId).append("\"\n");

            // Cart pay info
            if (includeUnderlyingCart && !cCode.isEmpty()) {
                sb.append("                                   ; ").append(pad("PAY_INFO_" + cCode, 24))
                  .append("; ").append(pad("PAY_INFO_" + cCode, 24))
                  .append("; ").append(pad(uUid, 38))
                  .append("; ").append(pad(billAddrId, 16))
                  .append("; \"").append(uName).append("\"     ; \"************1111\" ; visa      ; 12          ; 2030       ; \"SUB-").append(cCode).append("\"\n");
            }
        }
        sb.append("\n");

        // 2.7 Composite Order Prices
        sb.append("# 2.7 Composite Order Prices (TUA Composite Prices)\n");
        sb.append("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]      ; name                                  ; catalogCode\n");
        for (String oId : selectedOrders) {
            String cCode = orderCartCodes.get(oId);
            sb.append("                                            ; ").append(pad("price_" + oId, 23))
              .append("; \"Order Composite Price ").append(oId).append("\" ; $productCatalog\n");
            if (includeUnderlyingCart && !cCode.isEmpty()) {
                sb.append("                                            ; ").append(pad("price_" + cCode, 23))
                  .append("; \"Cart Composite Price ").append(cCode).append("\" ; $productCatalog\n");
            }
        }
        sb.append("\n");

        // 2.8 Order Total Prices
        sb.append("# 2.8 Order Total Prices\n");
        sb.append("INSERT TotalPrice; &tpId                  ; priceType(code)  ; value  ; isTaxIncluded; totalTax\n");
        for (String oId : selectedOrders) {
            String cCode = orderCartCodes.get(oId);
            Map<String, String> oData = ordersData.get(oId);
            String recPrice = oData.getOrDefault("orderMonthlyPrice", oData.getOrDefault("totalRecurringPrice", "0.00"));
            String duePrice = oData.getOrDefault("amountDueToday", "0.00");
            String tax = oData.getOrDefault("totalTax", "0.00");

            sb.append("                 ; ").append(pad("tp_rec_" + oId, 23))
              .append("; RECURRING        ; ").append(pad(recPrice, 6)).append(" ; TRUE         ; 0.00\n");
            sb.append("                 ; ").append(pad("tp_due_" + oId, 23))
              .append("; AMOUNT_DUE_TODAY ; ").append(pad(duePrice, 6)).append(" ; TRUE         ; ").append(tax).append("\n");

            if (includeUnderlyingCart && !cCode.isEmpty()) {
                sb.append("                 ; ").append(pad("tp_rec_" + cCode, 23))
                  .append("; RECURRING        ; ").append(pad(recPrice, 6)).append(" ; TRUE         ; 0.00\n");
                sb.append("                 ; ").append(pad("tp_due_" + cCode, 23))
                  .append("; AMOUNT_DUE_TODAY ; ").append(pad(duePrice, 6)).append(" ; TRUE         ; ").append(tax).append("\n");
            }
        }
        sb.append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 3. ORDER HEADERS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 3. ORDER HEADERS (All ").append(selectedOrders.size()).append(" Orders)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String orderHeaderStr = "INSERT_UPDATE Order; code[unique = true]; guid                                 ; cartNumber     ; user(uid)                            ; currency(isocode); site(uid); store(uid); date[dateformat = dd.MM.yyyy HH:mm:ss]; net[default = true]; calculated[default = true]; subtotal; totalPrice; totalTax; totalTaxValuesInternal               ; totalDiscounts; price(id)           ; prices(&tpId)                               ; deliveryAddress(&addrId) ; paymentAddress(&addrId) ; selectedAddress(&addrId) ; deliveryMode(code); paymentMode(code); paymentInfo(&paymentInfo); status(code); orderStatus(code); paymentStatus(code); paymentGateway(code); paymentType(code); planTypes(code); creditCheck; creditCheckEnum(code); creditScore; creditScoreLetter; creditScoreRisk(code); creditScoreLevel(code); llaCreditRiskRating; vertexApiCheck; vertexApiCheckEnum(code); orderVertexTaxAmnt; deliveryType ; storeId; storeName  ; storePickupAddress1; storePickupAddress2; storePickupCity; storePickupZip; customerEmail ; name           ; autoPayActive; billPreference; devicePlanCode; devicePrice; devicePriceType; isC2CRequestReceived; sourceSystem; numberOfExistingLine; numberOfFinancialDevices; numberOfLines; orderFeesAmount; orderMonthlyPrice; totalOneTimePrice; totalRecurringPrice; amountDueToday; amountDueFirstBill; isSSNSkipped; paymentJsonConsent";

        String[] orderHeaderCols = new String[]{
                "code[unique = true]", "guid", "cartNumber", "user(uid)", "currency(isocode)", "site(uid)", "store(uid)",
                "date[dateformat = dd.MM.yyyy HH:mm:ss]", "net[default = true]", "calculated[default = true]", "subtotal",
                "totalPrice", "totalTax", "totalTaxValuesInternal", "totalDiscounts", "price(id)", "prices(&tpId)",
                "deliveryAddress(&addrId)", "paymentAddress(&addrId)", "selectedAddress(&addrId)", "deliveryMode(code)",
                "paymentMode(code)", "paymentInfo(&paymentInfo)", "status(code)", "orderStatus(code)", "paymentStatus(code)",
                "paymentGateway(code)", "paymentType(code)", "planTypes(code)", "creditCheck", "creditCheckEnum(code)",
                "creditScore", "creditScoreLetter", "creditScoreRisk(code)", "creditScoreLevel(code)", "llaCreditRiskRating",
                "vertexApiCheck", "vertexApiCheckEnum(code)", "orderVertexTaxAmnt", "deliveryType", "storeId", "storeName",
                "storePickupAddress1", "storePickupAddress2", "storePickupCity", "storePickupZip", "customerEmail", "name",
                "autoPayActive", "billPreference", "devicePlanCode", "devicePrice", "devicePriceType", "isC2CRequestReceived",
                "sourceSystem", "numberOfExistingLine", "numberOfFinancialDevices", "numberOfLines", "orderFeesAmount",
                "orderMonthlyPrice", "totalOneTimePrice", "totalRecurringPrice", "amountDueToday", "amountDueFirstBill",
                "isSSNSkipped", "paymentJsonConsent"
        };

        sb.append(orderHeaderStr).append("\n");

        for (String oId : selectedOrders) {
            Map<String, String> oData = ordersData.get(oId);
            Map<String, String> uData = usersData.get(oId);
            String cCode = orderCartCodes.get(oId);
            String cGuid = orderCartGuids.get(oId);
            String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));
            String uName = uData.getOrDefault("name", oData.getOrDefault("name", ""));
            String email = uData.getOrDefault("contactEmail", oData.getOrDefault("customerEmail", ""));
            List<Map<String, String>> addrs = addressesData.get(oId);
            String addrId = !addrs.isEmpty() ? addrs.get(0).getOrDefault("id[unique = true]", "PR_ADDR_15201") : "PR_ADDR_15201";

            String totalTax = oData.getOrDefault("totalTax", "0.00");
            String taxValuesInternal = !totalTax.isEmpty() && !"0.00".equals(totalTax)
                    ? "\"[<TV<tax#" + totalTax + "#false#" + totalTax + "#USD>VT>]\""
                    : "";

            String rawScore = oData.getOrDefault("creditScore", "");
            String score = "";
            if (!rawScore.isEmpty()) {
                String cleanScore = rawScore.replace("+", "").trim();
                score = cleanScore.contains(".") ? cleanScore : cleanScore + ".0";
            }
            String letter = oData.getOrDefault("creditScoreLetter", "");
            String risk = oData.getOrDefault("creditScoreRisk(code)", oData.getOrDefault("creditScoreRisk(id)", ""));
            String tier = !letter.isEmpty() ? "CS_TIER_" + letter : "";
            String riskRating = oData.getOrDefault("llaCreditRiskRating", "");

            List<String> rowCells = new ArrayList<>();
            for (String col : orderHeaderCols) {
                String val = "";
                switch (col) {
                    case "code[unique = true]": val = oId; break;
                    case "guid": val = cGuid; break;
                    case "cartNumber": val = cCode; break;
                    case "user(uid)": val = uUid; break;
                    case "currency(isocode)": val = "$defaultCurrency"; break;
                    case "site(uid)": val = "$site"; break;
                    case "store(uid)": val = "$store"; break;
                    case "date[dateformat = dd.MM.yyyy HH:mm:ss]": val = oData.getOrDefault("date[dateformat = dd.MM.yyyy HH:mm:ss]", oData.getOrDefault("date", "")); break;
                    case "net[default = true]":
                    case "calculated[default = true]": val = "TRUE"; break;
                    case "subtotal": val = oData.getOrDefault("subtotal", "0.00"); break;
                    case "totalPrice": val = oData.getOrDefault("totalPrice", "0.00"); break;
                    case "totalTax": val = totalTax; break;
                    case "totalTaxValuesInternal": val = taxValuesInternal; break;
                    case "totalDiscounts": val = oData.getOrDefault("totalDiscounts", "0.00"); break;
                    case "price(id)": val = "price_" + oId; break;
                    case "prices(&tpId)": val = "tp_rec_" + oId + ",tp_due_" + oId; break;
                    case "deliveryAddress(&addrId)":
                    case "paymentAddress(&addrId)":
                    case "selectedAddress(&addrId)": val = addrId; break;
                    case "deliveryMode(code)": val = "standard-gross"; break;
                    case "paymentMode(code)": val = "creditcard"; break;
                    case "paymentInfo(&paymentInfo)": val = "PAY_INFO_" + oId; break;
                    case "status(code)":
                    case "orderStatus(code)": val = "CREATED"; break;
                    case "paymentStatus(code)": val = oData.getOrDefault("paymentStatus(code)", "NOTPAID"); break;
                    case "paymentGateway(code)": val = oData.getOrDefault("paymentGateway(code)", "Braintree"); break;
                    case "paymentType(code)": val = oData.getOrDefault("paymentType(code)", "CreditCard"); break;
                    case "planTypes(code)": val = oData.getOrDefault("planTypes(code)", "NEW_NUMBER"); break;
                    case "creditCheck": val = oData.getOrDefault("creditCheck", "FALSE"); break;
                    case "creditCheckEnum(code)": val = oData.getOrDefault("creditCheckEnum(code)", ""); break;
                    case "creditScore": val = score; break;
                    case "creditScoreLetter": val = letter; break;
                    case "creditScoreRisk(code)": val = risk; break;
                    case "creditScoreLevel(code)": val = tier; break;
                    case "llaCreditRiskRating": val = riskRating; break;
                    case "vertexApiCheck": val = "TRUE"; break;
                    case "vertexApiCheckEnum(code)": val = "DONE"; break;
                    case "orderVertexTaxAmnt": val = oData.getOrDefault("orderVertexTaxAmnt", "0.00"); break;
                    case "deliveryType": val = oData.getOrDefault("deliveryType", ""); break;
                    case "storeId": val = oData.getOrDefault("storeId", ""); break;
                    case "storeName": val = oData.getOrDefault("storeName", ""); break;
                    case "storePickupAddress1": val = oData.getOrDefault("storePickupAddress1", ""); break;
                    case "storePickupAddress2": val = oData.getOrDefault("storePickupAddress2", ""); break;
                    case "storePickupCity": val = oData.getOrDefault("storePickupCity", ""); break;
                    case "storePickupZip": val = oData.getOrDefault("storePickupZip", ""); break;
                    case "customerEmail": val = email; break;
                    case "name": val = uName; break;
                    case "autoPayActive": val = oData.getOrDefault("autoPayActive", "FALSE"); break;
                    case "billPreference": val = oData.getOrDefault("billPreference", "e_bill"); break;
                    case "devicePlanCode": val = oData.getOrDefault("devicePlanCode", ""); break;
                    case "devicePrice": val = oData.getOrDefault("devicePrice", "0.00"); break;
                    case "devicePriceType": val = oData.getOrDefault("devicePriceType", ""); break;
                    case "isC2CRequestReceived": val = oData.getOrDefault("isC2CRequestReceived", "FALSE"); break;
                    case "sourceSystem": val = oData.getOrDefault("sourceSystem", "WEB"); break;
                    case "numberOfExistingLine": val = oData.getOrDefault("numberOfExistingLine", "0"); break;
                    case "numberOfFinancialDevices": val = oData.getOrDefault("numberOfFinancialDevices", "0"); break;
                    case "numberOfLines": val = "1"; break;
                    case "orderFeesAmount": val = oData.getOrDefault("orderFeesAmount", "0.00"); break;
                    case "orderMonthlyPrice": val = oData.getOrDefault("orderMonthlyPrice", "0.00"); break;
                    case "totalOneTimePrice": val = oData.getOrDefault("totalOneTimePrice", "0.00"); break;
                    case "totalRecurringPrice": val = oData.getOrDefault("totalRecurringPrice", "0.00"); break;
                    case "amountDueToday": val = oData.getOrDefault("amountDueToday", "0.00"); break;
                    case "amountDueFirstBill": val = oData.getOrDefault("amountDueFirstBill", "0.00"); break;
                    case "isSSNSkipped": val = oData.getOrDefault("isSSNSkipped", "FALSE"); break;
                    case "paymentJsonConsent":
                        String pjc = oData.getOrDefault("paymentJsonConsent", "");
                        val = ImpexFormatter.formatCell(pjc);
                        break;
                    default:
                        val = oData.getOrDefault(col, "");
                        break;
                }
                rowCells.add(val);
            }
            sb.append("                   ; ").append(String.join(" ; ", rowCells)).append("\n");
        }
        sb.append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 4. ORDER LINE ITEMS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 4. ORDER LINE ITEMS (OrderEntry - All ").append(totalLineItems).append(" Entries)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String entryHeaderStr = "INSERT_UPDATE OrderEntry; order(code)[unique = true]; entryNumber[unique = true]; product(code, $onlineCatalogVersion); quantity; unit(code); basePrice; totalPrice; monthlyPrice; entryRecurringPrice; tax13Price; taxValuesInternal                   ; processType(code); calculated; giveAway; rejected; numberOfLines";

        sb.append(entryHeaderStr).append("\n");

        for (String oId : selectedOrders) {
            List<Map<String, String>> entries = entriesData.get(oId);
            for (int i = 0; i < entries.size(); i++) {
                Map<String, String> entry = entries.get(i);
                String eNum = entry.getOrDefault("entryNumber[unique = true]", entry.getOrDefault("entryNumber", String.valueOf(i)));
                String pCode = entry.getOrDefault("product(code, $onlineCatalogVersion)", entry.getOrDefault("product", ""));
                String qty = entry.getOrDefault("quantity", "1");
                String unit = entry.getOrDefault("unit(code)", "pieces");
                String bp = entry.getOrDefault("basePrice", "0.00");
                String tp = entry.getOrDefault("totalPrice", "0.00");
                String type = entry.getOrDefault("type", "");

                boolean isDevice = "DEVICE".equalsIgnoreCase(type) || pCode.startsWith("LLA_");
                String mp = isDevice ? "0.00" : tp;
                String rp = isDevice ? "0.00" : tp;

                String tax13 = "0.00";
                if (!isDevice) {
                    if ("Mobile_Plan_Mix_Core_PR".equals(pCode)) tax13 = "99.86";
                    else if ("PREPAID_Unlimited".equals(pCode)) tax13 = "10.93";
                    else if ("PREPAID_35GB".equals(pCode)) tax13 = "4.03";
                    else tax13 = "0.00";
                }

                String taxVals = !"0.00".equals(tax13)
                        ? "\"[<TV<tax#" + tax13 + "#false#" + tax13 + "#USD>VT>]\""
                        : "";

                List<String> eCells = new ArrayList<>();
                eCells.add(oId);
                eCells.add(eNum);
                eCells.add(pCode);
                eCells.add(qty);
                eCells.add(unit);
                eCells.add(bp);
                eCells.add(tp);
                eCells.add(mp);
                eCells.add(rp);
                eCells.add(tax13);
                eCells.add(taxVals);
                eCells.add("ACQUISITION");
                eCells.add("TRUE");
                eCells.add("FALSE");
                eCells.add("FALSE");
                eCells.add("1");

                sb.append("                        ; ").append(String.join(" ; ", eCells)).append("\n");
            }
        }
        sb.append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 5. CART SNAPSHOT
        // -----------------------------------------------------------------------------------------
        if (includeUnderlyingCart) {
            sb.append("# -----------------------------------------------------------------------------------------\n");
            sb.append("# 5. CART SNAPSHOT (Historical Associated Carts)\n");
            sb.append("# -----------------------------------------------------------------------------------------\n");

            String cartHeaderStr = "INSERT_UPDATE Cart; code[unique = true]; guid                                 ; user(uid)                            ; savedBy(uid)                         ; currency(isocode); site(uid); store(uid); date[dateformat = dd.MM.yyyy HH:mm:ss]; net[default = true]; calculated[default = true]; subtotal; totalPrice; totalTax; totalTaxValuesInternal               ; totalDiscounts; price(id)           ; prices(&tpId)                               ; deliveryAddress(&addrId) ; paymentAddress(&addrId) ; selectedAddress(&addrId) ; deliveryMode(code); paymentMode(code); paymentInfo(&paymentInfo); status(code); orderStatus(code); paymentStatus(code); paymentGateway(code); paymentType(code); planTypes(code); creditCheck; creditCheckEnum(code); creditScore; creditScoreLetter; creditScoreRisk(code); creditScoreLevel(code); llaCreditRiskRating; vertexApiCheck; vertexApiCheckEnum(code); orderVertexTaxAmnt; deliveryMethod; deliveryType ; storeId; storeName  ; selectedStoreID; storePickupAddress1; storePickupAddress2; storePickupCity; storePickupZip; phoneNumber  ; customerEmail ; name           ; autoPayActive; billPreference; devicePlanCode; devicePrice; devicePriceType; isC2CRequestReceived; customerDrop; sourceSystem; numberOfExistingLine; numberOfFinancialDevices; numberOfLines; orderFeesAmount; orderMonthlyPrice; totalOneTimePrice; totalRecurringPrice; amountDueToday; amountDueFirstBill; isSSNSkipped; paymentJsonConsent";

            String[] cartHeaderCols = new String[]{
                    "code[unique = true]", "guid", "user(uid)", "savedBy(uid)", "currency(isocode)", "site(uid)", "store(uid)",
                    "date[dateformat = dd.MM.yyyy HH:mm:ss]", "net[default = true]", "calculated[default = true]", "subtotal",
                    "totalPrice", "totalTax", "totalTaxValuesInternal", "totalDiscounts", "price(id)", "prices(&tpId)",
                    "deliveryAddress(&addrId)", "paymentAddress(&addrId)", "selectedAddress(&addrId)", "deliveryMode(code)",
                    "paymentMode(code)", "paymentInfo(&paymentInfo)", "status(code)", "orderStatus(code)", "paymentStatus(code)",
                    "paymentGateway(code)", "paymentType(code)", "planTypes(code)", "creditCheck", "creditCheckEnum(code)",
                    "creditScore", "creditScoreLetter", "creditScoreRisk(code)", "creditScoreLevel(code)", "llaCreditRiskRating",
                    "vertexApiCheck", "vertexApiCheckEnum(code)", "orderVertexTaxAmnt", "deliveryMethod", "deliveryType",
                    "storeId", "storeName", "selectedStoreID", "storePickupAddress1", "storePickupAddress2", "storePickupCity",
                    "storePickupZip", "phoneNumber", "customerEmail", "name", "autoPayActive", "billPreference", "devicePlanCode",
                    "devicePrice", "devicePriceType", "isC2CRequestReceived", "customerDrop", "sourceSystem", "numberOfExistingLine",
                    "numberOfFinancialDevices", "numberOfLines", "orderFeesAmount", "orderMonthlyPrice", "totalOneTimePrice",
                    "totalRecurringPrice", "amountDueToday", "amountDueFirstBill", "isSSNSkipped", "paymentJsonConsent"
            };

            sb.append(cartHeaderStr).append("\n");

            for (String oId : selectedOrders) {
                Map<String, String> oData = ordersData.get(oId);
                Map<String, String> uData = usersData.get(oId);
                String cCode = orderCartCodes.get(oId);
                String cGuid = orderCartGuids.get(oId);
                String uUid = uData.getOrDefault("uid", oData.getOrDefault("user(uid)", ""));
                String uName = uData.getOrDefault("name", oData.getOrDefault("name", ""));
                String email = uData.getOrDefault("contactEmail", oData.getOrDefault("customerEmail", ""));
                String phone = oData.getOrDefault("phoneNumber", "(787)000-0000");
                List<Map<String, String>> addrs = addressesData.get(oId);
                String addrId = !addrs.isEmpty() ? addrs.get(0).getOrDefault("id[unique = true]", "PR_ADDR_15201") : "PR_ADDR_15201";

                String totalTax = oData.getOrDefault("totalTax", "0.00");
                String taxValuesInternal = !totalTax.isEmpty() && !"0.00".equals(totalTax)
                        ? "\"[<TV<tax#" + totalTax + "#false#" + totalTax + "#USD>VT>]\""
                        : "";

                String rawScore = oData.getOrDefault("creditScore", "");
                String score = "";
                if (!rawScore.isEmpty()) {
                    String cleanScore = rawScore.replace("+", "").trim();
                    score = cleanScore.contains(".") ? cleanScore : cleanScore + ".0";
                }
                String letter = oData.getOrDefault("creditScoreLetter", "");
                String risk = oData.getOrDefault("creditScoreRisk(code)", oData.getOrDefault("creditScoreRisk(id)", ""));
                String tier = !letter.isEmpty() ? "CS_TIER_" + letter : "";
                String riskRating = oData.getOrDefault("llaCreditRiskRating", "");

                List<String> rowCells = new ArrayList<>();
                for (String col : cartHeaderCols) {
                    String val = "";
                    switch (col) {
                        case "code[unique = true]": val = cCode; break;
                        case "guid": val = cGuid; break;
                        case "user(uid)":
                        case "savedBy(uid)": val = uUid; break;
                        case "currency(isocode)": val = "$defaultCurrency"; break;
                        case "site(uid)": val = "$site"; break;
                        case "store(uid)": val = "$store"; break;
                        case "date[dateformat = dd.MM.yyyy HH:mm:ss]": val = oData.getOrDefault("date[dateformat = dd.MM.yyyy HH:mm:ss]", oData.getOrDefault("date", "")); break;
                        case "net[default = true]":
                        case "calculated[default = true]": val = "TRUE"; break;
                        case "subtotal": val = oData.getOrDefault("subtotal", "0.00"); break;
                        case "totalPrice": val = oData.getOrDefault("totalPrice", "0.00"); break;
                        case "totalTax": val = totalTax; break;
                        case "totalTaxValuesInternal": val = taxValuesInternal; break;
                        case "totalDiscounts": val = oData.getOrDefault("totalDiscounts", "0.00"); break;
                        case "price(id)": val = "price_" + cCode; break;
                        case "prices(&tpId)": val = "tp_rec_" + cCode + ",tp_due_" + cCode; break;
                        case "deliveryAddress(&addrId)":
                        case "paymentAddress(&addrId)":
                        case "selectedAddress(&addrId)": val = addrId; break;
                        case "deliveryMode(code)": val = "standard-gross"; break;
                        case "paymentMode(code)": val = "creditcard"; break;
                        case "paymentInfo(&paymentInfo)": val = "PAY_INFO_" + cCode; break;
                        case "status(code)":
                        case "orderStatus(code)": val = "CREATED"; break;
                        case "paymentStatus(code)": val = oData.getOrDefault("paymentStatus(code)", "NOTPAID"); break;
                        case "paymentGateway(code)": val = oData.getOrDefault("paymentGateway(code)", "Braintree"); break;
                        case "paymentType(code)": val = oData.getOrDefault("paymentType(code)", "CreditCard"); break;
                        case "planTypes(code)": val = oData.getOrDefault("planTypes(code)", "NEW_NUMBER"); break;
                        case "creditCheck": val = oData.getOrDefault("creditCheck", "FALSE"); break;
                        case "creditCheckEnum(code)": val = oData.getOrDefault("creditCheckEnum(code)", ""); break;
                        case "creditScore": val = score; break;
                        case "creditScoreLetter": val = letter; break;
                        case "creditScoreRisk(code)": val = risk; break;
                        case "creditScoreLevel(code)": val = tier; break;
                        case "llaCreditRiskRating": val = riskRating; break;
                        case "vertexApiCheck": val = "TRUE"; break;
                        case "vertexApiCheckEnum(code)": val = "DONE"; break;
                        case "orderVertexTaxAmnt": val = oData.getOrDefault("orderVertexTaxAmnt", "0.00"); break;
                        case "deliveryMethod": val = oData.getOrDefault("deliveryMethod", ""); break;
                        case "deliveryType": val = oData.getOrDefault("deliveryType", ""); break;
                        case "storeId": val = oData.getOrDefault("storeId", ""); break;
                        case "storeName": val = oData.getOrDefault("storeName", ""); break;
                        case "selectedStoreID": val = oData.getOrDefault("selectedStoreID", ""); break;
                        case "storePickupAddress1": val = oData.getOrDefault("storePickupAddress1", ""); break;
                        case "storePickupAddress2": val = oData.getOrDefault("storePickupAddress2", ""); break;
                        case "storePickupCity": val = oData.getOrDefault("storePickupCity", ""); break;
                        case "storePickupZip": val = oData.getOrDefault("storePickupZip", ""); break;
                        case "phoneNumber": val = phone; break;
                        case "customerEmail": val = email; break;
                        case "name": val = uName; break;
                        case "autoPayActive": val = oData.getOrDefault("autoPayActive", "FALSE"); break;
                        case "billPreference": val = oData.getOrDefault("billPreference", "e_bill"); break;
                        case "devicePlanCode": val = oData.getOrDefault("devicePlanCode", ""); break;
                        case "devicePrice": val = oData.getOrDefault("devicePrice", "0.00"); break;
                        case "devicePriceType": val = oData.getOrDefault("devicePriceType", ""); break;
                        case "isC2CRequestReceived": val = oData.getOrDefault("isC2CRequestReceived", "FALSE"); break;
                        case "customerDrop": val = oData.getOrDefault("customerDrop", "FALSE"); break;
                        case "sourceSystem": val = oData.getOrDefault("sourceSystem", "WEB"); break;
                        case "numberOfExistingLine": val = oData.getOrDefault("numberOfExistingLine", "0"); break;
                        case "numberOfFinancialDevices": val = oData.getOrDefault("numberOfFinancialDevices", "0"); break;
                        case "numberOfLines": val = "1"; break;
                        case "orderFeesAmount": val = oData.getOrDefault("orderFeesAmount", "0.00"); break;
                        case "orderMonthlyPrice": val = oData.getOrDefault("orderMonthlyPrice", "0.00"); break;
                        case "totalOneTimePrice": val = oData.getOrDefault("totalOneTimePrice", "0.00"); break;
                        case "totalRecurringPrice": val = oData.getOrDefault("totalRecurringPrice", "0.00"); break;
                        case "amountDueToday": val = oData.getOrDefault("amountDueToday", "0.00"); break;
                        case "amountDueFirstBill": val = oData.getOrDefault("amountDueFirstBill", "0.00"); break;
                        case "isSSNSkipped": val = oData.getOrDefault("isSSNSkipped", "FALSE"); break;
                        case "paymentJsonConsent":
                            String pjc = oData.getOrDefault("paymentJsonConsent", "");
                            val = ImpexFormatter.formatCell(pjc);
                            break;
                        default:
                            val = oData.getOrDefault(col, "");
                            break;
                    }
                    rowCells.add(val);
                }
                sb.append("                  ; ").append(String.join(" ; ", rowCells)).append("\n");
            }
            sb.append("\n");

            String cartEntryHeaderStr = "INSERT_UPDATE CartEntry; order(code)[unique = true]; entryNumber[unique = true]; product(code, $onlineCatalogVersion); quantity; unit(code); basePrice; totalPrice; monthlyPrice; entryRecurringPrice; tax13Price; taxValuesInternal                   ; processType(code); calculated; giveAway; rejected; numberOfLines";

            sb.append(cartEntryHeaderStr).append("\n");
            for (String oId : selectedOrders) {
                String cCode = orderCartCodes.get(oId);
                List<Map<String, String>> entries = entriesData.get(oId);
                for (int i = 0; i < entries.size(); i++) {
                    Map<String, String> entry = entries.get(i);
                    String eNum = entry.getOrDefault("entryNumber[unique = true]", entry.getOrDefault("entryNumber", String.valueOf(i)));
                    String pCode = entry.getOrDefault("product(code, $onlineCatalogVersion)", entry.getOrDefault("product", ""));
                    String qty = entry.getOrDefault("quantity", "1");
                    String unit = entry.getOrDefault("unit(code)", "pieces");
                    String bp = entry.getOrDefault("basePrice", "0.00");
                    String tp = entry.getOrDefault("totalPrice", "0.00");
                    String type = entry.getOrDefault("type", "");

                    boolean isDevice = "DEVICE".equalsIgnoreCase(type) || pCode.startsWith("LLA_");
                    String mp = isDevice ? "0.00" : tp;
                    String rp = isDevice ? "0.00" : tp;

                    String tax13 = "0.00";
                    if (!isDevice) {
                        if ("Mobile_Plan_Mix_Core_PR".equals(pCode)) tax13 = "99.86";
                        else if ("PREPAID_Unlimited".equals(pCode)) tax13 = "10.93";
                        else if ("PREPAID_35GB".equals(pCode)) tax13 = "4.03";
                        else tax13 = "0.00";
                    }

                    String taxVals = !"0.00".equals(tax13)
                            ? "\"[<TV<tax#" + tax13 + "#false#" + tax13 + "#USD>VT>]\""
                            : "";

                    List<String> eCells = new ArrayList<>();
                    eCells.add(cCode);
                    eCells.add(eNum);
                    eCells.add(pCode);
                    eCells.add(qty);
                    eCells.add(unit);
                    eCells.add(bp);
                    eCells.add(tp);
                    eCells.add(mp);
                    eCells.add(rp);
                    eCells.add(tax13);
                    eCells.add(taxVals);
                    eCells.add("ACQUISITION");
                    eCells.add("TRUE");
                    eCells.add("FALSE");
                    eCells.add("FALSE");
                    eCells.add("1");

                    sb.append("                       ; ").append(String.join(" ; ", eCells)).append("\n");
                }
            }
            sb.append("\n\n");
        }

        // -----------------------------------------------------------------------------------------
        // 6. PROMOTION ENGINE OBJECTS
        // -----------------------------------------------------------------------------------------
        List<Map<String, String>> promoItems = new ArrayList<>();
        for (String oId : selectedOrders) {
            String cCode = orderCartCodes.get(oId);
            List<Map<String, String>> entries = entriesData.get(oId);
            for (int i = 0; i < entries.size(); i++) {
                Map<String, String> entry = entries.get(i);
                String applied = entry.getOrDefault("promotionApplied", "");
                if ("TRUE".equalsIgnoreCase(applied)) {
                    Map<String, String> pItem = new HashMap<>();
                    pItem.put("orderId", oId);
                    pItem.put("cartCode", cCode);
                    pItem.put("entryNumber", entry.getOrDefault("entryNumber[unique = true]", entry.getOrDefault("entryNumber", String.valueOf(i))));
                    pItem.put("promotionName", entry.getOrDefault("promotionName", "Mix Core_new"));
                    pItem.put("promotionPromoType", entry.getOrDefault("promotionPromoType", "on_us"));
                    pItem.put("discountAmount", entry.getOrDefault("discountAmount", "0.00"));
                    pItem.put("totalPrice", entry.getOrDefault("totalPrice", "0.00"));
                    pItem.put("quantity", entry.getOrDefault("quantity", "1"));
                    promoItems.add(pItem);
                }
            }
        }

        if (!promoItems.isEmpty()) {
            sb.append("# -----------------------------------------------------------------------------------------\n");
            sb.append("# 6. PROMOTION ENGINE OBJECTS (Promotional Discount on Devices)\n");
            sb.append("# -----------------------------------------------------------------------------------------\n");

            // 6.1 Promotion Results
            sb.append("# 6.1 Promotion Results\n");
            sb.append("INSERT PromotionResult; &promoResId                       ; order(code)    ; certainty[default = 1.0] ; custom                        ; messageFired[lang = en]               ; rulesModuleName\n");
            for (Map<String, String> p : promoItems) {
                String oId = p.get("orderId");
                String cCode = p.get("cartCode");
                String pName = p.get("promotionName");
                String pType = p.get("promotionPromoType");
                String custom = "\"" + pName + " " + pType + "\"";
                String msg = "\"Device promo: " + pName + "\"";

                sb.append("                      ; ").append(pad("promo_res_" + oId, 28))
                  .append("; ").append(pad(oId, 8))
                  .append(" ; 1.0                      ; ").append(pad(custom, 21))
                  .append("; ").append(pad(msg, 29))
                  .append("; \"promotions-module\"\n");

                if (includeUnderlyingCart && !cCode.isEmpty()) {
                    sb.append("                      ; ").append(pad("promo_res_cart_" + cCode, 28))
                      .append("; ").append(pad(cCode, 14))
                      .append(" ; 1.0                      ; ").append(pad(custom, 21))
                      .append("; ").append(pad(msg, 29))
                      .append("; \"promotions-module\"\n");
                }
            }
            sb.append("\n");

            // 6.2 Promotion Order Entry Adjust Action
            sb.append("# 6.2 Promotion Order Entry Adjust Action\n");
            sb.append("INSERT_UPDATE PromotionOrderEntryAdjustAction; guid[unique = true]                  ; promotionResult(&promoResId)      ; amount  ; orderEntry(order(code), entryNumber) ; markedApplied[default = true]\n");
            for (Map<String, String> p : promoItems) {
                String oId = p.get("orderId");
                String cCode = p.get("cartCode");
                String eNum = p.get("entryNumber");
                String amt = p.get("discountAmount");

                sb.append("                                             ; ").append(pad("ACT_PROMO_" + oId + "_" + eNum, 29))
                  .append(" ; ").append(pad("promo_res_" + oId, 28))
                  .append("; ").append(pad(amt, 6))
                  .append(" ; ").append(pad(oId + ":" + eNum, 40))
                  .append("; TRUE\n");

                if (includeUnderlyingCart && !cCode.isEmpty()) {
                    sb.append("                                             ; ").append(pad("ACT_PROMO_CART_" + cCode + "_" + eNum, 35))
                      .append("; ").append(pad("promo_res_cart_" + cCode, 28))
                      .append("; ").append(pad(amt, 6))
                      .append(" ; ").append(pad(cCode + ":" + eNum, 20))
                      .append(" ; TRUE\n");
                }
            }
            sb.append("\n");

            // 6.3 Promotion Order Entry Consumed
            sb.append("# 6.3 Promotion Order Entry Consumed\n");
            sb.append("INSERT PromotionOrderEntryConsumed; code[unique = true]                 ; promotionResult(&promoResId)      ; orderEntry(order(code), entryNumber) ; quantity ; adjustedUnitPrice\n");
            for (Map<String, String> p : promoItems) {
                String oId = p.get("orderId");
                String cCode = p.get("cartCode");
                String eNum = p.get("entryNumber");
                String qty = p.get("quantity");
                String unitPrice = p.get("totalPrice");

                sb.append("                                  ; ").append(pad("CONS_" + oId + "_" + eNum, 25))
                  .append(" ; ").append(pad("promo_res_" + oId, 28))
                  .append("; ").append(pad(oId + ":" + eNum, 40))
                  .append("; ").append(pad(qty, 7))
                  .append("  ; ").append(unitPrice).append("\n");

                if (includeUnderlyingCart && !cCode.isEmpty()) {
                    sb.append("                                  ; ").append(pad("CONS_CART_" + cCode + "_" + eNum, 31))
                      .append("; ").append(pad("promo_res_cart_" + cCode, 28))
                      .append("; ").append(pad(cCode + ":" + eNum, 20))
                      .append(" ; ").append(pad(qty, 7))
                      .append("  ; ").append(unitPrice).append("\n");
                }
            }
        }

        return sb.toString();
    }

    public static String formatProductName(String code) {
        if (code == null || code.isEmpty()) return "";
        if (code.startsWith("PREPAID_")) {
            String rest = code.substring("PREPAID_".length());
            return "Prepaid " + rest + (rest.endsWith("Plan") ? "" : " Plan");
        }
        String[] parts = code.split("_");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) continue;
            if (i > 0) sb.append(" ");
            if (p.equalsIgnoreCase("black")) {
                sb.append("Black");
            } else if (p.equalsIgnoreCase("white")) {
                sb.append("White");
            } else {
                sb.append(p);
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
