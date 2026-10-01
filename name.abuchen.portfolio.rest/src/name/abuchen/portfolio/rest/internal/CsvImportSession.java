package name.abuchen.portfolio.rest.internal;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.checks.Checker;
import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.Extractor.Item;
import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.datatransfer.ImportAction.Status;
import name.abuchen.portfolio.datatransfer.actions.CheckCurrenciesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckForexGrossValueAction;
import name.abuchen.portfolio.datatransfer.actions.CheckSecurityRelatedValuesAction;
import name.abuchen.portfolio.datatransfer.actions.CheckTransactionDateAction;
import name.abuchen.portfolio.datatransfer.actions.CheckValidTypesAction;
import name.abuchen.portfolio.datatransfer.actions.DetectDuplicatesAction;
import name.abuchen.portfolio.datatransfer.actions.InsertAction;
import name.abuchen.portfolio.datatransfer.csv.CSVConfig;
import name.abuchen.portfolio.datatransfer.csv.CSVExtractor;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.AmountField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.Column;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.DateField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.EnumField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.EnumMapFormat;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.Field;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.FieldFormat;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.ISINField;
import name.abuchen.portfolio.datatransfer.csv.CSVLineException;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityEvent;
import name.abuchen.portfolio.model.SecurityEvent.DividendEvent;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.rest.Messages;
import name.abuchen.portfolio.rest.internal.CsvImportRequest.ItemDetail;
import name.abuchen.portfolio.rest.spi.CsvConfiguration;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

/**
 * One CSV import, previewed or committed. Reuses the import machinery of the
 * CSV import wizard: the {@link CSVImporter} parses and maps the columns, the
 * extractor creates the items, the same {@link ImportAction} checks as the
 * review page judge them, and the {@link InsertAction} inserts them. What the
 * wizard leaves to the eye of the user - which rows are potential duplicates,
 * how the import moves the balance of a cash account - is made explicit in the
 * report, so that a program can verify an import against a bank statement.
 * <p/>
 * A preview leaves the file untouched. A commit re-runs the very same
 * evaluation (the file may have changed in between) and then inserts the
 * items that pass.
 */
@SuppressWarnings("nls")
public final class CsvImportSession
{
    /** the import types, by the wire name and the code of the wizard's extractor */
    public enum ImportType
    {
        CASH_ACCOUNT_TRANSACTIONS("cash-account-transactions", "account-transaction"), //
        INVESTMENT_ACCOUNT_TRANSACTIONS("investment-account-transactions", "portfolio-transaction"), //
        INSTRUMENTS("instruments", "investment-vehicle"), //
        INSTRUMENT_PRICES("instrument-prices", "investment-vehicle-price"), //
        HOLDINGS("holdings", "portfolio");

        private final String wireName;
        private final String extractorCode;

        ImportType(String wireName, String extractorCode)
        {
            this.wireName = wireName;
            this.extractorCode = extractorCode;
        }

        public String wireName()
        {
            return wireName;
        }

        public String extractorCode()
        {
            return extractorCode;
        }

        public static ImportType byWireName(String name)
        {
            return Arrays.stream(values()).filter(t -> t.wireName.equals(name)).findFirst().orElse(null);
        }

        public static ImportType byExtractorCode(String code)
        {
            return Arrays.stream(values()).filter(t -> t.extractorCode.equals(code)).findFirst().orElse(null);
        }

        public static String wireNames()
        {
            return Arrays.stream(values()).map(ImportType::wireName).collect(Collectors.joining(", "));
        }
    }

    /** one message about an item: which check raised it, and how severe it is */
    private record Message(Status.Code code, String check, String text)
    {
    }

    /** an extracted item and everything the evaluation learned about it */
    private static final class Entry
    {
        final Item item;
        final Integer line;
        final List<Message> messages = new ArrayList<>();
        Status.Code maxCode = Status.Code.OK;

        Account cashAccount;
        Account targetCashAccount;
        Portfolio investmentAccount;
        Portfolio targetInvestmentAccount;

        final Set<String> duplicateOf = new LinkedHashSet<>();
        final Set<Integer> duplicateOfLines = new TreeSet<>();
        List<Object> duplicateKey;

        boolean doImport;
        String reason;

        Entry(Item item)
        {
            this.item = item;
            this.line = (Integer) item.getData(CSVExtractor.LINE_NUMBER);
        }

        void add(String check, Status status)
        {
            if (status == null || status.getCode() == Status.Code.OK)
                return;

            var text = status.getMessage() != null ? status.getMessage() : check;
            if (messages.stream().anyMatch(m -> m.code() == status.getCode() && m.text().equals(text)))
                return;

            messages.add(new Message(status.getCode(), check, text));
            if (status.getCode().isHigherSeverityAs(maxCode))
                maxCode = status.getCode();
        }
    }

    /**
     * The import targets, as the review page of the wizard offers them in its
     * drop-downs: one cash account per currency, one investment account, and
     * the counterparts of transfers. A currency for which no cash account is
     * given defaults to the only active cash account in that currency, if
     * there is exactly one; likewise for the investment account. Transfer
     * targets never default - a guessed counterparty would book money into the
     * wrong account without anyone noticing.
     */
    private final class Targets implements ImportAction.Context
    {
        final Map<String, Account> cash = new TreeMap<>();
        final Map<String, Account> targetCash = new TreeMap<>();
        Portfolio portfolio;
        Portfolio targetPortfolio;

        final Set<String> defaulted = new HashSet<>();
        final List<String> misses = new ArrayList<>();

        @Override
        public Account getAccount(String currencyCode)
        {
            var account = cash.get(currencyCode);
            if (account != null)
                return account;

            var candidates = activeAccounts(currencyCode);
            if (candidates.size() == 1)
            {
                account = candidates.get(0);
                cash.put(currencyCode, account);
                defaulted.add("cash:" + currencyCode);
                return account;
            }

            misses.add(MessageFormat.format(
                            "no cash account for currency {0}: add the uuid of one to cashAccounts ({1})",
                            currencyCode, describe(candidates, currencyCode)));
            return null;
        }

        @Override
        public Portfolio getPortfolio()
        {
            if (portfolio != null)
                return portfolio;

            var candidates = activePortfolios();
            if (candidates.size() == 1)
            {
                portfolio = candidates.get(0);
                defaulted.add("portfolio");
                return portfolio;
            }

            misses.add("no investment account: give the uuid of one as investmentAccount (" //
                            + candidates.stream().map(p -> p.getName() + " " + p.getUUID())
                                            .collect(Collectors.joining(", "))
                            + ")");
            return null;
        }

        @Override
        public Account getSecondaryAccount(String currencyCode)
        {
            var account = targetCash.get(currencyCode);
            if (account == null)
                misses.add(MessageFormat.format(
                                "no transfer target for currency {0}: add the uuid of a cash account to targetCashAccounts ({1})",
                                currencyCode, describe(activeAccounts(currencyCode), currencyCode)));
            return account;
        }

        @Override
        public Portfolio getSecondaryPortfolio()
        {
            if (targetPortfolio == null)
                misses.add("no transfer target: give the uuid of an investment account as targetInvestmentAccount");
            return targetPortfolio;
        }

        private String describe(List<Account> candidates, String currencyCode)
        {
            if (candidates.isEmpty())
                return "the file has no active cash account in " + currencyCode;
            return "candidates: " + candidates.stream().map(a -> a.getName() + " " + a.getUUID())
                            .collect(Collectors.joining(", "));
        }
    }

