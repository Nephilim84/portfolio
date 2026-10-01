package name.abuchen.portfolio.rest.internal;

import java.io.IOException;
import java.text.MessageFormat;
import java.util.List;

import com.google.gson.JsonArray;

import name.abuchen.portfolio.PortfolioLog;
import name.abuchen.portfolio.rest.Messages;

import name.abuchen.portfolio.rest.FileAccessRegistry;
import name.abuchen.portfolio.rest.FileAccessRegistry.FileAccess;
import name.abuchen.portfolio.rest.spi.HostApplication;
import name.abuchen.portfolio.rest.spi.OpenFile;

public class FilesHandler
{
    private final FileAccessRegistry registry;
    private final HostApplication host;

    public FilesHandler(FileAccessRegistry registry, HostApplication host)
    {
        this.registry = registry;
        this.host = host;
    }

    public Response list(Request request)
    {
        var items = new JsonArray();

        for (var file : host.listOpenFiles())
            registry.byPath(file.getPath()) //
                            .filter(FileAccess::enabled) //
                            .ifPresent(access -> items.add(EntityJson.toJson(access, file)));

        return Response.json(200, EntityJson.envelope(items));
    }

    /**
     * Saves the file like the user's "Save" command - including the backup
     * the user may have configured. Saving a file without changes is a no-op
     * and not an error.
     */
    public static Response save(FileAccessRegistry.FileAccess access, OpenFile file)
    {
        if (file.isDirty())
        {
            try
            {
                file.save();
            }
            catch (IOException e)
            {
                PortfolioLog.error(e);
                throw ApiException.conflict("save-failed", "The file could not be saved", e.getMessage(), //$NON-NLS-1$ //$NON-NLS-2$
                                List.of());
            }

            PortfolioLog.info(MessageFormat.format(Messages.MsgApiFileSaved, file.getLabel()));
        }

        return Response.json(200, EntityJson.toJson(access, file));
    }
}
