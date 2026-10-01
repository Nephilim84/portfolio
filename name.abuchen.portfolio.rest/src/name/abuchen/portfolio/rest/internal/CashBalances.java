package name.abuchen.portfolio.rest.internal;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import name.abuchen.portfolio.datatransfer.ImportAction;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.AccountTransferEntry;
import name.abuchen.portfolio.model.BuySellEntry;
import name.abuchen.portfolio.model.Portfolio;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.PortfolioTransferEntry;

/**
 * Cash account balances, computed from the transactions the way a bank
 * statement does: credits add, debits subtract, and a transaction counts on
 * the calendar day it is booked.
 */
public final class CashBalances
{
    /** a signed change of one cash account's balance on one day */
    public record CashFlow(Account account, LocalDate date, long amount)
    {
    }

    private CashBalances()
    {
    }

    /** the signed effect of a transaction on its account's balance */
    public static long signedAmount(AccountTransaction transaction)
    {
        return transaction.getType().isCredit() ? transaction.getAmount() : -transaction.getAmount();
    }

    /** the balance at the end of the given day, i.e. including that day */
    public static long balanceAt(Account account, LocalDate date)
    {
        long balance = 0;
        for (var transaction : account.getTransactions())
        {
            if (!transaction.getDateTime().toLocalDate().isAfter(date))
                balance += signedAmount(transaction);
        }
        return balance;
    }

    /**
     * Records the cash flows that inserting an import item would cause,
     * without changing anything. Run through {@code Item#apply}, it receives
     * exactly the accounts that the InsertAction would receive, and mirrors
     * its options: a buy or sell converted to a delivery moves no cash, and a
     * dividend that is withdrawn right away nets out to zero.
     */
    public static class CashFlowAction implements ImportAction
    {
        private final boolean convertBuySellToDelivery;
        private final boolean removeDividends;
        private final List<CashFlow> flows = new ArrayList<>();

        public CashFlowAction(boolean convertBuySellToDelivery, boolean removeDividends)
        {
            this.convertBuySellToDelivery = convertBuySellToDelivery;
            this.removeDividends = removeDividends;
        }

        public List<CashFlow> getFlows()
        {
            return flows;
        }

        private void add(Account account, AccountTransaction transaction, long amount)
        {
            flows.add(new CashFlow(account, transaction.getDateTime().toLocalDate(), amount));
        }

        @Override
        public Status process(AccountTransaction transaction, Account account)
        {
            if (removeDividends && transaction.getType() == AccountTransaction.Type.DIVIDENDS)
                return Status.OK_STATUS;

            add(account, transaction, signedAmount(transaction));
            return Status.OK_STATUS;
        }

        @Override
        public Status process(PortfolioTransaction transaction, Portfolio portfolio)
        {
            return Status.OK_STATUS;
        }

        @Override
        public Status process(BuySellEntry entry, Account account, Portfolio portfolio)
        {
            if (convertBuySellToDelivery)
                return Status.OK_STATUS;

            var transaction = entry.getAccountTransaction();
            add(account, transaction, signedAmount(transaction));
            return Status.OK_STATUS;
        }

        @Override
        public Status process(AccountTransferEntry entry, Account source, Account target)
        {
            add(source, entry.getSourceTransaction(), -entry.getSourceTransaction().getAmount());
            add(target, entry.getTargetTransaction(), entry.getTargetTransaction().getAmount());
            return Status.OK_STATUS;
        }

        @Override
        public Status process(PortfolioTransferEntry entry, Portfolio source, Portfolio target)
        {
            return Status.OK_STATUS;
        }
    }
}