    /**
     * Records what the evaluation needs to know about the item beyond the
     * checks: the accounts it resolves to, the existing transactions it may
     * duplicate (with the criteria of {@link DetectDuplicatesAction}, but
     * naming them), and the key by which it may duplicate another row.
     */
    private static final class InspectAction implements ImportAction
    {
        private final Entry entry;

        InspectAction(Entry entry)
        {
            this.entry = entry;
        }

        @Override
        public Status process(AccountTransaction transaction, Account account)
        {
            entry.cashAccount = account;
            entry.duplicateKey = key(account, transaction);
            findDuplicates(transaction, account.getTransactions());
            return Status.OK_STATUS;
        }

        @Override
        public Status process(PortfolioTransaction transaction, Portfolio portfolio)
        {
            entry.investmentAccount = portfolio;
            entry.duplicateKey = key(portfolio, transaction);
            findDuplicates(transaction, portfolio.getTransactions());
            return Status.OK_STATUS;
        }

        @Override
        public Status process(BuySellEntry buySell, Account account, Portfolio portfolio)
        {
            entry.cashAccount = account;
            entry.investmentAccount = portfolio;
            entry.duplicateKey = key(portfolio, buySell.getPortfolioTransaction());
            findDuplicates(buySell.getAccountTransaction(), account.getTransactions());
            findDuplicates(buySell.getPortfolioTransaction(), portfolio.getTransactions());
            return Status.OK_STATUS;
        }

        @Override
        public Status process(AccountTransferEntry transfer, Account source, Account target)
        {
            entry.cashAccount = source;
            entry.targetCashAccount = target;
            entry.duplicateKey = key(source, transfer.getSourceTransaction());
            findDuplicates(transfer.getSourceTransaction(), source.getTransactions());
            return Status.OK_STATUS;
        }

        @Override
        public Status process(PortfolioTransferEntry transfer, Portfolio source, Portfolio target)
        {
            entry.investmentAccount = source;
            entry.targetInvestmentAccount = target;
            entry.duplicateKey = key(source, transfer.getSourceTransaction());
            findDuplicates(transfer.getTargetTransaction(), source.getTransactions());
            return Status.OK_STATUS;
        }

        private void findDuplicates(AccountTransaction subject, List<AccountTransaction> existing)
        {
            var wanted = TransactionsHandler.duplicateKey(subject);
            existing.stream().filter(t -> TransactionsHandler.duplicateKey(t).equals(wanted))
                            .forEach(t -> entry.duplicateOf.add(t.getUUID()));
        }

        private void findDuplicates(PortfolioTransaction subject, List<PortfolioTransaction> existing)
        {
            var wanted = portfolioKey(subject);
            existing.stream().filter(t -> portfolioKey(t).equals(wanted))
                            .forEach(t -> entry.duplicateOf.add(t.getUUID()));
        }

        private static List<Object> key(Account owner, AccountTransaction transaction)
        {
            var key = new ArrayList<>(TransactionsHandler.duplicateKey(transaction));
            key.add(0, owner.getUUID());
            return key;
        }

        private static List<Object> key(Portfolio owner, PortfolioTransaction transaction)
        {
            var key = new ArrayList<>(portfolioKey(transaction));
            key.add(0, owner.getUUID());
            return key;
        }

        private static List<Object> portfolioKey(PortfolioTransaction transaction)
        {
            return Arrays.asList(TransactionsHandler.duplicateClass(transaction.getType()),
                            transaction.getDateTime().toLocalDate(), transaction.getCurrencyCode(),
                            transaction.getAmount(), transaction.getShares(),
                            transaction.getSecurity() != null ? transaction.getSecurity().getUUID() : null);
        }
    }

    /** the checks of the wizard's review page, in its order, with a stable code each */
    private static final List<String> CHECK_CODES = List.of("transaction-date", "transaction-type",
                    "instrument-values", "duplicate", "currency", "forex-gross-value");

    private final OpenFile file;
    private final Client client;
    private final HostApplication host;
    private final CsvImportRequest request;

    private ImportType type;
    private CsvConfiguration configuration;
    private CSVImporter importer;
    private final Set<Column> explicitlyFormatted = new HashSet<>();

    private final Targets targets = new Targets();
    private Security priceTarget;
    private final Map<CsvImportRequest.ExpectedBalance, Account> expectedBalanceAccounts = new LinkedHashMap<>();

    private final Map<CsvImportRequest.ExpectedBalance, Long> expectedBefore = new HashMap<>();

    private final List<Entry> entries = new ArrayList<>();
    private final List<JsonObject> parseErrors = new ArrayList<>();
    private final Set<Security> securitiesBefore = new HashSet<>();
    private int dataLines;

    private CsvImportSession(OpenFile file, HostApplication host, CsvImportRequest request)
    {
        this.file = file;
        this.client = file.getClient();
        this.host = host;
        this.request = request;
    }

    /** evaluates the import without changing the file */
    public static JsonObject preview(OpenFile file, HostApplication host, JsonObject body)
    {
        var session = new CsvImportSession(file, host, CsvImportRequest.parse(body));
        session.prepare();
        return session.type == ImportType.INSTRUMENT_PRICES ? session.prices(false) : session.items(false);
    }

    /** evaluates the import and inserts what passes */
    public static JsonObject commit(OpenFile file, HostApplication host, JsonObject body)
    {
        var session = new CsvImportSession(file, host, CsvImportRequest.parse(body));
        session.prepare();
        return session.type == ImportType.INSTRUMENT_PRICES ? session.prices(true) : session.items(true);
    }

    // ------------------------------------------------------------------
    // preparation: configuration, parsing, column mapping, targets
    // ------------------------------------------------------------------

    private void prepare()
    {
        var errors = new ArrayList<ApiException.FieldError>();

        resolveConfigurationAndType(errors);
        if (!errors.isEmpty())
            throw ApiException.validation(errors);

        importer = new CSVImporter(client, null);
        importer.getExtractorByCode(type.extractorCode()).ifPresent(importer::setExtractor);

        if (configuration != null)
            configuration.config().writeTo(importer);

        // data given as text is UTF-8, whatever a configuration says about
        // the file it was made for; raw data is read in the given encoding,
        // else in that of the configuration
        if (request.encoding() != null)
            importer.setEncoding(request.encoding());
        else if (configuration == null)
            importer.setEncoding(StandardCharsets.UTF_8);

        if (request.delimiter() != null)
            importer.setDelimiter(request.delimiter());
        else if (configuration == null)
            importer.setDelimiter(detectDelimiter());

        if (request.skipLines() != null)
            importer.setSkipLines(request.skipLines());
        else if (configuration == null)
            importer.setSkipLines(0);

        if (request.firstLineHeader() != null)
            importer.setFirstLineHeader(request.firstLineHeader());
        else if (configuration == null)
            importer.setFirstLineHeader(true);

        try
        {
            importer.processStream(new ByteArrayInputStream(withoutByteOrderMark()), configuration == null);
        }
        catch (IOException e)
        {
            throw ApiException.validation(List.of(new ApiException.FieldError("csv", "invalid-value",
                            "the data could not be read: " + e.getMessage())));
        }

        var parseError = importer.getParseError();
        if (parseError.isPresent())
            throw ApiException.validation(List.of(new ApiException.FieldError("csv", "invalid-value",
                            "the data is not well-formed CSV: " + parseError.get())));

        dataLines = importer.getRawValues().size();

        mapColumns(errors);
        resolveTargets(errors);

        if (!errors.isEmpty())
            throw ApiException.validation(errors);
    }

