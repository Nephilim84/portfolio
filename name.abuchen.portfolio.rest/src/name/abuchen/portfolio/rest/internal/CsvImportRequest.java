package name.abuchen.portfolio.rest.internal;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The body of a CSV import (preview or commit), parsed and validated in
 * isolation of the file it is applied to. All violations are collected and
 * reported at once, and an unknown key is a violation - a misspelled option
 * must not silently fall back to a default.
 */
@SuppressWarnings("nls")
public record CsvImportRequest(String type, byte[] data, Charset encoding, String configuration, Character delimiter,
                Integer skipLines, Boolean firstLineHeader, Character decimalSeparator, String dateFormat,
                List<ColumnSpec> columns, List<String> cashAccounts, String investmentAccount,
                List<String> targetCashAccounts, String targetInvestmentAccount, String instrument,
                boolean convertBuySellToDelivery, boolean removeDividends, boolean importNotes,
                Set<Integer> excludeLines, Set<Integer> acceptWarnings, ItemDetail itemDetail,
                List<ExpectedBalance> expectedBalances)
{
    /** how much per-item detail the report carries */
    public enum ItemDetail
    {
        ALL("all"), ISSUES("issues"), NONE("none");

        private final String wireName;

        ItemDetail(String wireName)
        {
            this.wireName = wireName;
        }

        public String wireName()
        {
            return wireName;
        }
    }

    /**
     * Assigns a field to a CSV column. The column is addressed by its 0-based
     * {@code index} or by its {@code header}. A JSON null as {@code field}
     * removes an automatic assignment; an absent {@code field} keeps it and
     * only changes the format.
     */
    public record ColumnSpec(int position, Integer index, String header, boolean hasField, String field,
                    JsonElement format)
    {
    }

    /** a balance taken from the bank statement, to reconcile against */
    public record ExpectedBalance(int position, String cashAccount, LocalDate date, BigDecimal balance)
    {
    }

    private static final Set<String> KEYS = Set.of("type", "csv", "csvBase64", "encoding", "configuration",
                    "delimiter", "skipLines", "firstLineHeader", "decimalSeparator", "dateFormat", "columns",
                    "cashAccounts", "investmentAccount", "targetCashAccounts", "targetInvestmentAccount",
                    "instrument", "convertBuySellToDelivery", "removeDividends", "importNotes", "excludeLines",
                    "acceptWarnings", "itemDetail", "expectedBalances");

    private static final String DELIMITERS = ",;\t|";

    public static CsvImportRequest parse(JsonObject body)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        for (var key : body.keySet())
        {
            if (!KEYS.contains(key))
                errors.add(new ApiException.FieldError(key, "unknown-field",
                                "unknown field '" + key + "'; accepted: " + String.join(", ", KEYS.stream().sorted().toList())));
        }

        var type = string(body, "type", errors);
        var configuration = string(body, "configuration", errors);
        if (type == null && configuration == null && !body.has("type"))
            errors.add(new ApiException.FieldError("type", "required",
                            "type is required unless a configuration is given; see GET .../csv-import/types"));

        // the data, as text or as raw bytes in a given encoding
        byte[] data = null;
        Charset encoding = null;
        var csv = string(body, "csv", errors);
        var csvBase64 = string(body, "csvBase64", errors);
        var encodingName = string(body, "encoding", errors);

        if (csv != null && csvBase64 != null)
            errors.add(new ApiException.FieldError("csv", "invalid-value", "give either csv or csvBase64, not both"));
        else if (csv == null && csvBase64 == null && !body.has("csv") && !body.has("csvBase64"))
            errors.add(new ApiException.FieldError("csv", "required",
                            "csv (the file content as text) or csvBase64 (the raw bytes) is required"));
        else if (csv != null)
        {
            data = csv.getBytes(StandardCharsets.UTF_8);
            encoding = StandardCharsets.UTF_8;
        }
        else if (csvBase64 != null)
        {
            try
            {
                data = Base64.getMimeDecoder().decode(csvBase64);
            }
            catch (IllegalArgumentException e)
            {
                errors.add(new ApiException.FieldError("csvBase64", "invalid-value", "csvBase64 is not valid base64"));
            }
        }

        if (encodingName != null)
        {
            if (csv != null)
                errors.add(new ApiException.FieldError("encoding", "invalid-value",
                                "encoding applies to csvBase64 only; csv is text and needs none"));
            try
            {
                encoding = Charset.forName(encodingName);
            }
            catch (IllegalCharsetNameException | UnsupportedCharsetException e)
            {
                errors.add(new ApiException.FieldError("encoding", "invalid-value",
                                "unknown encoding '" + encodingName + "'"));
            }
        }

        var delimiterText = string(body, "delimiter", errors);
        Character delimiter = null;
        if (delimiterText != null)
        {
            if (delimiterText.length() == 1 && DELIMITERS.indexOf(delimiterText.charAt(0)) >= 0)
                delimiter = delimiterText.charAt(0);
            else
                errors.add(new ApiException.FieldError("delimiter", "invalid-value",
                                "delimiter must be one of: \",\" \";\" \"\\t\" \"|\""));
        }

        var skipLines = integer(body, "skipLines", errors);
        if (skipLines != null && skipLines < 0)
            errors.add(new ApiException.FieldError("skipLines", "invalid-range", "skipLines must not be negative"));

        var firstLineHeader = bool(body, "firstLineHeader", errors);

        var separatorText = string(body, "decimalSeparator", errors);
        Character decimalSeparator = null;
        if (separatorText != null)
        {
            if (".".equals(separatorText) || ",".equals(separatorText))
                decimalSeparator = separatorText.charAt(0);
            else
                errors.add(new ApiException.FieldError("decimalSeparator", "invalid-value",
                                "decimalSeparator must be \".\" or \",\""));
        }

        var dateFormat = string(body, "dateFormat", errors);

        var columns = columns(body, errors);

        var cashAccounts = strings(body, "cashAccounts", errors);
        var targetCashAccounts = strings(body, "targetCashAccounts", errors);
        var investmentAccount = string(body, "investmentAccount", errors);
        var targetInvestmentAccount = string(body, "targetInvestmentAccount", errors);
        var instrument = string(body, "instrument", errors);

        var convertBuySellToDelivery = bool(body, "convertBuySellToDelivery", errors);
        var removeDividends = bool(body, "removeDividends", errors);
        var importNotes = bool(body, "importNotes", errors);

        var excludeLines = lines(body, "excludeLines", errors);
        var acceptWarnings = lines(body, "acceptWarnings", errors);

        var itemDetail = ItemDetail.ALL;
        var itemDetailText = string(body, "itemDetail", errors);
        if (itemDetailText != null)
        {
            itemDetail = null;
            for (var detail : ItemDetail.values())
                if (detail.wireName().equals(itemDetailText))
                    itemDetail = detail;
            if (itemDetail == null)
            {
                errors.add(new ApiException.FieldError("itemDetail", "invalid-value",
                                "itemDetail must be all, issues or none"));
                itemDetail = ItemDetail.ALL;
            }
        }

        var expectedBalances = expectedBalances(body, errors);

        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        return new CsvImportRequest(type, data, encoding, configuration, delimiter, skipLines, firstLineHeader,
                        decimalSeparator, dateFormat, columns, cashAccounts, investmentAccount, targetCashAccounts,
                        targetInvestmentAccount, instrument, Boolean.TRUE.equals(convertBuySellToDelivery),
                        Boolean.TRUE.equals(removeDividends), !Boolean.FALSE.equals(importNotes), excludeLines,
                        acceptWarnings, itemDetail, expectedBalances);
    }

    private static List<ColumnSpec> columns(JsonObject body, List<ApiException.FieldError> errors)
    {
        var result = new ArrayList<ColumnSpec>();
        var element = body.get("columns");
        if (element == null || element.isJsonNull())
            return result;

        if (!element.isJsonArray())
        {
            errors.add(new ApiException.FieldError("columns", "invalid-type", "columns must be an array"));
            return result;
        }

        var array = element.getAsJsonArray();
        for (int ii = 0; ii < array.size(); ii++)
        {
            var name = "columns[" + ii + "]";
            if (!array.get(ii).isJsonObject())
            {
                errors.add(new ApiException.FieldError(name, "invalid-type", name + " must be an object"));
                continue;
            }

            var column = array.get(ii).getAsJsonObject();
            for (var key : column.keySet())
            {
                if (!Set.of("index", "header", "field", "format").contains(key))
                    errors.add(new ApiException.FieldError(name + "." + key, "unknown-field",
                                    "unknown field '" + key + "'; accepted: index, header, field, format"));
            }

            var index = integer(column, "index", name + ".index", errors);
            var header = string(column, "header", name + ".header", errors);
            if ((index == null) == (header == null))
                errors.add(new ApiException.FieldError(name, "invalid-value",
                                name + " must address the column by either index or header"));
            if (index != null && index < 0)
                errors.add(new ApiException.FieldError(name + ".index", "invalid-range",
                                "index is 0-based and must not be negative"));

            var hasField = column.has("field");
            var field = string(column, "field", name + ".field", errors);

            var format = column.get("format");
            if (format != null && !format.isJsonNull() && !format.isJsonObject()
                            && !(format.isJsonPrimitive() && format.getAsJsonPrimitive().isString()))
                errors.add(new ApiException.FieldError(name + ".format", "invalid-type",
                                "format must be a string (amount and date columns) or an object (type column)"));

            result.add(new ColumnSpec(ii, index, header, hasField, field, format));
        }
        return result;
    }

    private static List<ExpectedBalance> expectedBalances(JsonObject body, List<ApiException.FieldError> errors)
    {
        var result = new ArrayList<ExpectedBalance>();
        var element = body.get("expectedBalances");
        if (element == null || element.isJsonNull())
            return result;

        if (!element.isJsonArray())
        {
            errors.add(new ApiException.FieldError("expectedBalances", "invalid-type",
                            "expectedBalances must be an array"));
            return result;
        }

        var array = element.getAsJsonArray();
        for (int ii = 0; ii < array.size(); ii++)
        {
            var name = "expectedBalances[" + ii + "]";
            if (!array.get(ii).isJsonObject())
            {
                errors.add(new ApiException.FieldError(name, "invalid-type", name + " must be an object"));
                continue;
            }

            var entry = array.get(ii).getAsJsonObject();
            for (var key : entry.keySet())
            {
                if (!Set.of("cashAccount", "date", "balance").contains(key))
                    errors.add(new ApiException.FieldError(name + "." + key, "unknown-field",
                                    "unknown field '" + key + "'; accepted: cashAccount, date, balance"));
            }

            var cashAccount = string(entry, "cashAccount", name + ".cashAccount", errors);

            LocalDate date = null;
            var dateText = string(entry, "date", name + ".date", errors);
            if (dateText == null)
                errors.add(new ApiException.FieldError(name + ".date", "required", "date is required"));
            else
            {
                try
                {
                    date = LocalDate.parse(dateText);
                }
                catch (DateTimeParseException e)
                {
                    errors.add(new ApiException.FieldError(name + ".date", "invalid-value",
                                    "date must be an ISO 8601 date (YYYY-MM-DD)"));
                }
            }

            BigDecimal balance = null;
            var value = entry.get("balance");
            if (value == null || value.isJsonNull())
                errors.add(new ApiException.FieldError(name + ".balance", "required", "balance is required"));
            else if (value.isJsonPrimitive() && (value.getAsJsonPrimitive().isNumber()
                            || value.getAsJsonPrimitive().isString()))
            {
                try
                {
                    balance = new BigDecimal(value.getAsString());
                }
                catch (NumberFormatException e)
                {
                    errors.add(new ApiException.FieldError(name + ".balance", "invalid-value",
                                    "balance must be a decimal number such as 1234.56"));
                }
            }
            else
                errors.add(new ApiException.FieldError(name + ".balance", "invalid-type",
                                "balance must be a number"));

            result.add(new ExpectedBalance(ii, cashAccount, date, balance));
        }
        return result;
    }

    private static Set<Integer> lines(JsonObject body, String key, List<ApiException.FieldError> errors)
    {
        var result = new LinkedHashSet<Integer>();
        var element = body.get(key);
        if (element == null || element.isJsonNull())
            return result;

        if (!element.isJsonArray())
        {
            errors.add(new ApiException.FieldError(key, "invalid-type", key + " must be an array of line numbers"));
            return result;
        }

        for (var line : element.getAsJsonArray())
        {
            if (isInteger(line))
                result.add(line.getAsInt());
            else
                errors.add(new ApiException.FieldError(key, "invalid-type",
                                key + " must contain line numbers (integers) only"));
        }
        return result;
    }

    private static List<String> strings(JsonObject body, String key, List<ApiException.FieldError> errors)
    {
        var result = new ArrayList<String>();
        var element = body.get(key);
        if (element == null || element.isJsonNull())
            return result;

        if (!element.isJsonArray())
        {
            errors.add(new ApiException.FieldError(key, "invalid-type", key + " must be an array of uuids"));
            return result;
        }

        for (var item : element.getAsJsonArray())
        {
            if (item.isJsonPrimitive() && item.getAsJsonPrimitive().isString())
                result.add(item.getAsString());
            else
                errors.add(new ApiException.FieldError(key, "invalid-type", key + " must contain strings only"));
        }
        return result;
    }

    private static String string(JsonObject body, String key, List<ApiException.FieldError> errors)
    {
        return string(body, key, key, errors);
    }

    private static String string(JsonObject body, String key, String name, List<ApiException.FieldError> errors)
    {
        var element = body.get(key);
        if (element == null || element.isJsonNull())
            return null;
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString())
            return element.getAsString();
        errors.add(new ApiException.FieldError(name, "invalid-type", name + " must be a string"));
        return null;
    }

    private static Integer integer(JsonObject body, String key, List<ApiException.FieldError> errors)
    {
        return integer(body, key, key, errors);
    }

    private static Integer integer(JsonObject body, String key, String name, List<ApiException.FieldError> errors)
    {
        var element = body.get(key);
        if (element == null || element.isJsonNull())
            return null;
        if (isInteger(element))
            return element.getAsInt();
        errors.add(new ApiException.FieldError(name, "invalid-type", name + " must be an integer"));
        return null;
    }

    private static Boolean bool(JsonObject body, String key, List<ApiException.FieldError> errors)
    {
        var element = body.get(key);
        if (element == null || element.isJsonNull())
            return null; // NOSONAR - null means: not given, use the default
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isBoolean())
            return element.getAsBoolean();
        errors.add(new ApiException.FieldError(key, "invalid-type", key + " must be a boolean"));
        return null; // NOSONAR
    }

    private static boolean isInteger(JsonElement element)
    {
        if (!(element instanceof JsonPrimitive primitive) || !primitive.isNumber())
            return false;
        var number = primitive.getAsBigDecimal();
        return number.stripTrailingZeros().scale() <= 0 && number.abs().compareTo(BigDecimal.valueOf(1_000_000_000)) < 0;
    }
}
