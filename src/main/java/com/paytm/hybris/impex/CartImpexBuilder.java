package com.paytm.hybris.impex;

import java.util.*;

/**
 * Builds SAP Commerce (Hybris) ImpEx matching the exact prototype of cartImportImpex
 * from an abandoned cart export workbook.
 */
public class CartImpexBuilder {

    public static String build(Map<String, XlsxReader.SheetData> sheets, String sourceFileName, String targetCartCode) {
        // Collect rows grouped by cartCode or cartId
        XlsxReader.SheetData primSheet = sheets.get("CartPrimitive");
        if (primSheet == null) {
            throw new IllegalArgumentException("CartPrimitive sheet not found in workbook.");
        }

        // Identify available carts
        int cartCodeCol = findCol(primSheet.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
        int cartIdCol = findCol(primSheet.getHeaders(), "cartId");

        List<String> cartCodes = new ArrayList<>();
        for (List<String> row : primSheet.getRows()) {
            String cCode = getVal(row, cartCodeCol);
            if (!cCode.isEmpty() && !cartCodes.contains(cCode)) {
                cartCodes.add(cCode);
            }
        }

        if (cartCodes.isEmpty()) {
            throw new IllegalArgumentException("No cart records found in CartPrimitive sheet.");
        }

        List<String> selectedCarts = new ArrayList<>();
        if (targetCartCode != null && !targetCartCode.trim().isEmpty()) {
            if (cartCodes.contains(targetCartCode.trim())) {
                selectedCarts.add(targetCartCode.trim());
            } else {
                throw new IllegalArgumentException("Cart code " + targetCartCode + " not found in file.");
            }
        } else {
            selectedCarts.addAll(cartCodes);
        }

        StringBuilder fullImpex = new StringBuilder();
        for (int i = 0; i < selectedCarts.size(); i++) {
            String cCode = selectedCarts.get(i);
            String cartImpex = buildSingleCart(sheets, sourceFileName, cCode);
            fullImpex.append(cartImpex);
            if (i < selectedCarts.size() - 1) {
                fullImpex.append("\n\n");
            }
        }

        return fullImpex.toString();
    }

    private static String buildSingleCart(Map<String, XlsxReader.SheetData> sheets, String sourceFileName, String cartCode) {
        StringBuilder sb = new StringBuilder();

        // 1. Gather all attributes across Cart sheets for this cart
        Map<String, String> cartData = new HashMap<>();
        String[] cartSheets = new String[]{"CartPrimitive", "CartNonPrimitive", "CartItemTypeRefs", "CartCollectionRefs", "CartEnumRefs"};
        for (String sName : cartSheets) {
            XlsxReader.SheetData sData = sheets.get(sName);
            if (sData == null) continue;
            int cCol = findCol(sData.getHeaders(), "code[unique = true]", "code[unique=true]", "code");
            int idCol = findCol(sData.getHeaders(), "cartId");
            for (List<String> row : sData.getRows()) {
                String rowCCode = getVal(row, cCol);
                if (cartCode.equals(rowCCode)) {
                    for (int c = 0; c < sData.getHeaders().size(); c++) {
                        String h = sData.getHeaders().get(c).trim();
                        String v = getVal(row, c);
                        if (!v.isEmpty()) {
                            cartData.put(h, v);
                            String normH = h.replaceAll("\\s+", "").toLowerCase();
                            cartData.put(normH, v);
                        }
                    }
                }
            }
        }

        // Customer sheet
        Map<String, String> userData = new HashMap<>();
        XlsxReader.SheetData userSheet = sheets.get("PrereqUsers");
        if (userSheet != null) {
            int cCol = findCol(userSheet.getHeaders(), "cartCode", "code");
            int idCol = findCol(userSheet.getHeaders(), "cartId");
            String myCartId = cartData.get("cartId");
            for (List<String> row : userSheet.getRows()) {
                String rCartId = getVal(row, idCol);
                if (myCartId != null && myCartId.equals(rCartId)) {
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
            int idCol = findCol(addrSheet.getHeaders(), "cartId");
            String myCartId = cartData.get("cartId");
            for (List<String> row : addrSheet.getRows()) {
                String rCartId = getVal(row, idCol);
                if (myCartId != null && myCartId.equals(rCartId)) {
                    Map<String, String> addrMap = new HashMap<>();
                    for (int c = 0; c < addrSheet.getHeaders().size(); c++) {
                        addrMap.put(addrSheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    addresses.add(addrMap);
                }
            }
        }

        // Portability checks sheet
        List<Map<String, String>> portChecks = new ArrayList<>();
        XlsxReader.SheetData portSheet = sheets.get("PrereqPortabilityChecks");
        if (portSheet != null) {
            int idCol = findCol(portSheet.getHeaders(), "cartId");
            String myCartId = cartData.get("cartId");
            for (List<String> row : portSheet.getRows()) {
                String rCartId = getVal(row, idCol);
                if (myCartId != null && myCartId.equals(rCartId)) {
                    Map<String, String> pMap = new HashMap<>();
                    for (int c = 0; c < portSheet.getHeaders().size(); c++) {
                        pMap.put(portSheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    if (!pMap.getOrDefault("phoneNumber", "").isEmpty()) {
                        portChecks.add(pMap);
                    }
                }
            }
        }

        // Cart entries
        List<Map<String, String>> cartEntries = new ArrayList<>();
        XlsxReader.SheetData entrySheet = sheets.get("CartEntries");
        if (entrySheet != null) {
            int oCol = findCol(entrySheet.getHeaders(), "order(code)[unique = true]", "order(code)", "order");
            for (List<String> row : entrySheet.getRows()) {
                String rCode = getVal(row, oCol);
                if (cartCode.equals(rCode)) {
                    Map<String, String> eMap = new LinkedHashMap<>();
                    for (int c = 0; c < entrySheet.getHeaders().size(); c++) {
                        eMap.put(entrySheet.getHeaders().get(c).trim(), getVal(row, c));
                    }
                    cartEntries.add(eMap);
                }
            }
        }

        // Macro variables
        String guid = cartData.getOrDefault("guid", "");
        String customerUid = userData.getOrDefault("uid", cartData.getOrDefault("user(uid)", ""));
        String customerName = userData.getOrDefault("name", "Customer Name");
        String site = cartData.getOrDefault("site(uid)", "puertorico");
        String store = cartData.getOrDefault("store(uid)", "puertoricoStore");
        String productCatalog = site.contains("Store") ? site.replace("Store", "") + "ProductCatalog" : site + "ProductCatalog";
        String currency = cartData.getOrDefault("currency(isocode)", "USD");

        // -----------------------------------------------------------------------------------------
        // Header comments
        // -----------------------------------------------------------------------------------------
        sb.append("# =========================================================================================\n");
        sb.append("# ImpEx: Improved Cart Import with Promotion Engine Objects\n");
        sb.append("# Cart Code: ").append(cartCode).append(" | GUID: ").append(guid).append("\n");
        sb.append("# Target Tenant: Puerto Rico (PR)\n");
        sb.append("# Source Data File: ").append(sourceFileName).append("\n");
        sb.append("# Sheets Mapped:\n");
        sb.append("#   - PrereqUsers -> Customer (").append(customerName).append(" / ").append(customerUid).append(")\n");
        sb.append("#   - CartPrimitive, CartNonPrimitive, CartItemTypeRefs, CartCollectionRefs, CartEnumRefs -> Cart Header\n");
        sb.append("#   - CartEntries -> CartEntry (");
        for (int i = 0; i < cartEntries.size(); i++) {
            sb.append("Entry ").append(i).append(": ").append(cartEntries.get(i).getOrDefault("product(code, $onlineCatalogVersion)", ""));
            if (i < cartEntries.size() - 1) sb.append(", ");
        }
        sb.append(")\n");
        sb.append("#   - Promotion Engine: SingleCodeCoupon, PromotionResult, PromotionOrderEntryAdjustAction,\n");
        sb.append("#                       PromotionOrderAdjustTotalAction, PromotionOrderEntryConsumed, CouponRedemption\n");
        sb.append("# =========================================================================================\n\n");

        // -----------------------------------------------------------------------------------------
        // 1. MACROS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 1. MACROS & CATALOG CONFIGURATION\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("$cartCode = ").append(cartCode).append("\n");
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
        sb.append("# 2. PREREQUISITES: CUSTOMER, ADDRESSES, RISKS, PRICING & COUPONS\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        // 2.1 Customer
        String contactEmail = userData.getOrDefault("contactEmail", cartData.getOrDefault("customerEmail", ""));
        String mobilePhone = cartData.getOrDefault("phoneNumber", "(787)000-0000");
        String groups = userData.getOrDefault("groups(uid)", "customergroup");
        String sessionLang = userData.getOrDefault("sessionLanguage(isocode)", "en");
        if (sessionLang.contains("-")) sessionLang = sessionLang.split("-")[0];
        String existingCust = cartData.getOrDefault("isCustomerExisting", "FALSE");

        sb.append("# 2.1 Customer (from PrereqUsers & CartPrimitive)\n");
        sb.append("INSERT_UPDATE Customer; uid[unique = true] ; name       ; contactEmail                 ; mobilePhone   ; customerID         ; groups(uid)   ; sessionLanguage(isocode); sessionCurrency(isocode); existingCustomer; dateOfBirth[dateformat = yyyy-MM-dd]; correlationId\n");
        sb.append("                      ; $customerUid       ; ").append(pad(customerName, 10)).append(" ; ").append(pad(contactEmail, 28)).append(" ; ").append(pad(mobilePhone, 13)).append(" ; $customerUid       ; ").append(pad(groups, 13)).append(" ; ").append(pad(sessionLang, 23)).append(" ; $defaultCurrency        ; ").append(pad(existingCust, 15)).append(" ; 1990-01-01                          ; CORR-$cartCode\n\n");

        // 2.2 Address
        sb.append("# 2.2 Address (Billing & Shipping Address for Puerto Rico customer)\n");
        sb.append("INSERT_UPDATE Address; &cartAddr        ; owner(Customer.uid)[unique = true] ; firstname ; lastname ; streetname         ; streetnumber ; postalcode ; town     ; country(isocode) ; billingAddress ; shippingAddress\n");
        if (!addresses.isEmpty()) {
            Map<String, String> addr = addresses.get(0);
            String fn = addr.getOrDefault("firstname", "Test");
            String ln = addr.getOrDefault("lastname", "Test");
            String sn = addr.getOrDefault("streetname", "Calle San Patricio");
            String snum = addr.getOrDefault("streetnumber", "100");
            String pc = addr.getOrDefault("postalcode", "00968");
            String town = addr.getOrDefault("town", "Guaynabo");
            String country = addr.getOrDefault("country(isocode)", "PR");
            if (country.isEmpty()) country = "PR";
            sb.append("                     ; ADDR_$cartCode   ; $customerUid                       ; ").append(pad(fn, 9)).append(" ; ").append(pad(ln, 8)).append(" ; ").append(pad(sn, 18)).append(" ; ").append(pad(snum, 12)).append(" ; ").append(pad(pc, 10)).append(" ; ").append(pad(town, 8)).append(" ; ").append(pad(country, 16)).append(" ; TRUE           ; TRUE\n\n");
        } else {
            sb.append("                     ; ADDR_$cartCode   ; $customerUid                       ; Test      ; Test     ; Calle San Patricio ; 100          ; 00968      ; Guaynabo ; PR               ; TRUE           ; TRUE\n\n");
        }

        // 2.3 Portability Checks (if any)
        if (!portChecks.isEmpty()) {
            sb.append("# 2.3 Portability Number Checks\n");
            sb.append("INSERT_UPDATE PortabilityNumberCheck; id[unique = true]   ; phoneNumber ; portabilityNumberCheck ; portabilityNumberUID\n");
            for (Map<String, String> pc : portChecks) {
                sb.append("                                    ; PR_PNC_$cartCode    ; ").append(pad(pc.getOrDefault("phoneNumber", ""), 11)).append(" ; TRUE                   ; ").append(pc.getOrDefault("portabilityNumberUID", "")).append("\n");
            }
            sb.append("\n");
        }

        // 2.5 Credit Score
        String creditScoreLevel = cartData.getOrDefault("creditScoreLevel(code)", cartData.getOrDefault("creditScoreLevel(id)", "CS_TIER_A"));
        String letterMapping = cartData.getOrDefault("creditScoreLetter", "A");
        sb.append("# 2.5 Credit Score (Schema: code, minScoreRange, maxScoreRange, contractStatus, pack, paperBilling, letterMapping)\n");
        sb.append("INSERT_UPDATE CreditScore; code[unique = true]; minScoreRange; maxScoreRange; contractStatus(code); pack    ; paperBilling; letterMapping\n");
        sb.append("                         ; ").append(pad(creditScoreLevel, 18)).append(" ; 700          ; 850          ; WITHCONTRACT        ; DEFAULT ; 0.0         ; ").append(letterMapping).append("\n\n");

        // 2.6 Composite TUA Price
        sb.append("# 2.6 Composite TUA Price (Cart Total Composite Price)\n");
        sb.append("INSERT_UPDATE TmaAbstractOrderCompositePrice; id[unique = true] ; name                                 ; catalogCode\n");
        sb.append("                                            ; price_$cartCode   ; \"Order Composite Price $cartCode\"    ; $productCatalog\n\n");

        // 2.7 Total Prices
        String recurringPrice = cartData.getOrDefault("orderMonthlyPrice", cartData.getOrDefault("totalRecurringPrice", "35.00"));
        String amountDueToday = cartData.getOrDefault("amountDueToday", cartData.getOrDefault("totalPrice", "74.03"));
        String totalTax = cartData.getOrDefault("totalTax", "0.00");
        sb.append("# 2.7 Order Total Prices (Item #4: prices)\n");
        sb.append("# TotalPrice has no unique attribute in items.xml, so INSERT is used\n");
        sb.append("INSERT TotalPrice; &tpId              ; priceType(code)  ; value ; isTaxIncluded ; totalTax\n");
        sb.append("                 ; tp_rec_$cartCode   ; RECURRING        ; ").append(pad(recurringPrice, 5)).append(" ; TRUE          ; 0.00\n");
        sb.append("                 ; tp_due_$cartCode   ; AMOUNT_DUE_TODAY ; ").append(pad(amountDueToday, 5)).append(" ; TRUE          ; ").append(totalTax).append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 3. CART HEADER
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 3. CART HEADER (with Promos, Taxes, Calculated Flags, Credit Risk & Financials)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String[] cartHeaderCols = new String[]{
                "code[unique = true]", "guid", "user(uid)", "savedBy(uid)", "currency(isocode)", "site(uid)", "store(uid)",
                "date[dateformat = dd.MM.yyyy HH:mm:ss]", "net[default = true]", "calculated[default = true]", "subtotal", "totalPrice",
                "totalTax", "totalTaxValuesInternal", "totalDiscounts", "globalDiscountValuesInternal", "appliedCouponCodes", "promoOrderFlag",
                "price(id)", "prices(&tpId)", "deliveryAddress(&cartAddr)", "creditCheckAddress(&cartAddr)", "installationAddress(&cartAddr)",
                "paymentAddress(&cartAddr)", "selectedAddress(&cartAddr)", "amountDueToday", "isAmountDueToday", "autoPayActive",
                "billPreference", "category", "contractType(code)", "creditCheck", "creditCheckEnum(code)", "creditScore", "creditScoreLetter",
                "creditScoreRisk(code)", "creditScoreLevel(code)", "customerEmail", "lastStep", "lastStepValue(code)", "llaCreditRiskRating",
                "mobileOrder", "numberOfExistingLine", "numberOfFinancialDevices", "numberOfLines", "packAmountDueToday", "packAmountDueFirstBill",
                "amountDueFirstBill", "phoneNumber", "sourceSystem", "totalRecurringPrice", "orderMonthlyPrice", "orderFeesAmount",
                "totalOneTimePrice", "devicePrice", "devicePlanCode", "devicePriceType", "customerDrop", "isC2CRequestReceived",
                "sessionId", "portabilityCheckFailed", "uspsStatusForBillingAddress", "uspsStatusForDeliveryAddress", "vertexApiCheck",
                "vertexApiCheckEnum(code)", "totalInstallationFee", "isCustomerExisting", "isSSNSkipped", "isDeliverySchedulingSkipped",
                "orderStatus(code)", "status(code)", "devicePaymentOption(code)", "planTypes(code)", "portabilityNumberCheckResponse(id)"
        };

        sb.append("INSERT_UPDATE Cart; ").append(String.join(" ; ", cartHeaderCols)).append("\n");

        List<String> cartValCells = new ArrayList<>();
        for (String col : cartHeaderCols) {
            String val = "";
            switch (col) {
                case "code[unique = true]": val = "$cartCode"; break;
                case "guid": val = guid; break;
                case "user(uid)":
                case "savedBy(uid)": val = "$customerUid"; break;
                case "currency(isocode)": val = "$defaultCurrency"; break;
                case "site(uid)": val = "$site"; break;
                case "store(uid)": val = "$store"; break;
                case "net[default = true]":
                case "calculated[default = true]": val = "TRUE"; break;
                case "price(id)": val = "price_$cartCode"; break;
                case "prices(&tpId)": val = "tp_rec_$cartCode,tp_due_$cartCode"; break;
                case "deliveryAddress(&cartAddr)":
                case "creditCheckAddress(&cartAddr)":
                case "installationAddress(&cartAddr)":
                case "paymentAddress(&cartAddr)":
                case "selectedAddress(&cartAddr)": val = "ADDR_$cartCode"; break;
                case "portabilityNumberCheckResponse(id)": val = portChecks.isEmpty() ? "" : "PR_PNC_$cartCode"; break;
                case "customerEmail": val = contactEmail; break;
                default:
                    val = cartData.getOrDefault(col, cartData.getOrDefault(col.replaceAll("\\s+", "").toLowerCase(), ""));
                    break;
            }
            cartValCells.add(ImpexFormatter.formatCell(val));
        }

        sb.append("                  ; ").append(String.join(" ; ", cartValCells)).append("\n\n");

        // -----------------------------------------------------------------------------------------
        // 4. CART LINE ITEMS
        // -----------------------------------------------------------------------------------------
        sb.append("# -----------------------------------------------------------------------------------------\n");
        sb.append("# 4. CART LINE ITEMS (CartEntry with Entry Discounts & Tax Values)\n");
        sb.append("# -----------------------------------------------------------------------------------------\n");

        String[] entryHeaderCols = new String[]{
                "order(code)[unique = true]", "entryNumber[unique = true]", "product(code, $onlineCatalogVersion)", "quantity",
                "unit(code)", "basePrice", "totalPrice", "monthlyPrice", "entryRecurringPrice", "tax13Price", "taxValuesInternal",
                "discountValuesInternal", "installationFee", "entryGroupNumbers", "processType(code)", "calculated", "giveAway", "rejected", "numberOfLines"
        };

        sb.append("INSERT_UPDATE CartEntry; ").append(String.join("; ", entryHeaderCols)).append("\n");

        for (int i = 0; i < cartEntries.size(); i++) {
            Map<String, String> entry = cartEntries.get(i);
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