    private void resolveConfigurationAndType(List<ApiException.FieldError> errors)
    {
        if (request.configuration() != null)
        {
            var available = host.listCsvConfigurations();
            configuration = available.stream().filter(c -> request.configuration().equals(c.config().getLabel()))
                            .findFirst().orElse(null);
            if (configuration == null)
            {
                errors.add(new ApiException.FieldError("configuration", "invalid-value",
                                "unknown configuration '" + request.configuration() + "'; available: "
                                                + available.stream().map(c -> c.config().getLabel())
                                                                .collect(Collectors.joining(", "))));
                return;
            }
        }

        if (request.type() != null)
        {
            type = ImportType.byWireName(request.type());
            if (type == null)
            {
                errors.add(new ApiException.FieldError("type", "invalid-value",
                                "unknown type '" + request.type() + "'; known types: " + ImportType.wireNames()));
                return;
            }
        }

        if (configuration != null)
        {
            var configured = ImportType.byExtractorCode(configuration.config().getTarget());
            if (type == null)
                type = configured;
            else if (configured != type)
                errors.add(new ApiException.FieldError("type", "invalid-value", "configuration '"
                                + configuration.config().getLabel() + "' imports "
                                + (configured != null ? configured.wireName() : configuration.config().getTarget())
                                + ", not " + type.wireName()));
        }

        if (type == null && errors.isEmpty())
            errors.add(new ApiException.FieldError("type", "required", "type is required; known types: "
                            + ImportType.wireNames()));
    }

    /**
     * Picks the delimiter that occurs most often (outside of quotes) in the
     * first line that is read - the header, if there is one.
     */
    private char detectDelimiter()
    {
        var text = new String(withoutByteOrderMark(), importer.getEncoding());
        var lines = text.split("\r?\n", -1);
        var skip = request.skipLines() != null ? request.skipLines() : 0;
        var line = skip < lines.length ? lines[skip] : "";

        var candidates = ";,\t|";
        var counts = new int[candidates.length()];
        var quoted = false;
        for (var c : line.toCharArray())
        {
            if (c == '"')
                quoted = !quoted;
            else if (!quoted && candidates.indexOf(c) >= 0)
                counts[candidates.indexOf(c)]++;
        }

        var best = 0;
        for (int ii = 1; ii < counts.length; ii++)
            if (counts[ii] > counts[best])
                best = ii;
        return counts[best] == 0 ? ',' : candidates.charAt(best);
    }

    /**
     * The data without a leading UTF-8 byte order mark. Spreadsheet programs
     * like to write one, and the CSV parser would read it as part of the first
     * header - which then matches no field.
     */
    private byte[] withoutByteOrderMark()
    {
        var data = request.data();
        if (StandardCharsets.UTF_8.equals(importer.getEncoding()) && data.length >= 3 && (data[0] & 0xFF) == 0xEF
                        && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF)
            return Arrays.copyOfRange(data, 3, data.length);
        return data;
    }

    /**
     * Guesses the decimal separator of an amount column from its values. A
     * wrong guess is not a parse error but a wrong amount - "1.234,56" read
     * with "." as separator is 1.23456 - so the separator is decided by the
     * values that are unambiguous: one whose last separator is followed by
     * one or two digits ("1.234,56", "12,5", "-61.20"). A value such as
     * "1,234" or "1.234" says nothing and is ignored. Without any evidence,
     * the default is ".".
     */
    private String guessDecimalSeparator(Column column)
    {
        int comma = 0;
        int dot = 0;
        for (var values : importer.getRawValues())
        {
            if (column.getColumnIndex() >= values.length || values[column.getColumnIndex()] == null)
                continue;

            var value = values[column.getColumnIndex()].trim();
            var lastComma = value.lastIndexOf(',');
            var lastDot = value.lastIndexOf('.');
            var last = Math.max(lastComma, lastDot);
            if (last < 0)
                continue;

            var decimals = value.substring(last + 1).replaceAll("[^0-9].*$", "");
            if (decimals.isEmpty() || decimals.length() > 2)
                continue;

            if (last == lastComma)
                comma++;
            else
                dot++;
        }
        return comma > dot ? "," : ".";
    }

    private void mapColumns(List<ApiException.FieldError> errors)
    {
        var extractor = importer.getExtractor();
        var columns = importer.getColumns();

        // headers in the import's own vocabulary: a column named like a
        // field code maps to that field, whatever the user interface language
        if (configuration == null)
        {
            var mapped = Arrays.stream(columns).map(Column::getField).filter(Objects::nonNull)
                            .collect(Collectors.toSet());
            for (var column : columns)
            {
                if (column.getField() != null)
                    continue;
                var normalized = normalize(column.getLabel());
                extractor.getFields().stream()
                                .filter(f -> !mapped.contains(f) && normalize(f.getCode()).equals(normalized))
                                .findFirst().ifPresent(f -> {
                                    column.setField(f);
                                    mapped.add(f);
                                });
            }
        }

        for (var spec : request.columns())
            applyColumnSpec(spec, columns, errors);

        for (var column : columns)
        {
            var field = column.getField();
            if (field == null || explicitlyFormatted.contains(column))
                continue;

            if (field instanceof AmountField)
            {
                if (request.decimalSeparator() != null)
                    column.setFormat(amountFormat(field, String.valueOf(request.decimalSeparator())));
                else if (configuration == null || column.getFormat() == null)
                    column.setFormat(amountFormat(field, guessDecimalSeparator(column)));
            }
            else if (field instanceof DateField)
            {
                if (request.dateFormat() != null)
                {
                    var format = dateFormat(field, request.dateFormat());
                    if (format == null)
                        errors.add(new ApiException.FieldError("dateFormat", "invalid-value",
                                        "'" + request.dateFormat() + "' is not a valid date pattern"));
                    column.setFormat(format);
                }
                else if (column.getFormat() == null)
                {
                    column.setFormat(field.guessFormat(client, importer.getFirstNonEmptyValue(column)));
                }
            }
            else if (field instanceof EnumField<?> enumField)
            {
                if (configuration == null || column.getFormat() == null)
                    column.setFormat(canonicalEnumFormat(enumField));
            }
            else if (column.getFormat() == null)
            {
                column.setFormat(field.guessFormat(client, importer.getFirstNonEmptyValue(column)));
            }
        }

        // each field at most once, and every required field at least once
        var byField = new LinkedHashMap<Field, List<Column>>();
        for (var column : columns)
            if (column.getField() != null)
                byField.computeIfAbsent(column.getField(), f -> new ArrayList<>()).add(column);

        byField.forEach((field, mapped) -> {
            if (mapped.size() > 1)
                errors.add(new ApiException.FieldError("columns", "invalid-value",
                                "field '" + field.getCode() + "' is assigned to several columns: "
                                                + mapped.stream().map(this::describe)
                                                                .collect(Collectors.joining(", "))));
        });

        for (var field : extractor.getFields())
        {
            if (!field.isOptional() && !byField.containsKey(field))
                errors.add(new ApiException.FieldError("columns", "required",
                                "required field '" + field.getCode() + "' (" + field.getName()
                                                + ") is not assigned to a column; the columns are: "
                                                + Arrays.stream(columns).map(this::describe)
                                                                .collect(Collectors.joining(", "))));
        }
    }

