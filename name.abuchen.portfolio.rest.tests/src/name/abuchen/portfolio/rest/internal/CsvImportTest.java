package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import org.json.simple.JSONObject;
import org.json.simple.JSONValue;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.datatransfer.csv.CSVConfig;
import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.rest.spi.CsvConfiguration;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class CsvImportTest
{
    private Client client;
    private Account giro;
    private AccountTransaction existingDeposit;
    private FakeHost.SavableFile file;
    private FakeHost host;

    @Before
    public void setUp()
    {
        client = new Client();
        giro = new AccountBuilder().deposit_("2024-01-02", Values.Amount.factorize(100)).addTo(client);
        giro.setName("Giro");
        existingDeposit = giro.getTransactions().get(0);

        file = new FakeHost.SavableFile("/tmp/test.xml", client);
        host = new FakeHost(List.of(file));
    }

    private static JsonObject body(String csv)
    {
        var body = new JsonObject();
        body.addProperty("type", "cash-account-transactions");
        body.addProperty("csv", csv);
        return body;
    }

    private static JsonArray ints(int... values)
    {
        var array = new JsonArray();
        for (var v : values)
            array.add(v);
        return array;
    }

    private static JsonObject item(JsonObject report, int line)
    {
        for (var element : report.getAsJsonArray("items"))
        {
            var item = element.getAsJsonObject();
            if (item.has("line") && item.get("line").getAsInt() == line)
                return item;
        }
        throw new AssertionError("no item for line " + line + " in " + report);
    }

    private static BigDecimal value(JsonElement money)
    {
        return money.getAsJsonObject().get("value").getAsBigDecimal();
    }

    private static List<String> checks(JsonObject item)
    {
        var checks = new ArrayList<String>();
        item.getAsJsonArray("messages").forEach(m -> checks.add(m.getAsJsonObject().get("check").getAsString()));
        return checks;
    }

    private static ApiException expectValidation(Runnable runnable)
    {
        try
        {
            runnable.run();
            fail("expected a validation error");
            return null;
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(422));
            return e;
        }
    }

    private static final String STATEMENT = """
                    date,value,note
                    2024-01-02,100.00,salary
                    2024-01-05,-30.00,groceries
                    2024-01-31,1.50,interest
                    """;

    @Test
    public void testPreviewMapsFieldCodeHeadersAndChangesNothing()
    {
        var report = CsvImportSession.preview(file, host, body(STATEMENT));

        assertThat(report.get("dryRun").getAsBoolean(), is(true));
        assertThat(report.getAsJsonObject("settings").get("delimiter").getAsString(), is(","));

        var columns = report.getAsJsonArray("columns");
        assertThat(columns.get(0).getAsJsonObject().get("field").getAsString(), is("date"));
        assertThat(columns.get(0).getAsJsonObject().get("format").getAsString(), is("yyyy-MM-dd"));
        assertThat(columns.get(1).getAsJsonObject().get("field").getAsString(), is("value"));
        assertThat(columns.get(1).getAsJsonObject().get("format").getAsString(), is("0,000.00"));
        assertThat(columns.get(2).getAsJsonObject().get("field").getAsString(), is("note"));

        var summary = report.getAsJsonObject("summary");
        assertThat(summary.get("lines").getAsInt(), is(3));
        assertThat(summary.get("items").getAsInt(), is(3));
        assertThat(summary.get("import").getAsInt(), is(2));
        assertThat(summary.get("duplicates").getAsInt(), is(1));

        // the only EUR account is the default target, and says so
        var target = report.getAsJsonObject("targets").getAsJsonArray("cashAccounts").get(0).getAsJsonObject();
        assertThat(target.get("uuid").getAsString(), is(giro.getUUID()));
        assertThat(target.get("defaulted").getAsBoolean(), is(true));

        assertThat(giro.getTransactions().size(), is(1));
        assertThat(file.isDirty(), is(false));
    }

    @Test
    public void testDuplicateOfExistingTransactionIsNamedAndNotImported()
    {
        var report = CsvImportSession.preview(file, host, body(STATEMENT));

        var duplicate = item(report, 2);
        assertThat(duplicate.get("type").getAsString(), is("deposit"));
        assertThat(duplicate.get("status").getAsString(), is("warning"));
        assertThat(checks(duplicate), hasItem("duplicate"));
        assertThat(duplicate.getAsJsonArray("potentialDuplicateOf").get(0).getAsString(),
                        is(existingDeposit.getUUID()));
        assertThat(duplicate.get("import").getAsBoolean(), is(false));
        assertThat(duplicate.get("reason").getAsString(), is("warning-not-accepted"));

        var removal = item(report, 3);
        assertThat(removal.get("type").getAsString(), is("removal"));
        assertThat(removal.get("status").getAsString(), is("ok"));
        assertThat(removal.get("import").getAsBoolean(), is(true));
        assertThat(removal.getAsJsonObject("cashAccount").get("uuid").getAsString(), is(giro.getUUID()));
    }

    @Test
    public void testBalancesAreProjectedFromTheImportedCashFlow()
    {
        var report = CsvImportSession.preview(file, host, body(STATEMENT));

        var balance = report.getAsJsonArray("balances").get(0).getAsJsonObject();
        assertThat(balance.get("date").getAsString(), is("2024-01-31"));
        assertThat(value(balance.get("balanceBefore")), is(new BigDecimal("100")));
        assertThat(value(balance.get("importedCashFlow")), is(new BigDecimal("-28.5")));
        assertThat(value(balance.get("balanceAfter")), is(new BigDecimal("71.5")));
    }

    @Test
    public void testReconciliationAgainstExpectedBalance()
    {
        var body = body(STATEMENT);
        var expected = new JsonArray();
        var match = new JsonObject();
        match.addProperty("date", "2024-01-31");
        match.addProperty("balance", "71.50");
        expected.add(match);
        var mismatch = new JsonObject();
        mismatch.addProperty("cashAccount", giro.getUUID());
        mismatch.addProperty("date", "2024-01-31");
        mismatch.addProperty("balance", 171.50);
        expected.add(mismatch);
        body.add("expectedBalances", expected);

        var reconciliation = CsvImportSession.preview(file, host, body).getAsJsonArray("reconciliation");

        assertThat(reconciliation.get(0).getAsJsonObject().get("status").getAsString(), is("match"));

        // the bank says 171.50: the deposit flagged as duplicate is real
        var second = reconciliation.get(1).getAsJsonObject();
        assertThat(second.get("status").getAsString(), is("mismatch"));
        assertThat(value(second.get("difference")), is(new BigDecimal("100")));
    }

    @Test
    public void testCommitImportsAcceptedWarningsAndMarksDirty()
    {
        var body = body(STATEMENT);
        body.add("acceptWarnings", ints(2));

        var report = CsvImportSession.commit(file, host, body);

        assertThat(report.get("dryRun").getAsBoolean(), is(false));
        assertThat(report.getAsJsonObject("summary").get("import").getAsInt(), is(3));
        assertThat(giro.getTransactions().size(), is(4));
        assertThat(file.isDirty(), is(true));
        assertThat(host.afterImports().size(), is(1));

        // on commit the balance is read back from the file
        var balance = report.getAsJsonArray("balances").get(0).getAsJsonObject();
        assertThat(value(balance.get("balanceAfter")), is(new BigDecimal("171.5")));
        assertThat(report.has("consistencyIssues"), is(true));
    }

    @Test
    public void testCommitWithoutAcceptanceSkipsTheDuplicate()
    {
        CsvImportSession.commit(file, host, body(STATEMENT));

        assertThat(giro.getTransactions().size(), is(3));
        assertThat(CashBalances.balanceAt(giro, LocalDate.parse("2024-01-31")),
                        is(Values.Amount.factorize(71.5)));
    }

    @Test
    public void testExcludedLinesAreNotImported()
    {
        var body = body(STATEMENT);
        body.add("excludeLines", ints(3));

        var report = CsvImportSession.commit(file, host, body);

        assertThat(item(report, 3).get("reason").getAsString(), is("excluded"));
        assertThat(giro.getTransactions().size(), is(2));
    }

    @Test
    public void testRepeatedLineWithinTheFileIsFlagged()
    {
        var csv = """
                        date,value
                        2024-02-01,-9.99
                        2024-02-01,-9.99
                        """;

        var report = CsvImportSession.preview(file, host, body(csv));

        var first = item(report, 2);
        assertThat(checks(first), hasItem("duplicate-in-file"));
        assertThat(first.getAsJsonArray("repeatsLines").get(0).getAsInt(), is(3));
        assertThat(item(report, 3).getAsJsonArray("repeatsLines").get(0).getAsInt(), is(2));
        assertThat(report.getAsJsonObject("summary").get("import").getAsInt(), is(0));
    }

    @Test
    public void testParseErrorsCarryTheLineNumber()
    {
        var csv = """
                        date,value
                        2024-02-01,10
                        not-a-date,10
                        """;

        var report = CsvImportSession.preview(file, host, body(csv));

        var error = report.getAsJsonArray("parseErrors").get(0).getAsJsonObject();
        assertThat(error.get("line").getAsInt(), is(3));
        assertThat(report.getAsJsonObject("summary").get("items").getAsInt(), is(1));
    }

    @Test
    public void testTypeColumnAcceptsWireNamesEnumNamesAndCustomPatterns()
    {
        var csv = """
                        Buchungstag;Betrag;Art
                        03.02.2024;5,00;deposit
                        04.02.2024;6,00;INTEREST
                        05.02.2024;7,00;Gutschrift
                        """;

        var body = body(csv);
        body.addProperty("decimalSeparator", ",");
        var columns = new JsonArray();
        var date = new JsonObject();
        date.addProperty("header", "Buchungstag");
        date.addProperty("field", "date");
        date.addProperty("format", "dd.MM.yyyy");
        columns.add(date);
        var amount = new JsonObject();
        amount.addProperty("index", 1);
        amount.addProperty("field", "value");
        columns.add(amount);
        var type = new JsonObject();
        type.addProperty("header", "art");
        type.addProperty("field", "type");
        var patterns = new JsonObject();
        patterns.addProperty("deposit", "Gutschrift|deposit");
        type.add("format", patterns);
        columns.add(type);
        body.add("columns", columns);

        var report = CsvImportSession.preview(file, host, body);

        assertThat(report.getAsJsonObject("settings").get("delimiter").getAsString(), is(";"));
        assertThat(item(report, 2).get("type").getAsString(), is("deposit"));
        assertThat(item(report, 3).get("type").getAsString(), is("interest"));
        assertThat(item(report, 4).get("type").getAsString(), is("deposit"));
        assertThat(value(item(report, 4).get("amount")), is(new BigDecimal("7")));
    }

    @Test
    public void testRawBytesAreReadInTheGivenEncoding()
    {
        var csv = "date;value;note\n2024-03-01;12,50;Gebühr\n";
        var body = new JsonObject();
        body.addProperty("type", "cash-account-transactions");
        body.addProperty("csvBase64", Base64.getEncoder().encodeToString(csv.getBytes(Charset.forName("ISO-8859-1"))));
        body.addProperty("encoding", "ISO-8859-1");
        body.addProperty("decimalSeparator", ",");

        var report = CsvImportSession.preview(file, host, body);

        assertThat(item(report, 2).get("note").getAsString(), is("Gebühr"));
        assertThat(value(item(report, 2).get("amount")), is(new BigDecimal("12.5")));
    }

    @Test
    public void testMissingRequiredFieldIsAValidationErrorListingTheColumns()
    {
        var e = expectValidation(() -> CsvImportSession.preview(file, host, body("when,value\n2024-01-01,1\n")));

        var error = e.getErrors().get(0);
        assertThat(error.code(), is("required"));
        assertThat(error.message(), containsString("'date'"));
        assertThat(error.message(), containsString("\"when\""));
    }

    @Test
    public void testUnknownBodyFieldIsRejected()
    {
        var body = body(STATEMENT);
        body.addProperty("dryrun", true);

        var e = expectValidation(() -> CsvImportSession.preview(file, host, body));

        assertThat(e.getErrors().get(0).field(), is("dryrun"));
        assertThat(e.getErrors().get(0).code(), is("unknown-field"));
    }

    @Test
    public void testUnknownTypeAndUnknownFieldCodeAreRejected()
    {
        var body = body(STATEMENT);
        body.addProperty("type", "bank-statement");
        var e = expectValidation(() -> CsvImportSession.preview(file, host, body));
        assertThat(e.getErrors().get(0).message(), containsString("cash-account-transactions"));

        var body2 = body(STATEMENT);
        var columns = new JsonArray();
        var column = new JsonObject();
        column.addProperty("index", 2);
        column.addProperty("field", "memo");
        columns.add(column);
        body2.add("columns", columns);
        e = expectValidation(() -> CsvImportSession.preview(file, host, body2));
        assertThat(e.getErrors().get(0).field(), is("columns[0].field"));
    }

    @Test
    public void testAmbiguousTargetIsReportedPerItemWithCandidates()
    {
        var second = new AccountBuilder().addTo(client);
        second.setName("Savings");

        var report = CsvImportSession.preview(file, host, body(STATEMENT));

        var removal = item(report, 3);
        assertThat(removal.get("status").getAsString(), is("error"));
        assertThat(checks(removal), hasItem("target"));
        assertThat(removal.getAsJsonArray("messages").get(0).getAsJsonObject().get("message").getAsString(),
                        containsString(second.getUUID()));

        // naming the account resolves it
        var body = body(STATEMENT);
        var accounts = new JsonArray();
        accounts.add(second.getUUID());
        body.add("cashAccounts", accounts);
        report = CsvImportSession.preview(file, host, body);
        assertThat(item(report, 3).get("status").getAsString(), is("ok"));
        assertThat(item(report, 3).getAsJsonObject("cashAccount").get("uuid").getAsString(), is(second.getUUID()));
    }

    @Test
    public void testDividendCreatesInstrumentOnlyOnCommit()
    {
        var csv = """
                        date,value,type,isin,name
                        2024-04-02,12.34,dividends,DE0007164600,SAP SE
                        """;

        var preview = CsvImportSession.preview(file, host, body(csv));
        var instrument = item(preview, 2).getAsJsonObject("instrument");
        assertThat(instrument.get("new").getAsBoolean(), is(true));
        assertThat(instrument.has("uuid"), is(false));
        assertThat(client.getSecurities().isEmpty(), is(true));

        var report = CsvImportSession.commit(file, host, body(csv));
        assertThat(client.getSecurities().size(), is(1));
        assertThat(report.getAsJsonArray("createdInstruments").size(), is(1));
        assertThat(host.afterImports().get(0).newInstruments().size(), is(1));
    }

    @Test
    public void testBuyFromInvestmentAccountTransactions()
    {
        var security = new SecurityBuilder().addTo(client);
        security.setIsin("DE0007164600");
        var portfolio = new PortfolioBuilder(giro).addTo(client);

        var csv = """
                        date,value,shares,isin,type,fees
                        2024-05-02,1010.00,10,DE0007164600,buy,10.00
                        """;
        var body = body(csv);
        body.addProperty("type", "investment-account-transactions");

        var report = CsvImportSession.commit(file, host, body);

        var buy = item(report, 2);
        assertThat(buy.get("kind").getAsString(), is("buy-sell"));
        assertThat(buy.get("type").getAsString(), is("buy"));
        assertThat(value(buy.get("fees")), is(new BigDecimal("10")));
        assertThat(buy.getAsJsonObject("investmentAccount").get("uuid").getAsString(), is(portfolio.getUUID()));
        assertThat(portfolio.getTransactions().size(), is(1));
        assertThat(portfolio.getTransactions().get(0).getType(), is(PortfolioTransaction.Type.BUY));

        var balance = report.getAsJsonArray("balances").get(0).getAsJsonObject();
        assertThat(value(balance.get("importedCashFlow")), is(new BigDecimal("-1010")));
    }

    @Test
    public void testSavedConfigurationIsApplied()
    {
        var config = new CSVConfig();
        config.fromJSON((JSONObject) JSONValue.parse("""
                        {"label": "My Bank", "target": "account-transaction", "delimiter": ";",
                         "encoding": "UTF-8", "skipLines": 1, "isFirstLineHeader": true,
                         "columns": [{"label": "Valuta", "field": "date", "format": "dd.MM.yyyy"},
                                     {"label": "Betrag", "field": "value", "format": "0.000,00"},
                                     {"label": "Text"}]}
                        """));
        host.setCsvConfigurations(List.of(new CsvConfiguration(config, false)));

        var body = new JsonObject();
        body.addProperty("configuration", "My Bank");
        body.addProperty("csv", "Kontoauszug Januar\nValuta;Betrag;Text\n15.01.2024;-1.234,56;Miete\n");

        var report = CsvImportSession.preview(file, host, body);

        assertThat(report.get("type").getAsString(), is("cash-account-transactions"));
        assertThat(report.get("configuration").getAsString(), is("My Bank"));
        var rent = item(report, 3);
        assertThat(rent.get("type").getAsString(), is("removal"));
        assertThat(value(rent.get("amount")), is(new BigDecimal("1234.56")));

        var listed = CsvImportHandler.configurations(host).getAsJsonObject().getAsJsonArray("items").get(0)
                        .getAsJsonObject();
        assertThat(listed.get("type").getAsString(), is("cash-account-transactions"));
        assertThat(listed.get("builtIn").getAsBoolean(), is(false));
        assertThat(listed.getAsJsonArray("columns").get(0).getAsJsonObject().get("header").getAsString(),
                        is("Valuta"));
    }

    @Test
    public void testUnknownConfigurationListsTheAvailableOnes()
    {
        var body = new JsonObject();
        body.addProperty("configuration", "Nope");
        body.addProperty("csv", STATEMENT);

        var e = expectValidation(() -> CsvImportSession.preview(file, host, body));
        assertThat(e.getErrors().get(0).field(), is("configuration"));
    }

    @Test
    public void testInstrumentPrices()
    {
        Security security = new SecurityBuilder().addPrice("2024-01-02", Values.Quote.factorize(10)).addTo(client);

        var body = new JsonObject();
        body.addProperty("type", "instrument-prices");
        body.addProperty("instrument", security.getUUID());
        body.addProperty("csv", "date,quote\n2024-01-02,11\n2024-01-03,12\n");

        var preview = CsvImportSession.preview(file, host, body);
        var summary = preview.getAsJsonObject("summary");
        assertThat(summary.get("new").getAsInt(), is(1));
        assertThat(summary.get("changed").getAsInt(), is(1));
        assertThat(security.getPrices().size(), is(1));

        CsvImportSession.commit(file, host, body);
        assertThat(security.getPrices().size(), is(2));
        assertThat(security.getSecurityPrice(LocalDate.parse("2024-01-02")).getValue(),
                        is(Values.Quote.factorize(11)));
    }

    @Test
    public void testInstrumentPricesRequireTheInstrument()
    {
        var body = new JsonObject();
        body.addProperty("type", "instrument-prices");
        body.addProperty("csv", "date,quote\n2024-01-02,11\n");

        var e = expectValidation(() -> CsvImportSession.preview(file, host, body));
        assertThat(e.getErrors().get(0).field(), is("instrument"));
    }

    @Test
    public void testItemDetailIssuesListsOnlyWhatNeedsAttention()
    {
        var body = body(STATEMENT);
        body.addProperty("itemDetail", "issues");

        var items = CsvImportSession.preview(file, host, body).getAsJsonArray("items");

        assertThat(items.size(), is(1));
        assertThat(items.get(0).getAsJsonObject().get("line").getAsInt(), is(2));
    }

    @Test
    public void testTypesDescribeFieldsAndValues()
    {
        var types = CsvImportHandler.types(client).getAsJsonObject().getAsJsonArray("items");

        JsonObject cash = null;
        for (var t : types)
            if ("cash-account-transactions".equals(t.getAsJsonObject().get("type").getAsString()))
                cash = t.getAsJsonObject();
        assertThat(cash == null, is(false));

        JsonObject typeField = null;
        for (var f : cash.getAsJsonArray("fields"))
            if ("type".equals(f.getAsJsonObject().get("code").getAsString()))
                typeField = f.getAsJsonObject();
        assertThat(typeField.get("kind").getAsString(), is("type"));
        assertThat(typeField.getAsJsonArray("values").toString(), containsString("\"interest-charge\""));
    }

    @Test
    public void testMalformedCsvIsAValidationError()
    {
        var e = expectValidation(() -> CsvImportSession.preview(file, host, body("date,value\n\"2024-01-01,1\n")));
        assertThat(e.getErrors().get(0).field(), is("csv"));
    }

    @Test
    public void testRequestParserCollectsAllViolations()
    {
        var body = JsonParser.parseString("""
                        {"csv": "x", "csvBase64": "eA==", "delimiter": "::", "skipLines": -1,
                         "excludeLines": ["a"], "itemDetail": "most"}
                        """).getAsJsonObject();

        var e = expectValidation(() -> CsvImportRequest.parse(body));

        var fields = e.getErrors().stream().map(ApiException.FieldError::field).toList();
        assertThat(fields, hasItem("type"));
        assertThat(fields, hasItem("csv"));
        assertThat(fields, hasItem("delimiter"));
        assertThat(fields, hasItem("skipLines"));
        assertThat(fields, hasItem("excludeLines"));
        assertThat(fields, hasItem("itemDetail"));
    }

    @Test
    public void testDecimalCommaIsDetectedSoAmountsAreNotSilentlyWrong()
    {
        var csv = """
                        date;value
                        2024-06-03;-1.234,56
                        2024-06-04;1.000
                        """;

        var report = CsvImportSession.preview(file, host, body(csv));

        assertThat(report.getAsJsonArray("columns").get(1).getAsJsonObject().get("format").getAsString(),
                        is("0.000,00"));
        assertThat(value(item(report, 2).get("amount")), is(new BigDecimal("1234.56")));
        assertThat(value(item(report, 3).get("amount")), is(new BigDecimal("1000")));
    }

    @Test
    public void testByteOrderMarkDoesNotHideTheFirstHeader()
    {
        var bytes = "\uFEFFdate,value\n2024-06-03,5.00\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var body = new JsonObject();
        body.addProperty("type", "cash-account-transactions");
        body.addProperty("csvBase64", Base64.getEncoder().encodeToString(bytes));

        var report = CsvImportSession.preview(file, host, body);

        assertThat(report.getAsJsonArray("columns").get(0).getAsJsonObject().get("field").getAsString(),
                        is("date"));
        assertThat(report.getAsJsonObject("summary").get("items").getAsInt(), is(1));
    }
}
