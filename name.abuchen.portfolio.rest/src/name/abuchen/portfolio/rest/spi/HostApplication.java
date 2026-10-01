package name.abuchen.portfolio.rest.spi;

import java.util.List;
import java.util.concurrent.Callable;

import name.abuchen.portfolio.model.Security;

/**
 * Services the hosting application provides to the REST plugin. Implemented
 * by the UI plugin; the REST plugin must not depend on UI bundles.
 */
public interface HostApplication
{
    List<OpenFile> listOpenFiles();

    /** runs the callable on the UI thread and returns its result */
    <T> T syncExec(Callable<T> callable) throws Exception;

    /**
     * true if the user is in the middle of an uncommitted edit — an
     * application-modal dialog is open or an in-place cell editor is active;
     * must be called on the UI thread
     */
    boolean isUserEditing();

    /**
     * Asks the user to approve an API access request. Called on an HTTP worker
     * thread and must not block; the decision is reported asynchronously
     * through the request object.
     */
    void requestApiAccessApproval(ApiAccessRequest request);

    /**
     * The CSV import configurations known to the application - the built-in
     * ones and those the user saved in the CSV import wizard - so that an API
     * client can reuse a column mapping the user set up once. Must be called on
     * the UI thread.
     */
    default List<CsvConfiguration> listCsvConfigurations()
    {
        return List.of();
    }

    /**
     * Called on the UI thread after the API imported data into a file. Lets the
     * host run the follow-ups of its own import wizard that need no user
     * interaction, e.g. fetching prices for newly created instruments. Must not
     * block and must not open dialogs.
     */
    default void afterImport(OpenFile file, List<Security> newInstruments)
    {
    }
}
