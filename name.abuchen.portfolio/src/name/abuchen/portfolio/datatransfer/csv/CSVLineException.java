package name.abuchen.portfolio.datatransfer.csv;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.Arrays;

import name.abuchen.portfolio.Messages;

/**
 * A CSV line that could not be converted into an item. Keeps the line number
 * and the reason separately so that callers other than the import wizard (e.g.
 * the REST API) can report them without parsing the localized message.
 */
public class CSVLineException extends IOException
{
    private static final long serialVersionUID = 1L;

    private final int lineNo;
    private final String reason;

    public CSVLineException(int lineNo, String reason, String[] values, Throwable cause)
    {
        super(MessageFormat.format(Messages.CSVLineXwithMsgY, lineNo, reason, Arrays.toString(values)), cause);
        this.lineNo = lineNo;
        this.reason = reason;
    }

    /** the 1-based line number in the CSV file */
    public int getLineNo()
    {
        return lineNo;
    }

    /** the reason without line number and data */
    public String getReason()
    {
        return reason;
    }
}
