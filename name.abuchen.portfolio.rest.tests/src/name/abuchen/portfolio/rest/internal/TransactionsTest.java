package name.abuchen.portfolio.rest.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.eclipse.core.runtime.preferences.InstanceScope;

import org.junit.Before;
import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.rest.ApiRoutes;
import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.testsupport.FakeHost;

@SuppressWarnings("nls")
public class TransactionsTest
{
    private Client client;
    private Account giro;
    private Security security;
    private Portfolio portfolio;

    @Before
    public void setUp()
    {
        client = new Client();
        security = new SecurityBuilder().addTo(client);
        giro = new AccountBuilder() //
                        .deposit_("2024-01-02", Values.Amount.factorize(1000)) //
                        .withdraw("2024-01-10", Values.Amount.factorize(50)) //
                        .withdraw("2024-01-10", Values.Amount.factorize(50)) //
                        .dividend("2024-02-15", Values.Amount.factorize(12), security) //
                        .addTo(client);
        portfolio = new PortfolioBuilder(giro)
                        .buy(security, "2024-01-20", Values.Share.factorize(2), Values.Amount.factorize(300))
                        .addTo(client);
    }

    private static BigDecimal value(JsonElement money)
    {
        return money.getAsJsonObject().get("value").getAsBigDecimal();
    }

    private static JsonObject items(JsonElement envelope, int index)
    {
        return envelope.getAsJsonObject().getAsJsonArray("items").get(index).getAsJsonObject();
    }

    @Test
    public void testListShowsEachBookingOnceWithItsCounterpart()
    {
        var list = TransactionsHandler.list(client, null, null, null, null, null);
        var items = list.getAsJsonObject().getAsJsonArray("items");

        // deposit, 2 removals, buy (once, from the investment account), dividend
        assertThat(items.size(), is(5));

        var buy = items(list, 3);
        assertThat(buy.get("type").getAsString(), is("buy"));
        assertThat(buy.getAsJsonObject("investmentAccount").get("uuid").getAsString(), is(portfolio.getUUID()));
        assertThat(buy.get("shares").getAsBigDecimal(), is(new BigDecimal("2")));
        var counterpart = buy.getAsJsonObject("counterpart");
        assertThat(counterpart.getAsJsonObject("cashAccount").get("uuid").getAsString(), is(giro.getUUID()));
    }

    @Test
    public void testFilters()
    {
        var removals = TransactionsHandler.list(client, "2024-01-05", "2024-01-31", null, giro.getUUID(),
                        "removal,buy");
        var items = removals.getAsJsonObject().getAsJsonArray("items");

        // the account side of the buy is listed, as the account is filtered
        assertThat(items.size(), is(3));
        assertThat(items.get(2).getAsJsonObject().get("type").getAsString(), is("buy"));

        var forInstrument = TransactionsHandler.list(client, null, null, security.getUUID(), null, null);
        assertThat(forInstrument.getAsJsonObject().getAsJsonArray("items").size(), is(2));
    }

    @Test
    public void testInvalidFiltersAreReportedAtOnce()
    {
        try
        {
            TransactionsHandler.list(client, "yesterday", null, "nope", "nope", "payday");
            fail("expected ApiException");
        }
        catch (ApiException e)
        {
            assertThat(e.getStatus(), is(400));
            assertThat(e.getErrors().size(), is(4));
        }
    }

    @Test
    public void testStatementReconciles()
    {
        var statement = TransactionsHandler.statement(client, giro.getUUID(), "2024-01-05", "2024-01-31")
                        .getAsJsonObject();

        assertThat(value(statement.get("openingBalance")), is(new BigDecimal("1000")));
        assertThat(value(statement.get("closingBalance")), is(new BigDecimal("600")));
        assertThat(value(statement.get("totalDebits")), is(new BigDecimal("400")));

        var items = statement.getAsJsonArray("items");
        assertThat(items.size(), is(3));
        assertThat(value(items.get(0).getAsJsonObject().get("cashFlow")), is(new BigDecimal("-50")));
        assertThat(value(items.get(1).getAsJsonObject().get("balance")), is(new BigDecimal("900")));
        assertThat(items.get(2).getAsJsonObject().get("type").getAsString(), is("buy"));

        // the two identical withdrawals on one day point at each other
        var first = items.get(0).getAsJsonObject();
        var second = items.get(1).getAsJsonObject();
        assertThat(first.getAsJsonArray("potentialDuplicateOf").get(0).getAsString(),
                        is(second.get("uuid").getAsString()));
        assertThat(items.get(2).getAsJsonObject().has("potentialDuplicateOf"), is(false));
    }

    @Test
    public void testStatementWithoutFromStartsAtTheFirstBooking()
    {
        var statement = TransactionsHandler.statement(client, giro.getUUID(), null, "2024-12-31").getAsJsonObject();

        assertThat(statement.get("from").getAsString(), is("2024-01-02"));
        assertThat(value(statement.get("openingBalance")), is(BigDecimal.ZERO));
        assertThat(value(statement.get("closingBalance")), is(new BigDecimal("612")));
    }

    @Test
    public void testSaveSavesOnlyADirtyFile()
    {
        var file = new FakeHost.SavableFile("/tmp/x.xml", client);
        var access = new FileAccessRegistry.FileAccess("/tmp/x.xml", "id", null, true);

        FilesHandler.save(access, file);
        assertThat(file.saves(), is(0));

        file.setDirty(true);
        var response = FilesHandler.save(access, file);
        assertThat(file.saves(), is(1));
        assertThat(new String(response.body()).contains("\"dirty\":false"), is(true));
    }

    @Test
    public void testCsvImportRoutesAcceptLargerBodiesThanTheRest()
    {
        var host = new FakeHost(List.of());
        var router = ApiRoutes.create(
                        new FileAccessRegistry(InstanceScope.INSTANCE.getNode("rest-test-" + UUID.randomUUID())), host,
                        null);

        Map<String, Integer> limits = router.maxBodyBytes();
        assertThat(limits.get("POST /v1/files/{file}/csv-import") > Router.DEFAULT_MAX_BODY_BYTES, is(true));
        assertThat(limits.get("POST /v1/files/{file}/csv-import/preview") > Router.DEFAULT_MAX_BODY_BYTES,
                        is(true));
        assertThat(limits.get("POST /v1/auth/requests"), is(Router.DEFAULT_MAX_BODY_BYTES));
    }
}
