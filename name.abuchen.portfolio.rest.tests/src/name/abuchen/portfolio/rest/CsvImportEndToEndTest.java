package name.abuchen.portfolio.rest;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

/**
 * The workflow the CSV import is designed for, over HTTP: an agent receives a
 * bank statement, previews the import, reconciles it against the statement's
 * closing balance, resolves the findings, commits, verifies with the account
 * statement, and saves.
 */
@SuppressWarnings("nls")
public class CsvImportEndToEndTest
{
    private static final String TOKEN = "e2e-token";

    private IEclipsePreferences node;
    private RestApiServer server;
    private HttpClient http;
    private Client client;
    private Account giro;
    private FakeHost.SavableFile file;

    @Before
    public void setUp() throws Exception
    {
        client = new Client();
        giro = new AccountBuilder() //
                        .deposit_("2024-01-02", Values.Amount.factorize(2500)) //
                        .addTo(client);
        giro.setName("Giro");

        file = new FakeHost.SavableFile("/tmp/e2e.portfolio", client);
        var host = new FakeHost(List.of(file));

        node = InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID());
        var registry = new FileAccessRegistry(node);
        registry.setEnabled(file.getPath(), true);
        registry.setAlias(file.getPath(), "main");

        server = new RestApiServer(0, TOKEN::equals, ApiRoutes.create(registry, host,
                        new PairingService(new ClientStore(Path.of("target", "e2e-client-store")), host)));
        server.start();
        http = HttpClient.newHttpClient();
    }

    @After
    public void tearDown() throws Exception
    {
        server.stop();
        node.removeNode();
    }

    private JsonObject call(String method, String path, JsonObject body, int expectedStatus) throws Exception
    {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getPort() + path))
                        .header("Authorization", "Bearer " + TOKEN)
                        .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body(), response.statusCode(), is(expectedStatus));
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static BigDecimal value(JsonObject json, String key)
    {
        return json.getAsJsonObject(key).get("value").getAsBigDecimal();
    }

    @Test
    public void testReconcileABankStatement() throws Exception
    {
        // the statement repeats the salary already booked, and its closing
        // balance is 2500 + 2500 - 61.20 - 14.99 = 4923.81
        var csv = """
                        Date,Amount,Text
                        2024-01-02,2500.00,Salary
                        2024-01-05,-61.20,Groceries
                        2024-01-20,-14.99,Streaming
                        2024-02-01,2500.00,Salary
                        """;

        // 1. what can be imported, and how
        var types = call("GET", "/v1/files/main/csv-import/types", null, 200);
        assertThat(types.toString(), containsString("cash-account-transactions"));

        // 2. preview: "Amount" and "Text" are no field codes, so map them
        var body = new JsonObject();
        body.addProperty("type", "cash-account-transactions");
        body.addProperty("csv", csv);
        var columns = new JsonArray();
        columns.add(JsonParser.parseString("{\"header\": \"Amount\", \"field\": \"value\"}"));
        columns.add(JsonParser.parseString("{\"header\": \"Text\", \"field\": \"note\"}"));
        body.add("columns", columns);
        var accounts = new JsonArray();
        accounts.add(giro.getUUID());
        body.add("cashAccounts", accounts);
        body.add("expectedBalances",
                        JsonParser.parseString("[{\"date\": \"2024-02-01\", \"balance\": 4923.81}]"));

        var preview = call("POST", "/v1/files/main/csv-import/preview", body, 200);
        assertThat(preview.get("dryRun").getAsBoolean(), is(true));
        assertThat(preview.getAsJsonObject("summary").get("duplicates").getAsInt(), is(1));

        // the duplicate is the salary of January, which the file already has:
        // without it the balance matches the statement
        var reconciliation = preview.getAsJsonArray("reconciliation").get(0).getAsJsonObject();
        assertThat(reconciliation.get("status").getAsString(), is("match"));

        var duplicate = preview.getAsJsonArray("items").get(0).getAsJsonObject();
        assertThat(duplicate.get("line").getAsInt(), is(2));
        assertThat(duplicate.get("import").getAsBoolean(), is(false));
        assertThat(duplicate.getAsJsonArray("potentialDuplicateOf").get(0).getAsString(),
                        is(giro.getTransactions().get(0).getUUID()));

        // nothing changed yet
        assertThat(call("GET", "/v1/files", null, 200).getAsJsonArray("items").get(0).getAsJsonObject()
                        .get("dirty").getAsBoolean(), is(false));

        // 3. commit the same body
        var report = call("POST", "/v1/files/main/csv-import", body, 200);
        assertThat(report.getAsJsonObject("summary").get("import").getAsInt(), is(3));
        assertThat(report.getAsJsonArray("reconciliation").get(0).getAsJsonObject().get("status").getAsString(),
                        is("match"));

        // 4. verify with the statement of the account
        var statement = call("GET", "/v1/files/main/cash-accounts/" + giro.getUUID()
                        + "/statement?from=2024-01-01&to=2024-02-01", null, 200);
        assertThat(value(statement, "openingBalance"), is(BigDecimal.ZERO));
        assertThat(value(statement, "closingBalance"), is(new BigDecimal("4923.81")));
        assertThat(statement.getAsJsonArray("items").size(), is(4));

        var listed = call("GET", "/v1/files/main/transactions?account=" + giro.getUUID() + "&type=removal", null,
                        200);
        assertThat(listed.getAsJsonArray("items").size(), is(2));

        // 5. save
        var saved = call("POST", "/v1/files/main/save", null, 200);
        assertThat(saved.get("dirty").getAsBoolean(), is(false));
        assertThat(file.saves(), is(1));
    }

    @Test
    public void testValidationProblemOverHttp() throws Exception
    {
        var body = new JsonObject();
        body.addProperty("type", "cash-account-transactions");
        body.addProperty("csv", "Amount\n1\n");

        var problem = call("POST", "/v1/files/main/csv-import/preview", body, 422);

        assertThat(problem.get("type").getAsString(), containsString("problems/validation"));
        assertThat(problem.getAsJsonArray("errors").toString(), containsString("'date'"));
    }

    @Test
    public void testUnknownQueryParameterOnTransactionsIs400() throws Exception
    {
        var problem = call("GET", "/v1/files/main/transactions?since=2024-01-01", null, 400);
        assertThat(problem.toString(), containsString("unknown-parameter"));
    }
}
