package com.paytm.hybris.impex;

import java.util.*;

/**
 * Builds production-grade SAP Commerce (Hybris) ImpEx from abandoned cart export workbooks,
 * matching the consolidated format and schema of the reference ImpEx file.
 */
public class CartImpexBuilder {

    public static String build(Map<String, XlsxReader.SheetData> sheets, String sourceFileName, String targetCartCode) {
        XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
        if (primSheet == null) {
            throw new IllegalArgumentException("CartPrimitive sheet not found in workbook.");
        }

        // Identify available carts
        int cartCodeCol = findCol(primSheet.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
        int cartIdCol = findCol(primSheet.getHeaders(), "cartId");

        List<String> allCartCodes = new ArrayList<>();
        Map<String, String> codeToCartId = new LinkedHashMap<>();

        for (List<String> row : primSheet.getRows()) {
            String cCode = getVal(row, cartCodeCol);
            String cId = getVal(row, cartIdCol);
            if (!cCode.isEmpty() && !allCartCodes.contains(cCode)) {
                allCartCodes.add(cCode);
                codeToCartId.put(cCode, cId);
            }
        }

        if (allCartCodes.isEmpty()) {
            throw new IllegalArgumentException("No cart records found in CartPrimitive sheet.");
        }

        List<String> selectedCarts = new ArrayList<>();
        if (targetCartCode != null && !targetCartCode.trim().isEmpty()) {
            String t = targetCartCode.trim();
            if (allCartCodes.contains(t)) {
                selectedCarts.add(t);
            } else {
                throw new IllegalArgumentException("Cart code " + targetCartCode + " not found in file.");
            }
        } else {
            selectedCarts.addAll(allCartCodes);
        }

        // -----------------------------------------------------------------------------------------
        // 1. Gather all attributes across sheets for selected carts
        // -----------------------------------------------------------------------------------------
        String[] cartSheets = new String[]{"CartPrimitive", "CartNonPrimitive", "CartItemTypeRefs", "CartCollectionRefs", "CartEnumRefs"};
        Map<String, Map<String, String>> cartsData = new LinkedHashMap<>();
        for (String cCode : selectedCarts) {
            cartsData.put(cCode, new LinkedHashMap<>());
        }

        for (String sName : cartSheets) {
            XlsxReader.SheetData sData = sheets.get(sName);
            if (sData == null) continue;
            int cCol = findCol(sData.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
            int idCol = findCol(sData.getHeaders(), "cartId");
            for (List<String> row : sData.getRows()) {
                String rowCCode = getVal(row, cCol);
                String rowCId = getVal(row, idCol);
                String matchedCCode = null;
                if (!rowCCode.isEmpty() && cartsData.containsKey(rowCCode)) {
                    matchedCCode = rowCCode;
                } else if (!rowCId.isEmpty()) {
                    for (Map.Entry<String, String> entry : codeToCartId.entrySet()) {
                        if (entry.getValue().equals(rowCId) && cartsData.containsKey(entry.getKey())) {
                            matchedCCode = entry.getKey();
                            break;
                        }
                    }
                }
                if (matchedCCode != null) {
                    Map<String, String> targetMap = cartsData.get(matchedCCode);
                    for (int c = 0; c < sData.getHeaders().size(); c++) {
                        String h = sData.getHeaders().get(c).trim();
                        String v = getVal(row, c);
                        if (!v.isEmpty()) {
                            targetMap.put(h, v);
                            targetMap.put(h.replaceAll("\\s+", "").toLowerCase(), v);
                        }
                    }
                }
            }
        }

        // Users Sheet (PrereqUsers)
        Map<String, Map<String, String>> usersData = new LinkedHashMap<>();
        XlsxReader.SheetData userSheet = sheets.get("PrereqUsers");
        if (userSheet != null) {
            int idCol = findCol(userSheet.getHeaders(), "cartId");
            int uidCol = findCol(userSheet.getHeaders(), "uid");
            for (List<String> row : userSheet.getRows()) {
                String rCartId = getVal(row, idCol);
                String rUid = getVal(row, uidCol);
                for (String cCode : selectedCarts) {
                    String myCartId = codeToCartId.get(cCode);
                    if ((myCartId != null && myCartId.equals(rCartId)) || (rUid != null && rUid.equals(cartsData.get(cCode).get("user(uid)")))) {
                        Map<String, String> uMap = new LinkedHashMap<>();
                        for (int c = 0; c < userSheet.getHeaders().size(); c++) {
                            uMap.put(userSheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        usersData.put(cCode, uMap);
                        break;
                    }
                }
            }
        }
        for (String cCode : selectedCarts) {
            usersData.putIfAbsent(cCode, new LinkedHashMap<>());
        }

        // Addresses Sheet (PrereqAddresses)
        Map<String, List<Map<String, String>>> addressesData = new LinkedHashMap<>();
        for (String cCode : selectedCarts) {
            addressesData.put(cCode, new ArrayList<>());
        }
        XlsxReader.SheetData addrSheet = sheets.get("PrereqAddresses");
        if (addrSheet != null) {
            int idCol = findCol(addrSheet.getHeaders(), "cartId");
            int ownerCol = findCol(addrSheet.getHeaders(), "owner(Customer.uid)[unique = true]", "owner");
            for (List<String> row : addrSheet.getRows()) {
                String rCartId = getVal(row, idCol);
                String rOwner = getVal(row, ownerCol);
                for (String cCode : selectedCarts) {
                    String myCartId = codeToCartId.get(cCode);
                    String userUid = usersData.get(cCode).getOrDefault("uid", cartsData.get(cCode).getOrDefault("user(uid)", ""));
                    if ((myCartId != null && myCartId.equals(rCartId)) || (!userUid.isEmpty() && userUid.equals(rOwner))) {
                        Map<String, String> aMap = new LinkedHashMap<>();
                        for (int c = 0; c < addrSheet.getHeaders().size(); c++) {
                            aMap.put(addrSheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        addressesData.get(cCode).add(aMap);
                        break;
                    }
                }
            }
        }

        // CartEntries Sheet
        Map<String, List<Map<String, String>>> entriesData = new LinkedHashMap<>();
        for (String cCode : selectedCarts) {
            entriesData.put(cCode, new ArrayList<>());
        }
        XlsxReader.SheetData entrySheet = sheets.get("CartEntries");
        if (entrySheet != null) {
            int oCol = findCol(entrySheet.getHeaders(), "order(code)[unique = true]", "order(code)", "order");
            int idCol = findCol(entrySheet.getHeaders(), "cartId");
            for (List<String> row : entrySheet.getRows()) {
                String rCode = getVal(row, oCol);
                String rId = getVal(row, idCol);
                for (String cCode : selectedCarts) {
                    String myCartId = codeToCartId.get(cCode);
                    if (cCode.equals(rCode) || (myCartId != null && myCartId.equals(rId))) {
                        Map<String, String> eMap = new LinkedHashMap<>();
                        for (int c = 0; c < entrySheet.getHeaders().size(); c++) {
                            eMap.put(entrySheet.getHeaders().get(c).trim(), getVal(row, c));
                        }
                        entriesData.get(cCode).add(eMap);
                        break;
                    }
                }
            }
        }

        // Extract Unique Products
        Set<String> uniqueProducts = new LinkedHashSet<>();
        int totalLineItems = 0;
        for (String cCode : selectedCarts) {
            List<Map<String, String>> entries = entriesData.get(cCode);
            totalLineItems += entries.size();
            for (Map<String, String> e : entries) {
                String pCode = e.getOrDefault("product(code, $onlineCatalogVersion)", e.getOrDefault("product", ""));
                if (!pCode.isEmpty()) {
                    uniqueProducts.add(pCode);
                }
            }
        }

        // Extract Unique Customers
        Map<String, Map<String, String>> uniqueCustomers = new LinkedHashMap<>();
        for (String cCode : selectedCarts) {
            Map<String, String> uData = usersData.get(cCode);
            Map<String, String> cData = cartsData.get(cCode);
            String uid = uData.getOrDefault("uid", cData.getOrDefault("user(uid)", cData.getOrDefault("uid", "")));
            if (!uid.isEmpty() && !uniqueCustomers.containsKey(uid)) {
                Map<String, String> cust = new LinkedHashMap<>(uData);
                cust.put("cartCode", cCode);
                cust.put("cartData", cData.toString());
                uniqueCustomers.put(uid, cust);
            }
        }

        // Extract Unique Addresses
        Map<String, Map<String, String>> uniqueAddresses = new LinkedHashMap<>();
        for (String cCode : selectedCarts) {
            List<Map<String, String>> addrs = addressesData.get(cCode);
            for (Map<String, String> addr : addrs) {
                String aId = addr.getOrDefault("id[unique = true]", addr.getOrDefault("id", ""));
                if (!aId.isEmpty() && !uniqueAddresses.containsKey(aId)) {
                    uniqueAddresses.put(aId, addr);
                }
            }
        }

        // Extract Unique Credit Risks & Scores
        Set<String> uniqueRisks = new LinkedHashSet<>();
        Set<String> uniqueCreditScores = new LinkedHashSet<>();
        for (String cCode : selectedCarts) {
            Map<String, String> cData = cartsData.get(cCode);
            String risk = cData.getOrDefault("creditScoreRisk(code)", cData.getOrDefault("creditScoreRisk(id)", ""));
            if (!risk.isEmpty()) uniqueRisks.add(risk);
            String letter = cData.getOrDefault("creditScoreLetter", "");
            if (!letter.isEmpty()) uniqueCreditScores.add("CS_TIER_" + letter);
        }

        // Check for Promotions
        List<Map<String, String>> promoItems = new ArrayList<>();
        for (String cCode : selectedCarts) {
            List<Map<String, String>> entries = entriesData.get(cCode);
            for (int i = 0; i < entries.size(); i++) {
                Map<String, String> entry = entries.get(i);
                String promoApp = entry.getOrDefault("promotionApplied", "FALSE");
                if ("TRUE".equalsIgnoreCase(promoApp)) {
                    Map<String, String> pItem = new HashMap<>(entry);
                    pItem.put("cartCode", cCode);
                    pItem.put("entryNumber", entry.getOrDefault("entryNumber[unique = true]", String.valueOf(i)));
                    promoItems.add(pItem);
                }
            }
        }

        // -----------------------------------------------------------------------------------------
        // Generate ImpEx Content
        // -----------------------------------------------------------------------------------------
        StringBuilder sb = new StringBuilder();

        // Header Comment
        sb.append("# =========================================================================================\n");
        sb.append("# ImpEx: Abandoned Cart Import for All Records\n");
        sb.append("# Target Tenant: Puerto Rico (PR)\n");
        sb.append("# Source Export File: ").append(sourceFileName).append("\n");
        sb.append("# Carts Included: ").append(selectedCarts.size()).append(" Carts (").append(totalLineItems).append(" Entry Line Items)\n");
        int cartIdx = 1;
        for (String cCode : selectedCarts) {
            Map<String, String> cData = cartsData.get(cCode);
            Map<String, String> uData = usersData.get(cCode);
            String uName = uData.getOrDefault("name", cData.getOrDefault("name", "Unknown"));
            uName = uName.replaceAll("\\s+", " ").trim();
            String uUid = uData.getOrDefault("uid", cData.getOrDefault("user(uid)", ""));
            String risk = cData.getOrDefault("creditScoreRisk(code)", "No Credit");
            String creditInfo = risk.isEmpty() || "No Credit".equals(risk) ? "Prepaid: No Credit" : "Credit: " + risk;
            sb.append("#   ").append(cartIdx++).append(". Cart ").append(cCode)
              .append(" | User: ").append(uName).append(" / ").append(uUid)
              .append(" | ").append(creditInfo).append("\n");
        }
        sb.append("# =========================================================================================\n\n");

        // Section 1: Macros
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 1. MACROS & CATALOG CONFIGURATION\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("$site = puertorico\n");
        sb.append("$store = puertoricoStore\n");
        sb.append("$productCatalog = puertoricoProductCatalog\n");
        sb.append("$onlineCatalogVersion = catalogversion(catalog(id[default = $productCatalog]), version[default = 'Online'])[unique = true, default = $productCatalog:Online]\n");
        sb.append("$defaultCurrency = USD\n\n");

        // Section 2: Prerequisites
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 2. PREREQUISITES: PRODUCTS, CUSTOMERS, ADDRESSES, RISKS, DELIVERY/PAYMENT MODES & PRICING\n");
        sb.append("# -----------------------------------------------------------------------------------------\n\n");

        // 2.0 Product Prerequisites
        sb.append("# 2.0 Product Offering Prerequisites (Ensures catalog items exist)\n");
        sb.append("INSERT_UPDATE Product; code[unique = true]        ; catalogVersion(catalog(id), version)[unique = true]; name[lang = en]            ; unit(code)[default = 'pieces']; approvalStatus(code)[default = 'approved']\n");
        for (String pCode : uniqueProducts) {
            String pName = formatProductName(pCode);
            sb.append("                     ; ").append(pad(pCode, 28))
              .append("; $productCatalog:Online                             ; \"").append(pName).append("\"   ;                               ;\n");
        }
        sb.append("\n");

        // 2.1 Customers
        sb.append("# 2.1 Customers (from PrereqUsers & CartPrimitive)\n");
        sb.append("INSERT_UPDATE Customer; uid[unique = true]                    ; name            ; contactEmail                   ; mobilePhone   ; customerID                            ; groups(uid)   ; sessionLanguage(isocode); sessionCurrency(isocode); existingCustomer; dateOfBirth[dateformat = yyyy-MM-dd]; correlationId\n");
        for (Map.Entry<String, Map<String, String>> entry : uniqueCustomers.entrySet()) {
            String uid = entry.getKey();
            Map<String, String> u = entry.getValue();
            String cCode = u.get("cartCode");
            Map<String, String> cData = cartsData.get(cCode);
            String name = u.getOrDefault("name", cData.getOrDefault("name", ""));
            String email = u.getOrDefault("contactEmail", cData.getOrDefault("customerEmail", ""));
            String phone = cData.getOrDefault("phoneNumber", "(787)000-0000");
            String groups = u.getOrDefault("groups(uid)", "customergroup");
            String lang = u.getOrDefault("sessionLanguage(isocode)", "en");
            if (lang.contains("-")) lang = lang.split("-")[0];
            String existing = cData.getOrDefault("isCustomerExisting", "FALSE");

            sb.append("                      ; ").append(pad(uid, 40))
              .append("; ").append(pad(name, 16))
              .append("; ").append(pad(email, 30))
              .append("; ").append(pad(phone, 13))
              .append(" ; ").append(pad(uid, 38))
              .append("; ").append(pad(groups, 13))
              .append(" ; ").append(pad(lang, 23))
              .append(" ; $defaultCurrency        ; ").append(pad(existing, 15))
              .append(" ; 1990-01-01                          ; CORR-").append(cCode).append("\n");
        }
        sb.append("\n");

        // 2.2 Addresses
        sb.append("# 2.2 Addresses (Delivery & Billing Addresses for Puerto Rico)\n");
        sb.append("INSERT_UPDATE Address; &addrId       ; id[unique = true]; owner(Customer.uid)[unique = true]    ; firstname ; lastname ; streetname ; streetnumber ; postalcode ; town      ; country(isocode) ; billingAddress ; shippingAddress\n");
        for (Map.Entry<String, Map<String, String>> entry : uniqueAddresses.entrySet()) {
            String aId = entry.getKey();
            Map<String, String> a = entry.getValue();
            String owner = a.getOrDefault("owner(Customer.uid)[unique = true]", a.getOrDefault("owner", ""));
            String fn = a.getOrDefault("firstname", "");
            String ln = a.getOrDefault("lastname", "");
            String sn = a.getOrDefault("streetname", "");
            String snum = a.getOrDefault("streetnumber", "525");
            String pc = a.getOrDefault("postalcode", "00729");
            String town = a.getOrDefault("town", "Canovanas");
            String country = a.getOrDefault("country(isocode)", "PR");
            if (country.isEmpty()) country = "PR";

            sb.append("                     ; ").append(pad(aId, 13))
              .append(" ; ").append(pad(aId, 13))
              .append(" ; ").append(pad(owner, 38))
              .append("; ").append(pad(fn, 10))
              .append(" ; ").append(pad(ln, 12))
              .append("; \"").append(sn).append("\" ; ").append(pad(snum, 12))
              .append("; ").append(pad(pc, 10))
              .append(" ; ").append(pad(town, 10))
              .append(" ; ").append(pad(country, 16))
              .append(" ; TRUE           ; TRUE\n");
        }
        sb.append("\n");

        // 2.3 CreditScoreBasedRisk
        if (!uniqueRisks.isEmpty()) {
            sb.append("# 2.3 Credit Score Based Risk (Prerequisite schema)\n");
            sb.append("INSERT_UPDATE CreditScoreBasedRisk; code[unique = true]; minScoreRange; maxScoreRange; minTenureAge; maxTenureAge; letterMapping\n");
            for (String risk : uniqueRisks) {
                String letter = risk.contains("_") ? risk.split("_")[0] : "A";
                sb.append("                                  ; ").append(pad(risk, 18))
                  .append(" ; 300          ; 850          ; 0           ; 6           ; ").append(letter).append("\n");
            }
            sb.append("\n");
        }

        // 2.4 CreditScore Tier
        if (!uniqueCreditScores.isEmpty()) {
            sb.append("# 2.4 Credit Score Tier (Prerequisite schema)\n");
            sb.append("INSERT_UPDATE CreditScore; code[unique = true]; minScoreRange; maxScoreRange; contractStatus(code); pack    ; paperBilling; letterMapping\n");
            for (String tier : uniqueCreditScores) {
                String letter = tier.replace("CS_TIER_", "");
                int minRange = "A".equals(letter) ? 700 : "B".equals(letter) ? 600 : "C".equals(letter) ? 500 : 300;
                int maxRange = "A".equals(letter) ? 850 : "B".equals(letter) ? 749 : "C".equals(letter) ? 599 : 499;
                sb.append("                         ; ").append(pad(tier, 18))
                  .append(" ; ").append(pad(String.valueOf(minRange), 12))
                  .append(" ; ").append(pad(String.valueOf(maxRange), 12))
                  .append(" ; WITHCONTRACT        ; DEFAULT ; 0.0         ; ").append(letter).append("\n");
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
        for (String cCode : selectedCarts) {
            sb.append("                                              ; PAY_INFO_").append(cCode).append("\n");
        }
        sb.append("\n");

        sb.append("INSERT_UPDATE CreditCardPaymentInfo; &paymentInfo           ; code[unique = true]    ; user(uid)[unique = true]              ; billingAddress(&addrId) ; ccOwner         ; number             ; type(code); validToMonth; validToYear; subscriptionId\n");
        for (String cCode : selectedCarts) {
            Map<String, String> cData = cartsData.get(cCode);
            Map<String, String> uData = usersData.get(cCode);
            String uUid = uData.getOrDefault("uid", cData.getOrDefault("user(uid)", ""));
            String uName = uData.getOrDefault("name", cData.getOrDefault("name", ""));
            List<Map<String, String>> addrs = addressesData.get(cCode);
            String addrId = !addrs.isEmpty() ? addrs.get(0).getOrDefault("id[unique = true]", "PR_ADDR_14973") : "PR_ADDR_14973";

            sb.append("                                   ; ").append(pad("PAY_INFO_" + cCode, 23))
              .append(" ; ").append(pad("PAY_INFO_" + cCode, 23))
              .append(" ; ").append(pad(uUid, 38))
              .append(" ; ").append(pad(addrId, 16))
              .append("; \"").append(uName).append("\"     ; \"************1111\" ; visa      ; 12          ; 2030       ; \"SUB-").append(cCode).append("\"\n");
        }
        sb.append("\n");

        // 2.7 Composite Prices
        sb.append("# 2.7 Composite Cart Prices (TUA Composite Prices)\n");
        sb.append("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true]      ; name                                  ; catalogCode\n");
        for (String cCode : selectedCarts) {
            sb.append("                                            ; ").append(pad("price_" + cCode, 24))
              .append(" ; \"Cart Composite Price ").append(cCode).append("\" ; $productCatalog\n");
        }
        sb.append("\n");

        // 2.8 Total Prices
        sb.append("# 2.8 Cart Total Prices\n");
        sb.append("INSERT TotalPrice; &tpId                  ; priceType(code)  ; value  ; isTaxIncluded; totalTax\n");
        for (String cCode : selectedCarts) {
            Map<String, String> cData = cartsData.get(cCode);
            String recurringPrice = cData.getOrDefault("orderMonthlyPrice", cData.getOrDefault("totalRecurringPrice", "85.00"));
            String amountDueToday = cData.getOrDefault("amountDueToday", "0.00");
            String totalTax = cData.getOrDefault("totalTax", "0.00");

            sb.append("                 ; ").append(pad("tp_rec_" + cCode, 23))
              .append("; RECURRING        ; ").append(pad(recurringPrice, 6))
              .append(" ; TRUE         ; 0.00\n");

            sb.append("                 ; ").append(pad("tp_due_" + cCode, 23))
              .append("; AMOUNT_DUE_TODAY ; ").append(pad(amountDueToday, 6))
              .append(" ; TRUE         ; ").append(totalTax).append("\n");
        }
        sb.append("\n\n");

        // Section 3: Cart Header
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 3. CART HEADERS (All ").append(selectedCarts.size()).append(" Carts)\n");
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

        for (String cCode : selectedCarts) {
            Map<String, String> cData = cartsData.get(cCode);
            Map<String, String> uData = usersData.get(cCode);
            String cGuid = cData.getOrDefault("guid", "");
            String uUid = uData.getOrDefault("uid", cData.getOrDefault("user(uid)", ""));
            String uName = uData.getOrDefault("name", cData.getOrDefault("name", ""));
            String email = uData.getOrDefault("contactEmail", cData.getOrDefault("customerEmail", ""));
            String phone = cData.getOrDefault("phoneNumber", "(787)000-0000");
            List<Map<String, String>> addrs = addressesData.get(cCode);
            String addrId = !addrs.isEmpty() ? addrs.get(0).getOrDefault("id[unique = true]", "PR_ADDR_14973") : "PR_ADDR_14973";

            String totalTax = cData.getOrDefault("totalTax", "0.00");
            String taxValuesInternal = !totalTax.isEmpty() && !"0.00".equals(totalTax)
                    ? "\"[<TV<tax#" + totalTax + "#false#" + totalTax + "#USD>VT>]\""
                    : "";

            String rawScore = cData.getOrDefault("creditScore", "");
            String score = "";
            if (!rawScore.isEmpty()) {
                String cleanScore = rawScore.replace("+", "").trim();
                score = cleanScore.contains(".") ? cleanScore : cleanScore + ".0";
            }
            String letter = cData.getOrDefault("creditScoreLetter", "");
            String risk = cData.getOrDefault("creditScoreRisk(code)", cData.getOrDefault("creditScoreRisk(id)", ""));
            String tier = !letter.isEmpty() ? "CS_TIER_" + letter : "";

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
                    case "date[dateformat = dd.MM.yyyy HH:mm:ss]": val = cData.getOrDefault("date[dateformat = dd.MM.yyyy HH:mm:ss]", cData.getOrDefault("date", "")); break;
                    case "net[default = true]":
                    case "calculated[default = true]": val = "TRUE"; break;
                    case "subtotal": val = cData.getOrDefault("subtotal", "0.00"); break;
                    case "totalPrice": val = cData.getOrDefault("totalPrice", "0.00"); break;
                    case "totalTax": val = totalTax; break;
                    case "totalTaxValuesInternal": val = taxValuesInternal; break;
                    case "totalDiscounts": val = cData.getOrDefault("totalDiscounts", "0.00"); break;
                    case "price(id)": val = "price_" + cCode; break;
                    case "prices(&tpId)": val = "tp_rec_" + cCode + ",tp_due_" + cCode; break;
                    case "deliveryAddress(&addrId)":
                    case "paymentAddress(&addrId)":
                    case "selectedAddress(&addrId)": val = addrId; break;
                    case "deliveryMode(code)": val = "standard-gross"; break;
                    case "paymentMode(code)": val = "creditcard"; break;
                    case "paymentInfo(&paymentInfo)": val = "PAY_INFO_" + cCode; break;
                    case "status(code)":
                    case "orderStatus(code)": val = cData.getOrDefault("orderStatus(code)", "CREATED"); break;
                    case "paymentStatus(code)": val = cData.getOrDefault("paymentStatus(code)", "NOTPAID"); break;
                    case "paymentGateway(code)": val = cData.getOrDefault("paymentGateway(code)", "Braintree"); break;
                    case "paymentType(code)": val = cData.getOrDefault("paymentType(code)", "CreditCard"); break;
                    case "planTypes(code)": val = cData.getOrDefault("planTypes(code)", "NEW_NUMBER"); break;
                    case "creditCheck": val = cData.getOrDefault("creditCheck", "FALSE"); break;
                    case "creditCheckEnum(code)": val = cData.getOrDefault("creditCheckEnum(code)", ""); break;
                    case "creditScore": val = score; break;
                    case "creditScoreLetter": val = letter; break;
                    case "creditScoreRisk(code)": val = risk; break;
                    case "creditScoreLevel(code)": val = tier; break;
                    case "llaCreditRiskRating": val = cData.getOrDefault("llaCreditRiskRating", ""); break;
                    case "vertexApiCheck": val = cData.getOrDefault("vertexApiCheck", "TRUE"); break;
                    case "vertexApiCheckEnum(code)": val = cData.getOrDefault("vertexApiCheckEnum(code)", "DONE"); break;
                    case "orderVertexTaxAmnt": val = cData.getOrDefault("orderVertexTaxAmnt", "0.00"); break;
                    case "deliveryMethod": val = cData.getOrDefault("deliveryMethod", ""); break;
                    case "deliveryType": val = cData.getOrDefault("deliveryType", ""); break;
                    case "storeId": val = cData.getOrDefault("storeId", ""); break;
                    case "storeName": val = cData.getOrDefault("storeName", ""); break;
                    case "selectedStoreID": val = cData.getOrDefault("selectedStoreID", ""); break;
                    case "storePickupAddress1": val = cData.getOrDefault("storePickupAddress1", ""); break;
                    case "storePickupAddress2": val = cData.getOrDefault("storePickupAddress2", ""); break;
                    case "storePickupCity": val = cData.getOrDefault("storePickupCity", ""); break;
                    case "storePickupZip": val = cData.getOrDefault("storePickupZip", ""); break;
                    case "phoneNumber": val = phone; break;
                    case "customerEmail": val = email; break;
                    case "name": val = uName; break;
                    case "autoPayActive": val = cData.getOrDefault("autoPayActive", "FALSE"); break;
                    case "billPreference": val = cData.getOrDefault("billPreference", "e_bill"); break;
                    case "devicePlanCode": val = cData.getOrDefault("devicePlanCode", ""); break;
                    case "devicePrice": val = cData.getOrDefault("devicePrice", "0.00"); break;
                    case "devicePriceType": val = cData.getOrDefault("devicePriceType", ""); break;
                    case "isC2CRequestReceived": val = cData.getOrDefault("isC2CRequestReceived", "FALSE"); break;
                    case "customerDrop": val = cData.getOrDefault("customerDrop", "FALSE"); break;
                    case "sourceSystem": val = cData.getOrDefault("sourceSystem", "WEB"); break;
                    case "numberOfExistingLine": val = cData.getOrDefault("numberOfExistingLine", "0"); break;
                    case "numberOfFinancialDevices": val = cData.getOrDefault("numberOfFinancialDevices", "0"); break;
                    case "numberOfLines": val = "1"; break;
                    case "orderFeesAmount": val = cData.getOrDefault("orderFeesAmount", "0.00"); break;
                    case "orderMonthlyPrice": val = cData.getOrDefault("orderMonthlyPrice", "0.00"); break;
                    case "totalOneTimePrice": val = cData.getOrDefault("totalOneTimePrice", "0.00"); break;
                    case "totalRecurringPrice": val = cData.getOrDefault("totalRecurringPrice", "0.00"); break;
                    case "amountDueToday": val = cData.getOrDefault("amountDueToday", "0.00"); break;
                    case "amountDueFirstBill": val = cData.getOrDefault("amountDueFirstBill", "0.00"); break;
                    case "isSSNSkipped": val = cData.getOrDefault("isSSNSkipped", "FALSE"); break;
                    case "paymentJsonConsent":
                        String pjc = cData.getOrDefault("paymentJsonConsent", "");
                        val = ImpexFormatter.formatCell(pjc);
                        break;
                    default:
                        val = cData.getOrDefault(col, "");
                        break;
                }
                rowCells.add(val);
            }
            sb.append("                  ; ").append(String.join(" ; ", rowCells)).append("\n");
        }
        sb.append("\n\n");

        // Section 4: Cart Line Items
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 4. CART LINE ITEMS (CartEntry - All ").append(totalLineItems).append(" Entries)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String entryHeaderStr = "INSERT_UPDATE CartEntry; order(code)[unique = true]; entryNumber[unique = true]; product(code, $onlineCatalogVersion); quantity; unit(code); basePrice; totalPrice; monthlyPrice; entryRecurringPrice; tax13Price; taxValuesInternal                   ; processType(code); calculated; giveAway; rejected; numberOfLines";

        sb.append(entryHeaderStr).append("\n");

        for (String cCode : selectedCarts) {
            List<Map<String, String>> entries = entriesData.get(cCode);
            Map<String, String> cData = cartsData.get(cCode);
            String cartTax = cData.getOrDefault("totalTax", "0.00");

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
                    else if (!cartTax.isEmpty() && !"0.00".equals(cartTax)) tax13 = cartTax;
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

        // Section 5: Promotion Engine Objects
        if (!promoItems.isEmpty()) {
            sb.append("# -----------------------------------------------------------------------------------------\n");
            sb.append("# 5. PROMOTION ENGINE OBJECTS (Promotional Discount on Devices)\n");
            sb.append("# -----------------------------------------------------------------------------------------\n");

            sb.append("# 5.1 Promotion Results\n");
            sb.append("INSERT PromotionResult; &promoResId                       ; order(code)    ; certainty[default = 1.0] ; custom                        ; messageFired[lang = en]               ; rulesModuleName\n");
            Set<String> promoCartSeen = new HashSet<>();
            for (Map<String, String> p : promoItems) {
                String cCode = p.get("cartCode");
                String pName = p.getOrDefault("promotionName", "Mix Core_new on_us");
                if (promoCartSeen.add(cCode)) {
                    sb.append("                      ; ").append(pad("promo_res_cart_" + cCode, 28))
                      .append("; ").append(pad(cCode, 15))
                      .append("; 1.0                      ; \"").append(pName).append("\" ; \"Device promo: ").append(pName).append("\" ; \"promotions-module\"\n");
                }
            }
            sb.append("\n");

            sb.append("# 5.2 Promotion Order Entry Adjust Action\n");
            sb.append("INSERT_UPDATE PromotionOrderEntryAdjustAction; guid[unique = true]                  ; promotionResult(&promoResId)      ; amount  ; orderEntry(order(code), entryNumber) ; markedApplied[default = true]\n");
            for (Map<String, String> p : promoItems) {
                String cCode = p.get("cartCode");
                String eNum = p.get("entryNumber");
                sb.append("                                             ; ").append(pad("ACT_PROMO_CART_" + cCode + "_" + eNum, 31))
                  .append("; ").append(pad("promo_res_cart_" + cCode, 28))
                  .append("; 0.00   ; ").append(pad(cCode + ":" + eNum, 21))
                  .append("; TRUE\n");
            }
            sb.append("\n");

            sb.append("# 5.3 Promotion Order Entry Consumed\n");
            sb.append("INSERT PromotionOrderEntryConsumed; code[unique = true]                 ; promotionResult(&promoResId)      ; orderEntry(order(code), entryNumber) ; quantity ; adjustedUnitPrice\n");
            for (Map<String, String> p : promoItems) {
                String cCode = p.get("cartCode");
                String eNum = p.get("entryNumber");
                String qty = p.getOrDefault("quantity", "1");
                String unitPrice = p.getOrDefault("totalPrice", "0.00");
                sb.append("                                  ; ").append(pad("CONS_CART_" + cCode + "_" + eNum, 31))
                  .append("; ").append(pad("promo_res_cart_" + cCode, 28))
                  .append("; ").append(pad(cCode + ":" + eNum, 20))
                  .append(" ; ").append(pad(qty, 7))
                  .append("  ; ").append(unitPrice).append("\n");
            }
            sb.append("\n");
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
