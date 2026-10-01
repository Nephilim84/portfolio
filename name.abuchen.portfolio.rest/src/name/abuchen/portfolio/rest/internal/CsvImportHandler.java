package name.abuchen.portfolio.rest.internal;

import java.util.Arrays;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import name.abuchen.portfolio.datatransfer.csv.CSVImporter;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.AmountField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.DateField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.EnumField;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.FieldFormat;
import name.abuchen.portfolio.datatransfer.csv.CSVImporter.ISINField;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.rest.internal.CsvImportSession.ImportType;
import name.abuchen.portfolio.rest.spi.HostApplication;

/**
 * Describes what the CSV import accepts: the import types with their fields,
 * and the saved configurations of the CSV import wizard.
 */
@SuppressWarnings("nls")
public final class CsvImportHandler
{
    private CsvImportHandler()
    {
    }

    /**
     * The import types and, per type, the fields a column can be assigned to.
     * The fields depend on the file: an instrument import offers a field per
     * custom attribute.
     */
    public static JsonElement types(Client client)
    {
        var importer = new CSVImporter(client, null);

        var items = new JsonArray();
        for (var type : ImportType.values())
        {
            var extractor = importer.getExtractorByCode(type.extractorCode()).orElse(null);
            if (extractor == null)
                continue;

            var json = new JsonObject();
            json.addProperty("type", type.wireName());
            json.addProperty("label", extractor.getLabel());

            var fields = new JsonArray();
            for (var field : extractor.getFields())
            {
                var f = new JsonObject();
                f.addProperty("code", field.getCode());
                f.addProperty("label", field.getName());
                f.addProperty("required", !field.isOptional());

                if (field instanceof DateField)
                {
                    f.addProperty("kind", "date");
                    f.add("formats", formats(field.getAvailableFieldFormats()));
                }
                else if (field instanceof AmountField)
                {
                    f.addProperty("kind", "amount");
                    f.add("formats", formats(field.getAvailableFieldFormats()));
                }
                else if (field instanceof EnumField<?> enumField)
                {
                    f.addProperty("kind", "type");
                    var values = new JsonArray();
                    Arrays.stream(enumField.getEnumType().getEnumConstants())
                                    .forEach(c -> values.add(TransactionJson.wireType(c)));
                    f.add("values", values);
                }
                else if (field instanceof ISINField)
                {
                    f.addProperty("kind", "isin");
                }
                else
                {
                    f.addProperty("kind", "text");
                }

                fields.add(f);
            }
            json.add("fields", fields);
            items.add(json);
        }

        return EntityJson.envelope(items);
    }

    private static JsonArray formats(List<FieldFormat> formats)
    {
        var array = new JsonArray();
        formats.forEach(f -> array.add(f.getCode()));
        return array;
    }

    /**
     * The configurations saved in the CSV import wizard, which a client can
     * reference by label to reuse a column mapping the user set up once.
     */
    public static JsonElement configurations(HostApplication host)
    {
        var items = new JsonArray();
        for (var configuration : host.listCsvConfigurations())
        {
            var config = JsonParser.parseString(configuration.config().toJSON().toJSONString()).getAsJsonObject();

            var json = new JsonObject();
            json.addProperty("label", configuration.config().getLabel());
            var type = ImportType.byExtractorCode(configuration.config().getTarget());
            if (type != null)
                json.addProperty("type", type.wireName());
            json.addProperty("builtIn", configuration.builtIn());
            copy(config, json, "delimiter");
            copy(config, json, "encoding");
            copy(config, json, "skipLines");
            copy(config, json, "isFirstLineHeader", "firstLineHeader");

            var columns = new JsonArray();
            var configColumns = config.getAsJsonArray("columns");
            if (configColumns != null)
            {
                for (int ii = 0; ii < configColumns.size(); ii++)
                {
                    var c = configColumns.get(ii).getAsJsonObject();
                    var column = new JsonObject();
                    column.addProperty("index", ii);
                    copy(c, column, "label", "header");
                    copy(c, column, "field");
                    copy(c, column, "format");
                    columns.add(column);
                }
            }
            json.add("columns", columns);

            items.add(json);
        }
        return EntityJson.envelope(items);
    }

    private static void copy(JsonObject from, JsonObject to, String key)
    {
        copy(from, to, key, key);
    }

    private static void copy(JsonObject from, JsonObject to, String key, String as)
    {
        var value = from.get(key);
        if (value != null && !value.isJsonNull())
            to.add(as, value);
    }
}
