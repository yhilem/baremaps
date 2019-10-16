package io.gazetteer.cli.commands;

import static io.gazetteer.osm.osmpbf.PBFUtil.input;
import static io.gazetteer.osm.osmpbf.PBFUtil.stream;
import static io.gazetteer.osm.osmpbf.PBFUtil.url;
import static io.gazetteer.osm.osmxml.ChangeUtil.statePath;

import io.gazetteer.cli.commands.OSM.Import;
import io.gazetteer.cli.commands.OSM.Update;
import io.gazetteer.cli.util.StopWatch;
import io.gazetteer.common.io.URLUtil;
import io.gazetteer.common.postgis.DatabaseUtils;
import io.gazetteer.osm.data.DirectByteBufferProvider;
import io.gazetteer.osm.data.CoordinateMapper;
import io.gazetteer.osm.data.FixedSizeObjectMap;
import io.gazetteer.osm.data.LongListMapper;
import io.gazetteer.osm.data.LongMapper;
import io.gazetteer.osm.data.VariableSizeObjectMap;
import io.gazetteer.osm.data.VariableSizeObjectStore;
import io.gazetteer.osm.geometry.NodeGeometryBuilder;
import io.gazetteer.osm.geometry.RelationGeometryBuilder;
import io.gazetteer.osm.geometry.WayGeometryBuilder;
import io.gazetteer.osm.data.CacheConsumer;
import io.gazetteer.osm.osmpbf.FileBlock;
import io.gazetteer.osm.osmpbf.HeaderBlock;
import io.gazetteer.osm.osmxml.Change;
import io.gazetteer.osm.osmxml.ChangeUtil;
import io.gazetteer.osm.osmxml.State;
import io.gazetteer.osm.osmxml.StateUtil;
import io.gazetteer.osm.postgis.ChangeConsumer;
import io.gazetteer.osm.postgis.CopyConsumer;
import io.gazetteer.osm.postgis.HeaderTable;
import java.io.InputStream;
import java.net.URL;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.apache.commons.dbcp2.PoolingDataSource;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.PrecisionModel;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(name = "osm", subcommands = {
    Import.class,
    Update.class,
})
public class OSM implements Callable<Integer> {

  @Command(name = "import")
  public static class Import implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "PBF_URL", description = "The PBF file.")
    private String source;

    @Parameters(index = "1", paramLabel = "POSTGRES_DATABASE", description = "The Postgres database.")
    private String database;

    @Option(
        names = {"-t", "--threads"},
        description = "The size of the thread pool.")
    private int threads = Runtime.getRuntime().availableProcessors();

    @Override
    public Integer call() throws Exception {
      StopWatch stopWatch = new StopWatch();
      ForkJoinPool executor = new ForkJoinPool(threads);
      PoolingDataSource datasource = DatabaseUtils.poolingDataSource(database);
      try {
        System.out.println("Creating database.");
        try (Connection connection = datasource.getConnection()) {
          DatabaseUtils.executeScript(connection, "osm_create_tables.sql");
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        try (Connection connection = datasource.getConnection()) {
          System.out.println("Creating primary keys.");
          DatabaseUtils.executeScript(connection, "osm_create_primary_keys.sql");
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        FixedSizeObjectMap<Coordinate> coordinatesMap = new FixedSizeObjectMap<>(new DirectByteBufferProvider(), new CoordinateMapper());
        FixedSizeObjectMap<Long> keys = new FixedSizeObjectMap<>(
            new DirectByteBufferProvider(),
            new LongMapper());
        VariableSizeObjectStore<List<Long>> values =  new VariableSizeObjectStore<>(
            new DirectByteBufferProvider(),
            new LongListMapper());
        VariableSizeObjectMap<List<Long>> referencesMap = new VariableSizeObjectMap<>(keys, values);
        System.out.println("Populating cache.");
        try (InputStream input = input(url(source))) {
          Stream<FileBlock> blocks = stream(input);
          CacheConsumer blockConsumer = new CacheConsumer(coordinatesMap, referencesMap);
          executor.submit(() -> blocks.forEach(blockConsumer)).get();
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        System.out.println("Populating database.");
        try (InputStream input = input(url(source))) {
          Stream<FileBlock> blocks = stream(input);
          GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 3857);
          NodeGeometryBuilder nodeGeometryBuilder = new NodeGeometryBuilder(geometryFactory);
          WayGeometryBuilder wayGeometryBuilder = new WayGeometryBuilder(geometryFactory, coordinatesMap);
          RelationGeometryBuilder relationGeometryBuilder = new RelationGeometryBuilder(geometryFactory, coordinatesMap, referencesMap);
          CopyConsumer blockConsumer = new CopyConsumer(datasource, nodeGeometryBuilder, wayGeometryBuilder, relationGeometryBuilder);
          executor.submit(() -> blocks.forEach(blockConsumer)).get();
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        try (Connection connection = datasource.getConnection()) {
          System.out.println("Indexing geometries.");
          DatabaseUtils.executeScript(connection, "osm_create_indexes.sql");
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        try (Connection connection = datasource.getConnection()) {
          System.out.println("Creating triggers.");
          DatabaseUtils.executeScript(connection, "osm_create_triggers.sql");
          System.out.println(String.format("-> %dms", stopWatch.lap()));
        }

        return 0;
      } finally {
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      }
    }
  }

  @Command(name = "update")
  public static class Update implements Callable<Integer> {

    @Parameters(index = "0", paramLabel = "OSM_FILE", description = "The OpenStreetMap PBF url.")
    private String source;

    @Parameters(index = "1", paramLabel = "POSTGRES_DATABASE", description = "The Postgres database.")
    private String database;

    @Option(
        names = {"-t", "--threads"},
        description = "The size of the thread pool.")
    private int threads = Runtime.getRuntime().availableProcessors();

    @Override
    public Integer call() throws Exception {
      StopWatch stopWatch = new StopWatch();
      ForkJoinPool executor = new ForkJoinPool(threads);
      PoolingDataSource datasource = DatabaseUtils.poolingDataSource(database);
      try {
        HeaderBlock header;
        try (Connection connection = datasource.getConnection()) {
          header = HeaderTable.last(connection);
        }

        long nextSequenceNumber = header.getReplicationSequenceNumber() + 1;

        String statePath = statePath(nextSequenceNumber);
        URL stateURL = new URL(String.format("%s/%s", header.getReplicationUrl(), statePath));
        String stateContent = URLUtil.toString(stateURL);
        State state = StateUtil.parse(stateContent);

        String changePath = ChangeUtil.changePath(nextSequenceNumber);
        URL changeURL = new URL(String.format("%s/%s", header.getReplicationUrl(), changePath));
        try (InputStream changeInputStream = URLUtil.toGZIPInputStream(changeURL)) {
          Stream<Change> changeStream = ChangeUtil.stream(changeInputStream);
          ChangeConsumer changeConsumer = new ChangeConsumer(datasource);
          executor.submit(() -> changeStream.forEach(changeConsumer)).get();
        }

        try (Connection connection = datasource.getConnection()) {
          HeaderTable.insert(connection,
              new HeaderBlock(state.timestamp, state.sequenceNumber, header.getReplicationUrl(), header.getSource(),
                  header.getWritingProgram(),
                  header.getBbox()));
        }

        return 0;
      } finally {
        executor.shutdown();
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS);
      }
    }

  }

  @Override
  public Integer call() throws Exception {
    CommandLine.usage(new OSM(), System.out);
    return 0;
  }

}