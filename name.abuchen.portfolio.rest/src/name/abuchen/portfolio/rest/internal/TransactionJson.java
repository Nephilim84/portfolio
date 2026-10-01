package name.abuchen.portfolio.rest.internal;

import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.Transaction.Unit;
import name.abuchen.portfolio.model.TransactionOwner;
import name.abuchen.portfolio.money.Values;

/**
 * Maps transactions to the wire format. Monetary fields are magnitudes, as the
 * model stores them (ADR 0003): the direction of a transaction is its
 * {@code type}, never the sign of {@code amount}.
 */
@SuppressWarnings("nls")
public final class TransactionJson
{
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private TransactionJson()
    {
    }

    /**
     * The wire name of a transaction type: the model's enum name in
     * lower-kebab, e.g. {@code DELIVERY_INBOUND} becomes
     * {@code delivery-inbound}.
     */
    public static String wireType(Enum<?> type)
    {
        return type.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    public static JsonObject toJson(TransactionOwner<?> owner, Transaction transaction)
    {
        var json = new JsonObject();
        json.addProperty("uuid", transaction.getUUID());
        json.addProperty("date", DATE_TIME.format(transaction.getDateTime()));

        if (transaction instanceof AccountTransaction at)
            json.addProperty("type", wireType(at.getType()));
        else if (transaction instanceof PortfolioTransaction pt)
            json.addProperty("type", wireType(pt.getType()));

        addOwner(json, owner);

        json.add("amount", EntityJson.toJson(transaction.getMonetaryAmount()));

        if (transaction.getSecurity() != null)
        {
            json.add("instrument", EntityJson.reference(transaction.getSecurity()));
            json.add("shares", EntityJson.decimal(transaction.getShares(), Values.Share.precision()));
        }

        addUnits(json, transaction);

        if (transaction instanceof AccountTransaction at && at.getExDate() != null)
            json.addProperty("exDate", DATE_TIME.format(at.getExDate()));

        if (transaction.getNote() != null && !transaction.getNote().isBlank())
            json.addProperty("note", transaction.getNote());
        if (transaction.getSource() != null && !transaction.getSource().isBlank())
            json.addProperty("source", transaction.getSource());

        var crossEntry = transaction.getCrossEntry();
        if (crossEntry != null)
        {
            var cross = crossEntry.getCrossTransaction(transaction);
            var crossOwner = crossEntry.getCrossOwner(transaction);
            if (cross != null && crossOwner != null)
            {
                var counterpart = new JsonObject();
                counterpart.addProperty("uuid", cross.getUUID());
                if (cross instanceof AccountTransaction at)
                    counterpart.addProperty("type", wireType(at.getType()));
                else if (cross instanceof PortfolioTransaction pt)
                    counterpart.addProperty("type", wireType(pt.getType()));
                addOwner(counterpart, crossOwner);
                json.add("counterpart", counterpart);
            }
        }

        return json;
    }

    private static void addOwner(JsonObject json, TransactionOwner<?> owner)
    {
        if (owner instanceof Account account)
            json.add("cashAccount", EntityJson.reference(account));
        else if (owner instanceof Portfolio portfolio)
            json.add("investmentAccount", EntityJson.reference(portfolio));
    }

    private static void addUnits(JsonObject json, Transaction transaction)
    {
        if (transaction.getUnit(Unit.Type.FEE).isPresent())
            json.add("fees", EntityJson.toJson(transaction.getUnitSum(Unit.Type.FEE)));
        if (transaction.getUnit(Unit.Type.TAX).isPresent())
            json.add("taxes", EntityJson.toJson(transaction.getUnitSum(Unit.Type.TAX)));

        transaction.getUnit(Unit.Type.GROSS_VALUE).ifPresent(unit -> {
            var gross = new JsonObject();
            gross.add("amount", EntityJson.toJson(unit.getAmount()));
            if (unit.getForex() != null)
                gross.add("forex", EntityJson.toJson(unit.getForex()));
            if (unit.getExchangeRate() != null)
                gross.add("exchangeRate", EntityJson.decimal(unit.getExchangeRate()));
            json.add("grossValue", gross);
        });
    }
}
