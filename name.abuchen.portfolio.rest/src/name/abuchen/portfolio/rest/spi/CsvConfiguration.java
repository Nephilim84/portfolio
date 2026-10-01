package name.abuchen.portfolio.rest.spi;

import name.abuchen.portfolio.datatransfer.csv.CSVConfig;

/**
 * A saved CSV import configuration (column mapping, delimiter, encoding, ...)
 * as created in the CSV import wizard.
 *
 * @param builtIn
 *            true if the configuration ships with the application, false if
 *            the user saved it
 */
public record CsvConfiguration(CSVConfig config, boolean builtIn)
{
}
