
package com.baremaps.cli;

import com.baremaps.blob.BlobStore;
import com.baremaps.postgres.jdbc.PostgresUtils;
import com.baremaps.server.EditorResources;
import com.baremaps.server.MaputnikResources;
import io.servicetalk.http.api.BlockingStreamingHttpService;
import io.servicetalk.http.netty.HttpServers;
import io.servicetalk.http.router.jersey.HttpJerseyRouterBuilder;
import io.servicetalk.transport.api.ServerContext;
import java.net.URI;
import java.util.concurrent.Callable;
import javax.sql.DataSource;
import org.glassfish.hk2.utilities.binding.AbstractBinder;
import org.glassfish.jersey.server.ResourceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Option;

@Command(name = "edit", description = "Edit the vector tiles.")
public class Edit implements Callable<Integer> {

  private static final Logger logger = LoggerFactory.getLogger(Edit.class);

  @Mixin
  private Options options;

  @Option(
      names = {"--database"},
      paramLabel = "DATABASE",
      description = "The JDBC url of the Postgres database.",
      required = true)
  private String database;

  @Option(
      names = {"--tileset"},
      paramLabel = "TILESET",
      description = "The tileset file.",
      required = true)
  private URI tileset;

  @Option(
      names = {"--style"},
      paramLabel = "STYLE",
      description = "The style file.",
      required = true)
  private URI style;

  @Option(
      names = {"--host"},
      paramLabel = "HOST",
      description = "The host of the server.")
  private String host = "localhost";

  @Option(
      names = {"--port"},
      paramLabel = "PORT",
      description = "The port of the server.")
  private int port = 9000;

  @Option(
      names = {"--open"},
      paramLabel = "OPEN",
      description = "Open the browser.")
  private boolean open = false;

  @Override
  public Integer call() throws Exception {
    System.setProperty("logLevel", options.logLevel.name());

    BlobStore blobStore = options.blobStore();
    DataSource datasource = PostgresUtils.datasource(database);

    ResourceConfig config = new ResourceConfig()
        .register(EditorResources.class)
        .register(MaputnikResources.class)
        .register(new AbstractBinder() {
          @Override
          protected void configure() {
            bind(style).named("style").to(URI.class);
            bind(tileset).named("tileset").to(URI.class);
            bind(blobStore).to(BlobStore.class);
            bind(datasource).to(DataSource.class);
          }
        });

    BlockingStreamingHttpService httpService = new HttpJerseyRouterBuilder()
        .buildBlockingStreaming(config);
    ServerContext serverContext = HttpServers.forPort(port)
        .listenBlockingStreamingAndAwait(httpService);

    logger.info("Listening on {}", serverContext.listenAddress());
    serverContext.awaitShutdown();

    return 0;
  }

}