    private void applyColumnSpec(CsvImportRequest.ColumnSpec spec, Column[] columns,
                    List<ApiException.FieldError> errors)
    {
        var name = "columns[" + spec.position() + "]";

        Column column = null;
        if (spec.index() != null)
        {
            if (spec.index() >= columns.length)
            {
                errors.add(new ApiException.FieldError(name + ".index", "invalid-range",
                                "the data has " + columns.length + " columns (index 0 to " + (columns.length - 1)
                                                + ")"));
                return;
            }
            column = columns[spec.index()];
        }
        else
        {
            var matches = Arrays.stream(columns).filter(c -> spec.header().equals(c.getLabel())).toList();
            if (matches.isEmpty())
                matches = Arrays.stream(columns).filter(c -> spec.header().trim().equalsIgnoreCase(c.getLabel().trim()))
                                .toList();
            if (matches.size() != 1)
            {
                errors.add(new ApiException.FieldError(name + ".header", "invalid-value",
                                (matches.isEmpty() ? "no column with header '" : "several columns with header '")
                                                + spec.header() + "'; address it by index instead. The columns are: "
                                                + Arrays.stream(columns).map(this::describe)
                                                                .collect(Collectors.joining(", "))));
                return;
            }
            column = matches.get(0);
        }

        if (spec.hasField())
        {
            if (spec.field() == null)
            {
                column.setField(null);
            }
            else
            {
                var field = importer.getExtractor().getField(spec.field());
                if (field == null)
                {
                    errors.add(new ApiException.FieldError(name + ".field", "invalid-value",
                                    "unknown field '" + spec.field() + "' for " + type.wireName() + "; known fields: "
                                                    + importer.getExtractor().getFields().stream().map(Field::getCode)
                                                                    .collect(Collectors.joining(", "))));
                    return;
                }

                // a field moved here is taken from where it was assigned automatically
                for (var other : columns)
                    if (other != column && other.getField() == field)
                        other.setField(null);

                column.setField(field);
            }
        }

        if (spec.format() == null || spec.format().isJsonNull())
            return;

        var field = column.getField();
        if (field == null)
        {
            errors.add(new ApiException.FieldError(name + ".format", "invalid-value",
                            "a format needs a field; the column " + describe(column) + " has none"));
            return;
        }

        var format = parseFormat(field, spec.format());
        if (format == null)
        {
            errors.add(new ApiException.FieldError(name + ".format", "invalid-value", formatHint(field)));
            return;
        }

        column.setFormat(format);
        explicitlyFormatted.add(column);
    }

    private FieldFormat parseFormat(Field field, JsonElement format)
    {
        if (field instanceof EnumField<?> enumField)
            return format.isJsonObject() ? enumFormat(enumField, format.getAsJsonObject()) : null;

        if (!format.isJsonPrimitive())
            return null;

        var text = format.getAsString();
        if (field instanceof AmountField)
            return amountFormat(field, text);
        if (field instanceof DateField)
            return dateFormat(field, text);
        return null;
    }

    private String formatHint(Field field)
    {
        if (field instanceof AmountField)
            return "an amount format is \"0,000.00\" (or \".\") or \"0.000,00\" (or \",\")";
        if (field instanceof DateField)
            return "a date format is a pattern such as \"yyyy-MM-dd\" or \"dd.MM.yyyy\"; predefined: "
                            + field.getAvailableFieldFormats().stream().map(FieldFormat::getCode)
                                            .collect(Collectors.joining(", "));
        if (field instanceof EnumField<?> enumField)
            return "the format of a type column maps types to patterns, e.g. {\"buy\": \"Kauf|Buy\"}; types: "
                            + Arrays.stream(enumField.getEnumType().getEnumConstants()).map(TransactionJson::wireType)
                                            .collect(Collectors.joining(", "));
        return "field '" + field.getCode() + "' takes no format";
    }

    private static FieldFormat amountFormat(Field field, String text)
    {
        var code = switch (text)
        {
            case "." -> "0,000.00";
            case "," -> "0.000,00";
            default -> text;
        };
        return field.getAvailableFieldFormats().stream().filter(f -> code.equals(f.getCode())).findFirst()
                        .orElse(null);
    }

