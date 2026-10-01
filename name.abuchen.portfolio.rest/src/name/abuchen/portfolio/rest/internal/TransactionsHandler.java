package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.model.TransactionOwner;
import name.abuchen.portfolio.model.TransactionPair;
import name.abuchen.portfolio.money.Money;

/**
 * Lists transactions and renders the statement of a cash account - the view a
 * client needs to reconcile the file against a bank statement.
 */
@SuppressWarnings("nls")
public final class TransactionsHandler
{
    private TransactionsHandler()
    {
    }

    /**
     * All transactions of the file, optionally filtered. Without an
     * {@code account} filter, a booking that has two sides (a buy, a sell, a
     * transfer) is listed once, from the investment account resp. the sending
     * side, and names the other side as {@code counterpart}. With an
     * {@code account} filter, every transaction of that account is listed.
     */
    public static JsonElement list(Client client, String from, String to, String instrument, String account,
                    String type)
    {
        var errors = new ArrayList<ApiException.FieldError>();

        var fromDate = from != null ? CalcParams.date("from", from, errors) : null;
        var toDate = to != null ? CalcParams.date("to", to, errors) : null;
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate))
            errors.add(new ApiException.FieldError("to", "invalid-range", "to must not be before from"));

        Security security = null;
        if (instrument != null)
        {
            security = client.getSecurities().stream().filter(s -> s.getUUID().equals(instrument)).findFirst()
                            .orElse(null);
            if (security == null)
                errors.add(new ApiException.FieldError("instrument", "invalid-value",
                                "no instrument with uuid " + instrument));
        }

        TransactionOwner<?> owner = null;
        if (account != null)
        {
            owner = findOwner(client, account);
            if (owner == null)
                errors.add(new ApiException.FieldError("account", "invalid-value",
                                "no cash account or investment account with uuid " + account));
        }

        var types = types(type, errors);

        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        List<TransactionPair<?>> pairs;
        if (owner != null)
            pairs = pairsOf(owner);
        else
            pairs = client.getAllTransactions();

        var selected = security;
        var items = new JsonArray();
        pairs.stream() //
                        .filter(p -> inRange(p.getTransaction(), fromDate, toDate)) //
                        .filter(p -> selected == null || selected.equals(p.getTransaction().getSecurity())) //
                        .filter(p -> types == null || types.contains(wireType(p.getTransaction()))) //
                        .sorted(TransactionPair.BY_DATE) //
                        .forEach(p -> items.add(TransactionJson.toJson(p.getOwner(), p.getTransaction())));

        return EntityJson.envelope(items);
    }

    /**
     * The statement of one cash account between two dates (both inclusive):
     * the opening balance, every booking with its signed cash flow and the
     * running balance, and the closing balance. The terms reconcile:
     * {@code openingBalance + sum(cashFlow) = closingBalance}, which is why
     * {@code cashFlow} - unlike {@code amount} - is signed (ADR 0003).
     */
    public static JsonElement statement(Client client, String uuid, String from, String to)
    {
        var account = Entities.byUuid(client.getAccounts(), Account::getUUID, uuid);

        var errors = new ArrayList<ApiException.FieldError>();
        var fromDate = from != null ? CalcParams.date("from", from, errors) : null;
        var toDate = to != null ? CalcParams.date("to", to, errors) : LocalDate.now();
        if (fromDate != null && toDate != null && toDate.isBefore(fromDate))
            errors.add(new ApiException.FieldError("to", "invalid-range", "to must not be before from"));
        if (!errors.isEmpty())
            throw ApiException.badRequest(errors);

        var transactions = account.getTransactions().stream() //
                        .sorted(Transaction.BY_DATE) //
                        .toList();

        if (fromDate == null)
            fromDate = transactions.isEmpty() ? toDate
                            : transactions.get(0).getDateTime().toLocalDate();

        var currency = account.getCurrencyCode();
        var opening = CashBalances.balanceAt(account, fromDate.minusDays(1));
        var duplicates = duplicateCandidates(transactions);

        var lines = new JsonArray();
        long balance = opening;
        long credits = 0;
        long debits = 0;
        for (var transaction : transactions)
        {
            if (!inRange(transaction, fromDate, toDate))
                continue;

            var flow = CashBalances.signedAmount(transaction);
            balance += flow;
            if (flow >= 0)
                credits += flow;
            else
                debits -= flow;

            var line = TransactionJson.toJson(account, transaction);
            line.add("cashFlow", EntityJson.toJson(Money.of(currency, flow)));
            line.add("balance", EntityJson.toJson(Money.of(currency, balance)));

            var others = duplicates.get(transaction);
            if (others != null)
            {
                var array = new JsonArray();
                others.forEach(other -> array.add(other.getUUID()));
                line.add("potentialDuplicateOf", array);
            }

            lines.add(line);
        }

        var json = new JsonObject();
        json.add("cashAccount", EntityJson.reference(account));
        json.addProperty("from", fromDate.toString());
        json.addProperty("to", toDate.toString());
        json.add("openingBalance", EntityJson.toJson(Money.of(currency, opening)));
        json.add("closingBalance", EntityJson.toJson(Money.of(currency, balance)));
        json.add("totalCredits", EntityJson.toJson(Money.of(currency, credits)));
        json.add("totalDebits", EntityJson.toJson(Money.of(currency, debits)));
        json.add("items", lines);
        return json;
    }

    /**
     * Groups the transactions of one account that the import would consider
     * potential duplicates of each other: same day, same currency, amount,
     * shares and instrument, and equivalent types - the criteria of
     * {@code DetectDuplicatesAction}.
     */
    private static Map<AccountTransaction, List<AccountTransaction>> duplicateCandidates(
                    List<AccountTransaction> transactions)
    {
        var groups = new HashMap<List<Object>, List<AccountTransaction>>();
        for (var transaction : transactions)
            groups.computeIfAbsent(duplicateKey(transaction), k -> new ArrayList<>()).add(transaction);

        var result = new HashMap<AccountTransaction, List<AccountTransaction>>();
        for (var group : groups.values())
        {
            if (group.size() < 2)
                continue;
            for (var transaction : group)
                result.put(transaction, group.stream().filter(t -> t != transaction).toList());
        }
        return result;
    }

    /* package */ static List<Object> duplicateKey(AccountTransaction transaction)
    {
        return Arrays.asList(duplicateClass(transaction.getType()), transaction.getDateTime().toLocalDate(),
                        transaction.getCurrencyCode(), transaction.getAmount(), transaction.getShares(),
                        transaction.getSecurity() != null ? transaction.getSecurity().getUUID() : null);
    }

    /** the class of types DetectDuplicatesAction treats as equivalent */
    /* package */ static Object duplicateClass(AccountTransaction.Type type)
    {
        return switch (type)
        {
            case DEPOSIT, TRANSFER_IN -> AccountTransaction.Type.DEPOSIT;
            case REMOVAL, TRANSFER_OUT -> AccountTransaction.Type.REMOVAL;
            default -> type;
        };
    }

    /* package */ static Object duplicateClass(PortfolioTransaction.Type type)
    {
        return switch (type)
        {
            case BUY, DELIVERY_INBOUND -> PortfolioTransaction.Type.BUY;
            case SELL, DELIVERY_OUTBOUND -> PortfolioTransaction.Type.SELL;
            default -> type;
        };
    }

    private static Set<String> types(String value, List<ApiException.FieldError> errors)
    {
        if (value == null)
            return null; // NOSONAR - null means: no filter

        var known = new LinkedHashSet<String>();
        EnumSet.allOf(AccountTransaction.Type.class).forEach(t -> known.add(TransactionJson.wireType(t)));
        EnumSet.allOf(PortfolioTransaction.Type.class).forEach(t -> known.add(TransactionJson.wireType(t)));

        var types = new HashSet<String>();
        for (var t : value.split(","))
        {
            var name = t.trim();
            if (known.contains(name))
                types.add(name);
            else
                errors.add(new ApiException.FieldError("type", "invalid-value",
                                "unknown transaction type '" + name + "'; known types: " + String.join(", ", known)));
        }
        return types;
    }

    private static String wireType(Transaction transaction)
    {
        if (transaction instanceof AccountTransaction at)
            return TransactionJson.wireType(at.getType());
        if (transaction instanceof PortfolioTransaction pt)
            return TransactionJson.wireType(pt.getType());
        return null;
    }

    private static boolean inRange(Transaction transaction, LocalDate from, LocalDate to)
    {
        var date = transaction.getDateTime().toLocalDate();
        return (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
    }

    private static TransactionOwner<?> findOwner(Client client, String uuid)
    {
        return Stream.concat(client.getAccounts().stream(), client.getPortfolios().stream()) //
                        .filter(o -> Objects.equals(uuidOf(o), uuid)) //
                        .<TransactionOwner<?>>map(o -> (TransactionOwner<?>) o) //
                        .findFirst().orElse(null);
    }

    private static String uuidOf(Object owner)
    {
        if (owner instanceof Account a)
            return a.getUUID();
        if (owner instanceof Portfolio p)
            return p.getUUID();
        return null;
    }

    private static <T extends Transaction> List<TransactionPair<?>> pairsOf(TransactionOwner<T> owner)
    {
        return owner.getTransactions().stream().<TransactionPair<?>>map(t -> new TransactionPair<>(owner, t))
                        .toList();
    }
}
