package cc.thonly.sqlswitch.core;

import cc.thonly.sqlswitch.detect.DetectionResult;
import cc.thonly.sqlswitch.detect.DialectDetector;
import cc.thonly.sqlswitch.infer.TypeInference;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.parse.SqlParser;
import cc.thonly.sqlswitch.sqlite.SqliteDatabaseReader;
import cc.thonly.sqlswitch.util.IoUtils;
import cc.thonly.sqlswitch.util.Log;
import cc.thonly.sqlswitch.write.MariaDbWriter;
import cc.thonly.sqlswitch.write.MySqlWriter;
import cc.thonly.sqlswitch.write.SqlWriter;
import cc.thonly.sqlswitch.write.SqliteWriter;
import cc.thonly.sqlswitch.model.TargetConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.List;

public final class ConversionService {
    public DetectionResult detect(Path input) throws IOException {
        return DialectDetector.detect(input);
    }

    public Schema loadSchema(Path input, Dialect forcedDialect) throws IOException, SQLException {
        DetectionResult detection = detectOrForced(input, forcedDialect);
        Schema schema;
        if (detection.databaseFile()) {
            schema = new SqliteDatabaseReader(input).read();
        } else {
            SqlParser parser = new SqlParser(detection.dialect());
            schema = parser.parseFile(input);
            schema.source.serverVersion = detection.serverVersion();
            schema.source.detectedBy = String.join("; ", detection.reasons());
        }
        if (schema.name == null || schema.name.isBlank()) {
            String fileName = input.getFileName() == null ? "schema" : input.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            schema.name = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        return schema;
    }

    public ConversionResult convert(ConvertRequest request) throws IOException, SQLException {
        long start = System.currentTimeMillis();
        if (request.input == null) {
            throw new IllegalArgumentException("Input file is required");
        }
        if (!request.input.toFile().isFile()) {
            throw new IOException("Input file not found: " + request.input);
        }
        if (request.to == null) {
            throw new IllegalArgumentException("Target dialect is required (--to sqlite|mysql|mariadb)");
        }
        DetectionResult detection = detectOrForced(request.input, request.from);
        if (detection.databaseFile() && request.from != null && request.from != Dialect.SQLITE) {
            throw new IOException("Input " + request.input
                    + " is a SQLite database file, not a " + request.from.displayName() + " dump");
        }

        Log.detail("Source: " + detection.summary());
        Schema schema;
        if (detection.databaseFile()) {
            Log.detail("Reading SQLite database directly (read-only)");
            schema = new SqliteDatabaseReader(request.input).read();
        } else {
            Log.detail("Parsing " + detection.dialect().displayName() + " dump");
            SqlParser parser = new SqlParser(detection.dialect());
            schema = parser.parseFile(request.input);
            schema.source.serverVersion = detection.serverVersion();
            schema.source.detectedBy = String.join("; ", detection.reasons());
        }
        if (schema.name == null || schema.name.isBlank()) {
            String fileName = request.input.getFileName() == null ? "schema" : request.input.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            schema.name = dot > 0 ? fileName.substring(0, dot) : fileName;
        }
        if (!request.preserveOrder) {
            schema.tables.sort(java.util.Comparator.comparing(
                    t -> t.name.toLowerCase(java.util.Locale.ROOT)));
        }
        if (schema.tables.isEmpty()) {
            schema.addWarning("No tables were found in the input");
        }
        Log.detail("Loaded " + schema.tables.size() + " tables, " + schema.totalRows() + " rows");

        TypeInference.apply(schema, request.inferMode, request.inferDates,
                request.inferBooleans, request.autoWiden);

        TargetConfig config = buildConfig(request);
        SqlWriter writer = switch (request.to) {
            case SQLITE -> new SqliteWriter();
            case MYSQL -> new MySqlWriter();
            case MARIADB -> new MariaDbWriter();
        };

        Path output = request.output;
        long bytes = 0;
        if (request.stdout) {
            Writer stdout = new OutputStreamWriter(System.out, StandardCharsets.UTF_8);
            if (request.bom) {
                stdout.write('\uFEFF');
            }
            writer.write(schema, config, stdout);
            stdout.flush();
            output = null;
        } else {
            if (output == null) {
                output = defaultOutputPath(request.input, request.to);
            }
            Path absolute = output.toAbsolutePath();
            if (Files.exists(absolute) && !request.force) {
                throw new IOException("Output already exists: " + absolute
                        + " (use --force to overwrite)");
            }
            IoUtils.ensureParent(absolute);
            Path parent = absolute.getParent();
            Path temp = Files.createTempFile(parent, ".sqlswitch-", ".tmp");
            boolean success = false;
            try {
                try (Writer fileWriter = new BufferedWriter(
                        new OutputStreamWriter(Files.newOutputStream(temp), StandardCharsets.UTF_8), 1 << 20)) {
                    if (request.bom) {
                        fileWriter.write('\uFEFF');
                    }
                    writer.write(schema, config, fileWriter);
                }
                try {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING,
                            StandardCopyOption.ATOMIC_MOVE);
                } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                    Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
                }
                success = true;
                bytes = Files.size(absolute);
            } finally {
                if (!success) {
                    Files.deleteIfExists(temp);
                }
            }
            output = absolute;
        }