    private static FieldFormat dateFormat(Field field, String pattern)
    {
        var predefined = field.getAvailableFieldFormats().stream().filter(f -> pattern.equals(f.getCode()))
                        .findFirst();
        if (predefined.isPresent())
            return predefined.get();

        try
        {
            return new FieldFormat(pattern, pattern, new SimpleDateFormat(pattern, Locale.US));
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * The type column accepts the user interface's own label, the API's wire
     * name and the model's enum name of each type, case-insensitively and only
     * as the whole value - so that a file written by a program reads the same
     * whatever language the application runs in.
     */
    private static <M extends Enum<M>> FieldFormat canonicalEnumFormat(EnumField<M> field)
    {
        var format = new EnumMapFormat<>(field.getEnumType());
        for (var entry : format.map().entrySet())
        {
            var alternatives = new LinkedHashSet<String>();
            // the default label may itself be an alternation ("Withdrawal|Removal")
            for (var label : entry.getValue().split("\\|"))
                alternatives.add(Pattern.quote(label));
            alternatives.add(Pattern.quote(entry.getKey().name()));
            alternatives.add(Pattern.quote(TransactionJson.wireType(entry.getKey())));
            if (entry.getKey() == AccountTransaction.Type.REMOVAL)
                alternatives.add(Pattern.quote("withdrawal"));

            entry.setValue("(?i)^(?:" + String.join("|", alternatives) + ")$");
        }
        return new FieldFormat(null, format);
    }

    /** the canonical mapping, with the patterns given for some types replaced */
    private static <M extends Enum<M>> FieldFormat enumFormat(EnumField<M> field, JsonObject patterns)
    {
        var canonical = canonicalEnumFormat(field);
        @SuppressWarnings("unchecked")
        var map = ((EnumMapFormat<M>) canonical.getFormat()).map();

        for (var entry : patterns.entrySet())
        {
            var constant = Arrays.stream(field.getEnumType().getEnumConstants())
                            .filter(c -> TransactionJson.wireType(c).equals(entry.getKey())).findFirst();
            if (constant.isEmpty() || !entry.getValue().isJsonPrimitive())
                return null;

            var pattern = entry.getValue().getAsString();
            try
            {
                Pattern.compile(pattern);
            }
            catch (PatternSyntaxException e)
            {
                return null;
            }
            map.put(constant.get(), pattern);
        }
        return canonical;
    }

    private void resolveTargets(List<ApiException.FieldError> errors)
    {
        resolveCashAccounts("cashAccounts", request.cashAccounts(), targets.cash, errors);
        resolveCashAccounts("targetCashAccounts", request.targetCashAccounts(), targets.targetCash, errors);

        if (request.investmentAccount() != null)
        {
            targets.portfolio = portfolio(request.investmentAccount());
            if (targets.portfolio == null)
                errors.add(new ApiException.FieldError("investmentAccount", "invalid-value",
                                "no investment account with uuid " + request.investmentAccount()));
        }

        if (request.targetInvestmentAccount() != null)
        {
            targets.targetPortfolio = portfolio(request.targetInvestmentAccount());
            if (targets.targetPortfolio == null)
                errors.add(new ApiException.FieldError("targetInvestmentAccount", "invalid-value",
                                "no investment account with uuid " + request.targetInvestmentAccount()));
        }

        if (type == ImportType.INSTRUMENT_PRICES)
        {
            if (request.instrument() == null)
                errors.add(new ApiException.FieldError("instrument", "required",
                                "instrument-prices imports into one instrument: give its uuid as instrument"));
            else
            {
                priceTarget = client.getSecurities().stream().filter(s -> s.getUUID().equals(request.instrument()))
                                .findFirst().orElse(null);
                if (priceTarget == null)
                    errors.add(new ApiException.FieldError("instrument", "invalid-value",
                                    "no instrument with uuid " + request.instrument()));
            }
        }
        else if (request.instrument() != null)
        {
            errors.add(new ApiException.FieldError("instrument", "invalid-value",
                            "instrument applies to instrument-prices only"));
        }

        for (var expected : request.expectedBalances())
        {
            if (expected.cashAccount() == null)
            {
                expectedBalanceAccounts.put(expected, null); // resolved after the evaluation
                continue;
            }

            var account = account(expected.cashAccount());
            if (account == null)
                errors.add(new ApiException.FieldError("expectedBalances[" + expected.position() + "].cashAccount",
                                "invalid-value", "no cash account with uuid " + expected.cashAccount()));
            expectedBalanceAccounts.put(expected, account);
        }
    }

    private void resolveCashAccounts(String name, List<String> uuids, Map<String, Account> target,
                    List<ApiException.FieldError> errors)
    {
        for (var uuid : uuids)
        {
            var account = account(uuid);
            if (account == null)
            {
                errors.add(new ApiException.FieldError(name, "invalid-value", "no cash account with uuid " + uuid));
                continue;
            }

            var previous = target.put(account.getCurrencyCode(), account);
            if (previous != null && previous != account)
                errors.add(new ApiException.FieldError(name, "invalid-value", "several cash accounts in "
                                + account.getCurrencyCode() + "; give at most one per currency"));
        }
    }

    // ------------------------------------------------------------------
    // evaluation and insertion of transactions and instruments
    // ------------------------------------------------------------------

    private JsonObject items(boolean commit)
    {
        securitiesBefore.addAll(client.getSecurities());

        var errors = new ArrayList<Exception>();
        for (var item : importer.createItems(errors))
            entries.add(new Entry(item));
        collectParseErrors(errors);

        evaluate();

        var flowsBefore = cashFlows();
        var balancesBefore = balancesBefore(flowsBefore);

        List<Security> created = List.of();
        var imported = 0;
        if (commit)
        {
            var candidates = referencedNewSecurities();
            imported = insert();
            created = candidates.stream().filter(client.getSecurities()::contains).toList();

            if (imported > 0)
            {
                client.markDirty();
                host.afterImport(file, created);
                PortfolioLog.info(MessageFormat.format(Messages.MsgApiCsvImported, imported, type.wireName(),
                                file.getLabel()));
            }
        }

        var report = header(commit);
        report.add("targets", targetsJson());
        report.add("summary", summary(commit ? imported : -1));

        if (request.itemDetail() != ItemDetail.NONE)
        {
            var items = new JsonArray();
            for (var entry : entries)
            {
                if (request.itemDetail() == ItemDetail.ISSUES && entry.messages.isEmpty() && entry.doImport)
                    continue;
                items.add(toJson(entry, commit));
            }
            report.add("items", items);
        }

        var parseErrorArray = new JsonArray();
        parseErrors.forEach(parseErrorArray::add);
        report.add("parseErrors", parseErrorArray);

        report.add("balances", balances(flowsBefore, balancesBefore, commit));
        report.add("reconciliation", reconciliation(flowsBefore, commit));

        if (commit)
        {
            var array = new JsonArray();
            created.forEach(s -> array.add(EntityJson.reference(s)));
            report.add("createdInstruments", array);
            report.add("consistencyIssues", imported > 0 ? consistencyIssues() : new JsonArray());
        }

        return report;
    }

    private void collectParseErrors(List<Exception> errors)
    {
        for (var error : errors)
        {
            var json = new JsonObject();
            if (error instanceof CSVLineException lineError)
            {
                json.addProperty("line", lineError.getLineNo());
                json.addProperty("message", lineError.getReason());
            }
            else
            {
                json.addProperty("message", error.getMessage());
            }
            parseErrors.add(json);
        }
    }

    private void evaluate()
    {
        var checks = List.<ImportAction>of(new CheckTransactionDateAction(), new CheckValidTypesAction(),
                        new CheckSecurityRelatedValuesAction(), new DetectDuplicatesAction(client),
                        new CheckCurrenciesAction(), new CheckForexGrossValueAction());

        for (var entry : entries)
        {
            var item = entry.item;

            if (item.isFailure())
            {
                entry.add("extraction", new Status(Status.Code.ERROR, item.getFailureMessage()));
                continue;
            }

            if (item.isSkipped())
            {
                entry.add("extraction", new Status(Status.Code.SKIP,
                                item.getFailureMessage() != null ? item.getFailureMessage() : "skipped by the import"));
                continue;
            }

            targets.misses.clear();

            for (int ii = 0; ii < checks.size(); ii++)
            {
                try
                {
                    entry.add(CHECK_CODES.get(ii), item.apply(checks.get(ii), targets));
                }
                catch (Exception e)
                {
                    // as on the review page: an unexpected failure of a check
                    // makes the item not importable, but does not abort
                    PortfolioLog.error(e);
                    entry.add(CHECK_CODES.get(ii), new Status(Status.Code.ERROR, String.valueOf(e.getMessage())));
                }
            }

            if (!targets.misses.isEmpty())
            {
                // the generic "currency does not match account ?" of the items
                // hides what is missing - say it
                entry.messages.clear();
                entry.maxCode = Status.Code.OK;
                new LinkedHashSet<>(targets.misses)
                                .forEach(m -> entry.add("target", new Status(Status.Code.ERROR, m)));
                continue;
            }

            try
            {
                item.apply(new InspectAction(entry), targets);
            }
            catch (RuntimeException e)
            {
                PortfolioLog.error(e);
            }
        }

        flagDuplicatesWithinFile();
        decide();
    }

    /**
     * A row that repeats another row of the same file is just as suspicious as
     * one that repeats an existing transaction - bank exports that overlap with
     * themselves are not unheard of. Flagged as a warning, like the check of
     * the review page, so that a genuine repetition can still be accepted.
     */
    private void flagDuplicatesWithinFile()
    {
        var groups = new LinkedHashMap<List<Object>, List<Entry>>();
        for (var entry : entries)
            if (entry.duplicateKey != null)
                groups.computeIfAbsent(entry.duplicateKey, k -> new ArrayList<>()).add(entry);

        for (var group : groups.values())
        {
            if (group.size() < 2)
                continue;

            for (var entry : group)
            {
                group.stream().filter(e -> e != entry && e.line != null).forEach(e -> entry.duplicateOfLines.add(e.line));
                entry.add("duplicate-in-file", new Status(Status.Code.WARNING, "repeats line(s) "
                                + entry.duplicateOfLines.stream().map(String::valueOf)
                                                .collect(Collectors.joining(", "))
                                + " of this file"));
            }
        }
    }

    /**
     * Decides per entry whether it is (or would be) imported, with the rules
     * of the review page: errors never, warnings only if accepted, nothing
     * that the client excluded. Instruments that are created on the fly for
     * transactions are imported only if a transaction that refers to them is.
     */
    private void decide()
    {
        for (var entry : entries)
        {
            if (entry.item instanceof Extractor.SecurityItem && entry.line == null)
                continue;

            if (entry.line != null && request.excludeLines().contains(entry.line))
                reject(entry, "excluded");
            else if (entry.maxCode == Status.Code.SKIP)
                reject(entry, "skipped");
            else if (entry.maxCode == Status.Code.ERROR)
                reject(entry, "error");
            else if (entry.maxCode == Status.Code.WARNING && !entry.item.isInvestmentPlanItem()
                            && (entry.line == null || !request.acceptWarnings().contains(entry.line)))
                reject(entry, "warning-not-accepted");
            else
                entry.doImport = true;
        }

        var needed = entries.stream().filter(e -> e.doImport && !(e.item instanceof Extractor.SecurityItem))
                        .map(e -> e.item.getSecurity()).filter(Objects::nonNull).collect(Collectors.toSet());

        for (var entry : entries)
        {
            if (!(entry.item instanceof Extractor.SecurityItem) || entry.line != null)
                continue;

            if (entry.maxCode == Status.Code.ERROR)
                reject(entry, "error");
            else if (needed.contains(entry.item.getSecurity()))
                entry.doImport = true;
            else
                reject(entry, "not-referenced");
        }
    }

    private static void reject(Entry entry, String reason)
    {
        entry.doImport = false;
        entry.reason = reason;
    }

    /** the securities that an import of the selected entries would create */
    private Set<Security> referencedNewSecurities()
    {
        var result = new LinkedHashSet<Security>();
        for (var entry : entries)
        {
            var security = entry.item.getSecurity();
            if (entry.doImport && security != null && !client.getSecurities().contains(security))
                result.add(security);
        }
        return result;
    }

    /** inserts the selected entries, exactly as ImportController does for the wizard */
    private int insert()
    {
        var action = new InsertAction(client);
        action.setConvertBuySellToDelivery(request.convertBuySellToDelivery());
        action.setRemoveDividends(request.removeDividends());

        var count = 0;
        for (var entry : entries)
        {
            if (!entry.doImport)
                continue;

            var item = entry.item;
            if (!request.importNotes())
                item.setNote(null);

            action.setInvestmentPlanItem(item.isInvestmentPlanItem());
            enrichMissingExDate(item);

            item.apply(action, targets);
            count++;
        }
        return count;
    }

    /**
     * Fills in the ex-date of a dividend from the dividend payments known for
     * the instrument. Unlike the wizard, this does not query an online
     * dividend feed: the API must not make network calls on the user's behalf
     * while holding the UI thread.
     */
    private static void enrichMissingExDate(Item item)
    {
        if (!(item.getSubject() instanceof AccountTransaction transaction))
            return;

        if (transaction.getType() != AccountTransaction.Type.DIVIDENDS || transaction.getExDate() != null
                        || transaction.getSecurity() == null)
            return;

        var events = transaction.getSecurity().getEvents().stream()
                        .filter(event -> event.getType() == SecurityEvent.Type.DIVIDEND_PAYMENT)
                        .map(DividendEvent.class::cast).toList();

        DividendEvent.findExDateByPaymentDate(transaction.getDateTime().toLocalDate(), events)
                        .ifPresent(exDate -> transaction.setExDate(exDate.atStartOfDay()));
    }

    // ------------------------------------------------------------------
    // balances and reconciliation
    // ------------------------------------------------------------------

    /** the cash flows of the entries to import, per account, as the insert would book them */
    private Map<Account, List<CashBalances.CashFlow>> cashFlows()
    {
        var action = new CashBalances.CashFlowAction(request.convertBuySellToDelivery(), request.removeDividends());
        for (var entry : entries)
        {
            if (entry.doImport && !(entry.item instanceof Extractor.SecurityItem))
            {
                try
                {
                    entry.item.apply(action, targets);
                }
                catch (RuntimeException e)
                {
                    PortfolioLog.error(e);
                }
            }
        }

        var result = new LinkedHashMap<Account, List<CashBalances.CashFlow>>();
        for (var flow : action.getFlows())
            result.computeIfAbsent(flow.account(), a -> new ArrayList<>()).add(flow);
        return result;
    }

    /** per affected account: the last day the import books on, and the balance at its end before the import */
    private Map<Account, long[]> balancesBefore(Map<Account, List<CashBalances.CashFlow>> flows)
    {
        var result = new IdentityHashMap<Account, long[]>();
        for (var entry : flows.entrySet())
        {
            var last = entry.getValue().stream().map(CashBalances.CashFlow::date).max(LocalDate::compareTo)
                            .orElseThrow();
            result.put(entry.getKey(), new long[] { last.toEpochDay(),
                            CashBalances.balanceAt(entry.getKey(), last) });
        }

        // expected balances: the balance before the import at the stated date
        for (var expected : expectedBalanceAccounts.entrySet())
        {
            var account = expected.getValue() != null ? expected.getValue()
                            : flows.size() == 1 ? flows.keySet().iterator().next() : null;
            if (account != null)
                expectedBefore.put(expected.getKey(), CashBalances.balanceAt(account, expected.getKey().date()));
        }

        return result;
    }

    /**
     * The balance of each cash account the import books on, at the end of the
     * last day it books on: before the import, the import's own cash flow, and
     * after it. The terms reconcile:
     * {@code balanceBefore + importedCashFlow = balanceAfter}. In a preview,
     * {@code balanceAfter} is projected; on commit it is read from the file.
     */
    private JsonArray balances(Map<Account, List<CashBalances.CashFlow>> flows, Map<Account, long[]> before,
                    boolean commit)
    {
        var array = new JsonArray();
        for (var entry : flows.entrySet())
        {
            var account = entry.getKey();
            var currency = account.getCurrencyCode();
            var date = LocalDate.ofEpochDay(before.get(account)[0]);
            var balanceBefore = before.get(account)[1];
            var flow = entry.getValue().stream().mapToLong(CashBalances.CashFlow::amount).sum();
            var balanceAfter = commit ? CashBalances.balanceAt(account, date) : balanceBefore + flow;

            var json = new JsonObject();
            json.add("cashAccount", EntityJson.reference(account));
            json.addProperty("date", date.toString());
            json.add("balanceBefore", EntityJson.toJson(Money.of(currency, balanceBefore)));
            json.add("importedCashFlow", EntityJson.toJson(Money.of(currency, flow)));
            json.add("balanceAfter", EntityJson.toJson(Money.of(currency, balanceAfter)));
            array.add(json);
        }
        return array;
    }

    private JsonArray reconciliation(Map<Account, List<CashBalances.CashFlow>> flows, boolean commit)
    {
        var array = new JsonArray();
        for (var expectation : expectedBalanceAccounts.entrySet())
        {
            var expected = expectation.getKey();
            var account = expectation.getValue();

            var json = new JsonObject();
            json.addProperty("date", expected.date().toString());

            if (account == null && flows.size() == 1)
                account = flows.keySet().iterator().next();

            if (account == null)
            {
                json.add("expected", EntityJson.decimal(expected.balance()));
                json.addProperty("status", "unresolved");
                json.addProperty("message", flows.isEmpty()
                                ? "the import books on no cash account; give the cashAccount to reconcile"
                                : "the import books on several cash accounts; give the cashAccount to reconcile");
                array.add(json);
                continue;
            }

            var currency = account.getCurrencyCode();
            var expectedAmount = expected.balance().movePointRight(Values.Amount.precision())
                            .setScale(0, RoundingMode.HALF_UP).longValueExact();

            var before = expectedBefore.getOrDefault(expected, CashBalances.balanceAt(account, expected.date()));
            var flowUntil = flows.getOrDefault(account, List.of()).stream()
                            .filter(f -> !f.date().isAfter(expected.date()))
                            .mapToLong(CashBalances.CashFlow::amount).sum();
            var after = commit ? CashBalances.balanceAt(account, expected.date()) : before + flowUntil;

            json.add("cashAccount", EntityJson.reference(account));
            json.add("expected", EntityJson.toJson(Money.of(currency, expectedAmount)));
            json.add("balanceBefore", EntityJson.toJson(Money.of(currency, before)));
            json.add("balanceAfter", EntityJson.toJson(Money.of(currency, after)));
            json.add("difference", EntityJson.toJson(Money.of(currency, expectedAmount - after)));
            json.addProperty("status", expectedAmount == after ? "match" : "mismatch");
            array.add(json);
        }
        return array;
    }

    private JsonArray consistencyIssues()
    {
        var array = new JsonArray();
        try
        {
            for (var issue : Checker.runAll(client))
            {
                var json = new JsonObject();
                if (issue.getDate() != null)
                    json.addProperty("date", issue.getDate().toString());
                json.addProperty("message", issue.getLabel());
                var entity = issue.getEntity();
                if (entity instanceof Account account)
                    json.add("cashAccount", EntityJson.reference(account));
                else if (entity instanceof Portfolio portfolio)
                    json.add("investmentAccount", EntityJson.reference(portfolio));
                else if (entity instanceof Security security)
                    json.add("instrument", EntityJson.reference(security));
                array.add(json);
            }
        }
        catch (RuntimeException e)
        {
            PortfolioLog.error(e);
        }
        return array;
    }

    // ------------------------------------------------------------------
    // instrument prices
    // ------------------------------------------------------------------

    private JsonObject prices(boolean commit)
    {
        var errors = new ArrayList<Exception>();
        var extracted = importer.createItems(errors);
        collectParseErrors(errors);

        List<SecurityPrice> prices = extracted.isEmpty() ? List.of() : extracted.get(0).getSecurity().getPrices();

        var existing = new HashMap<LocalDate, Long>();
        priceTarget.getPrices().forEach(p -> existing.put(p.getDate(), p.getValue()));

        int added = 0;
        int changed = 0;
        int unchanged = 0;
        var items = new JsonArray();
        for (var price : prices)
        {
            var previous = existing.get(price.getDate());
            String status;
            if (previous == null)
            {
                status = "new";
                added++;
            }
            else if (previous != price.getValue())
            {
                status = "changed";
                changed++;
            }
            else
            {
                status = "unchanged";
                unchanged++;
            }

            if (request.itemDetail() == ItemDetail.ALL
                            || (request.itemDetail() == ItemDetail.ISSUES && "changed".equals(status)))
            {
                var json = new JsonObject();
                json.addProperty("date", price.getDate().toString());
                json.add("value", EntityJson.decimal(price.getValue(), Values.Quote.precision()));
                if (previous != null && previous != price.getValue())
                    json.add("previousValue", EntityJson.decimal(previous, Values.Quote.precision()));
                json.addProperty("status", status);
                items.add(json);
            }
        }

        if (commit)
        {
            var dirty = false;
            for (var price : prices)
                dirty |= priceTarget.addPrice(price);
            if (dirty)
            {
                client.markDirty();
                PortfolioLog.info(MessageFormat.format(Messages.MsgApiCsvImported, added + changed,
                                type.wireName(), file.getLabel()));
            }
        }

        var report = header(commit);
        report.add("instrument", EntityJson.reference(priceTarget));

        var summary = new JsonObject();
        summary.addProperty("lines", dataLines);
        summary.addProperty("prices", prices.size());
        summary.addProperty("new", added);
        summary.addProperty("changed", changed);
        summary.addProperty("unchanged", unchanged);
        summary.addProperty("parseErrors", parseErrors.size());
        report.add("summary", summary);

        if (request.itemDetail() != ItemDetail.NONE)
            report.add("items", items);

        var parseErrorArray = new JsonArray();
        parseErrors.forEach(parseErrorArray::add);
        report.add("parseErrors", parseErrorArray);
        return report;
    }

    // ------------------------------------------------------------------
    // report
    // ------------------------------------------------------------------

    private JsonObject header(boolean commit)
    {
        var report = new JsonObject();
        report.addProperty("dryRun", !commit);
        report.addProperty("type", type.wireName());
        if (configuration != null)
            report.addProperty("configuration", configuration.config().getLabel());

        var settings = new JsonObject();
        settings.addProperty("delimiter", String.valueOf(importer.getDelimiter()));
        settings.addProperty("encoding", importer.getEncoding().name());
        settings.addProperty("skipLines", importer.getSkipLines());
        settings.addProperty("firstLineHeader", importer.isFirstLineHeader());
        report.add("settings", settings);

        var columns = new JsonArray();
        for (var column : importer.getColumns())
            columns.add(columnJson(column));
        report.add("columns", columns);
        return report;
    }

    private JsonObject columnJson(Column column)
    {
        var json = new JsonObject();
        json.addProperty("index", column.getColumnIndex());
        json.addProperty("header", column.getLabel());

        var sample = importer.getFirstNonEmptyValue(column);
        if (sample != null)
            json.addProperty("sample", sample);

        var field = column.getField();
        if (field != null)
        {
            json.addProperty("field", field.getCode());
            json.addProperty("fieldLabel", field.getName());
            var format = formatJson(field, column.getFormat());
            if (format != null)
                json.add("format", format);
        }
        return json;
    }

    private static JsonElement formatJson(Field field, FieldFormat format)
    {
        if (format == null || field instanceof ISINField)
            return null;

        if (field instanceof EnumField<?> && format.getFormat() instanceof EnumMapFormat<?> enumFormat)
        {
            var json = new JsonObject();
            enumFormat.map().forEach((key, pattern) -> json.addProperty(TransactionJson.wireType(key), pattern));
            return json;
        }

        if (field instanceof AmountField || field instanceof DateField)
            return new JsonPrimitive(format.getCode());

        return null;
    }

    private JsonObject targetsJson()
    {
        var json = new JsonObject();

        var cash = new JsonArray();
        targets.cash.values().forEach(a -> cash.add(withDefaulted(EntityJson.reference(a),
                        targets.defaulted.contains("cash:" + a.getCurrencyCode()))));
        json.add("cashAccounts", cash);

        if (targets.portfolio != null)
            json.add("investmentAccount", withDefaulted(EntityJson.reference(targets.portfolio),
                            targets.defaulted.contains("portfolio")));

        var targetCash = new JsonArray();
        targets.targetCash.values().forEach(a -> targetCash.add(EntityJson.reference(a)));
        json.add("targetCashAccounts", targetCash);

        if (targets.targetPortfolio != null)
            json.add("targetInvestmentAccount", EntityJson.reference(targets.targetPortfolio));

        return json;
    }

    private static JsonObject withDefaulted(JsonObject reference, boolean defaulted)
    {
        reference.addProperty("defaulted", defaulted);
        return reference;
    }

    private JsonObject summary(int imported)
    {
        var json = new JsonObject();
        json.addProperty("lines", dataLines);
        json.addProperty("items", entries.size());
        json.addProperty("ok", count(e -> e.maxCode == Status.Code.OK));
        json.addProperty("warnings", count(e -> e.maxCode == Status.Code.WARNING));
        json.addProperty("errors", count(e -> e.maxCode == Status.Code.ERROR));
        json.addProperty("skipped", count(e -> e.maxCode == Status.Code.SKIP));
        json.addProperty("excluded", count(e -> "excluded".equals(e.reason)));
        json.addProperty("duplicates", count(e -> !e.duplicateOf.isEmpty() || !e.duplicateOfLines.isEmpty()));
        json.addProperty("parseErrors", parseErrors.size());
        json.addProperty("import", imported >= 0 ? imported : count(e -> e.doImport));
        json.addProperty("newInstruments", (int) entries.stream()
                        .filter(e -> e.doImport && e.item.getSecurity() != null
                                        && (imported >= 0 ? createdDuringCommit(e.item.getSecurity())
                                                        : !client.getSecurities().contains(e.item.getSecurity())))
                        .map(e -> e.item.getSecurity()).distinct().count());
        return json;
    }

    private boolean createdDuringCommit(Security security)
    {
        return client.getSecurities().contains(security) && !securitiesBefore.contains(security);
    }

    private long count(Predicate<Entry> predicate)
    {
        return entries.stream().filter(predicate).count();
    }

    private JsonObject toJson(Entry entry, boolean commit)
    {
        var item = entry.item;
        var json = new JsonObject();
        if (entry.line != null)
            json.addProperty("line", entry.line);

        var subject = item.getSubject();
        json.addProperty("kind", kind(item));
        var transactionType = transactionType(subject);
        if (transactionType != null)
            json.addProperty("type", transactionType);

        if (item.getDate() != null)
            json.addProperty("date", item.getDate().toLocalDate().toString());
        if (item.getAmount() != null)
            json.add("amount", EntityJson.toJson(item.getAmount()));
        if (item.getShares() != 0)
            json.add("shares", EntityJson.decimal(item.getShares(), Values.Share.precision()));

        var transaction = primaryTransaction(subject);
        if (transaction != null)
        {
            if (transaction.getUnit(Unit.Type.FEE).isPresent())
                json.add("fees", EntityJson.toJson(transaction.getUnitSum(Unit.Type.FEE)));
            if (transaction.getUnit(Unit.Type.TAX).isPresent())
                json.add("taxes", EntityJson.toJson(transaction.getUnitSum(Unit.Type.TAX)));
        }

        if (item.getSecurity() != null)
            json.add("instrument", instrumentJson(item.getSecurity(), commit));

        if (subject != null && subject.getNote() != null && !subject.getNote().isBlank())
            json.addProperty("note", subject.getNote());

        if (entry.cashAccount != null)
            json.add("cashAccount", EntityJson.reference(entry.cashAccount));
        if (entry.investmentAccount != null)
            json.add("investmentAccount", EntityJson.reference(entry.investmentAccount));
        if (entry.targetCashAccount != null)
            json.add("targetCashAccount", EntityJson.reference(entry.targetCashAccount));
        if (entry.targetInvestmentAccount != null)
            json.add("targetInvestmentAccount", EntityJson.reference(entry.targetInvestmentAccount));

        json.addProperty("status", switch (entry.maxCode)
        {
            case OK -> "ok";
            case WARNING -> "warning";
            case ERROR -> "error";
            case SKIP -> "skipped";
        });

        var messages = new JsonArray();
        for (var message : entry.messages)
        {
            var m = new JsonObject();
            m.addProperty("severity", switch (message.code())
            {
                case WARNING -> "warning";
                case ERROR -> "error";
                case SKIP -> "skipped";
                case OK -> "info";
            });
            m.addProperty("check", message.check());
            m.addProperty("message", message.text());
            messages.add(m);
        }
        json.add("messages", messages);

        if (!entry.duplicateOf.isEmpty())
        {
            var array = new JsonArray();
            entry.duplicateOf.forEach(array::add);
            json.add("potentialDuplicateOf", array);
        }
        if (!entry.duplicateOfLines.isEmpty())
        {
            var array = new JsonArray();
            entry.duplicateOfLines.forEach(array::add);
            json.add("repeatsLines", array);
        }

        json.addProperty("import", entry.doImport);
        if (entry.reason != null)
            json.addProperty("reason", entry.reason);

        return json;
    }

    /**
     * An instrument the import refers to. One the import would create has no
     * uuid in a preview: the object exists only for the duration of the
     * request (ADR 0004).
     */
    private JsonObject instrumentJson(Security security, boolean commit)
    {
        var isNew = commit ? createdDuringCommit(security) : !client.getSecurities().contains(security);

        var json = new JsonObject();
        if (!isNew || commit)
            json.addProperty("uuid", security.getUUID());
        json.addProperty("name", security.getName());
        if (security.getCurrencyCode() != null)
            json.addProperty("currencyCode", security.getCurrencyCode());
        if (security.getIsin() != null)
            json.addProperty("isin", security.getIsin());
        if (security.getWkn() != null)
            json.addProperty("wkn", security.getWkn());
        if (security.getTickerSymbol() != null)
            json.addProperty("tickerSymbol", security.getTickerSymbol());
        json.addProperty("new", isNew);
        return json;
    }

    private static String kind(Item item)
    {
        var subject = item.getSubject();
        if (item instanceof Extractor.SecurityItem)
            return "instrument";
        if (item instanceof Extractor.SecurityUpdateItem)
            return "instrument-update";
        if (subject instanceof AccountTransaction)
            return "cash-account-transaction";
        if (subject instanceof PortfolioTransaction)
            return "investment-account-transaction";
        if (subject instanceof BuySellEntry)
            return "buy-sell";
        if (subject instanceof AccountTransferEntry)
            return "cash-transfer";
        if (subject instanceof PortfolioTransferEntry)
            return "investment-transfer";
        return "other";
    }

    private static String transactionType(Object subject)
    {
        if (subject instanceof AccountTransaction at)
            return TransactionJson.wireType(at.getType());
        if (subject instanceof PortfolioTransaction pt)
            return TransactionJson.wireType(pt.getType());
        if (subject instanceof BuySellEntry entry)
            return TransactionJson.wireType(entry.getPortfolioTransaction().getType());
        if (subject instanceof AccountTransferEntry || subject instanceof PortfolioTransferEntry)
            return "transfer";
        return null;
    }

    private static Transaction primaryTransaction(Object subject)
    {
        if (subject instanceof Transaction t)
            return t;
        if (subject instanceof BuySellEntry entry)
            return entry.getPortfolioTransaction();
        if (subject instanceof AccountTransferEntry entry)
            return entry.getSourceTransaction();
        if (subject instanceof PortfolioTransferEntry entry)
            return entry.getSourceTransaction();
        return null;
    }

    private String describe(Column column)
    {
        return column.getColumnIndex() + " \"" + column.getLabel() + "\""
                        + (column.getField() != null ? " (" + column.getField().getCode() + ")" : "");
    }

    private static String normalize(String text)
    {
        return text == null ? "" : text.replaceAll("[\\s_\\-]", "").toLowerCase(Locale.ROOT);
    }

    private List<Account> activeAccounts(String currencyCode)
    {
        return client.getAccounts().stream().filter(a -> !a.isRetired())
                        .filter(a -> currencyCode.equals(a.getCurrencyCode())).toList();
    }

    private List<Portfolio> activePortfolios()
    {
        return client.getPortfolios().stream().filter(p -> !p.isRetired()).toList();
    }

    private Account account(String uuid)
    {
        return client.getAccounts().stream().filter(a -> a.getUUID().equals(uuid)).findFirst().orElse(null);
    }

    private Portfolio portfolio(String uuid)
    {
        return client.getPortfolios().stream().filter(p -> p.getUUID().equals(uuid)).findFirst().orElse(null);
    }
}