        long millis = System.currentTimeMillis() - start;
        int warnings = schema.warnings.size();
        List<String> messages = List.copyOf(schema.warnings);
        return new ConversionResult(output, detection.dialect(), request.to,
                detection.databaseFile(), schema.tables.size(), schema.totalRows(),
                schema.totalColumns(), warnings, bytes, millis, messages);
    }

    public TargetConfig buildConfig(ConvertRequest request) {
        TargetConfig config = new TargetConfig(request.to);
        if (request.serverVersion != null && !request.serverVersion.isBlank()) {
            config.serverVersion = request.serverVersion;
        }
        config.charset = request.charset;
        config.collation = request.collation;
        if (config.isMySqlFamily() && config.charset != null && config.collation != null
                && !config.charset.isBlank() && !config.collation.isBlank()) {
            String cs = config.charset.toLowerCase(java.util.Locale.ROOT);
            String coll = config.collation.toLowerCase(java.util.Locale.ROOT);
            boolean matches;
            if (cs.equals("utf8") || cs.equals("utf8mb3")) {
                matches = coll.startsWith("utf8_") || coll.startsWith("utf8mb3_");
            } else {
                matches = coll.startsWith(cs);
            }
            if (!matches) {
                throw new IllegalArgumentException("--collation " + config.collation
                        + " does not match --charset " + config.charset);
            }
        }
        config.emitDrop = !request.noDrop;
        config.includeSchema = !request.noSchema;
        config.includeData = !request.noData;
        config.comments = !request.noComments;
        config.header = !request.noComments;
        config.noTimestamp = request.noTimestamp;
        config.bom = request.bom;
        config.transaction = request.transaction;
        config.sortRows = request.sortRows;
        config.databaseName = request.databaseName;
        config.emitCreateDatabase = request.createDatabase;
        config.maxRowsPerInsert = request.maxRowsPerInsert;
        config.maxStatementBytes = request.maxStatementBytes;
        config.autoWidenVarchar = request.autoWiden;
        config.inferTypes = request.inferMode != TypeInference.Mode.OFF;
        config.inferDates = request.inferDates;
        config.inferBooleans = request.inferBooleans;
        return config;
    }

    public static Path defaultOutputPath(Path input, Dialect target) {
        String name = input.getFileName() == null ? "output" : input.getFileName().toString();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        return input.toAbsolutePath().resolveSibling(name + "." + target.name().toLowerCase() + ".sql");
    }

    private DetectionResult detectOrForced(Path input, Dialect forced) throws IOException {
        boolean sqliteFile = IoUtils.isSqliteFile(input);
        if (forced != null) {
            if (sqliteFile && forced != Dialect.SQLITE) {
                throw new IOException("Input " + input + " is a SQLite database file, not a "
                        + forced.displayName() + " dump");
            }
            return new DetectionResult(forced, sqliteFile, 1.0, List.of("forced by --from"), null);
        }
        return DialectDetector.detect(input);
    }
}
