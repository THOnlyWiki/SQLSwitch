package cc.thonly.sqlswitch.core;

import cc.thonly.sqlswitch.infer.TypeInference;
import cc.thonly.sqlswitch.model.Dialect;

import java.nio.file.Path;

public final class ConvertRequest {
    public Path input;
    public Path output;
    public Dialect from;
    public Dialect to;
    public boolean stdout;
    public boolean force;

    public boolean noDrop;
    public boolean noSchema;
    public boolean noData;
    public boolean noComments;
    public boolean noTimestamp;
    public boolean bom;
    public boolean transaction = true;
    public boolean preserveOrder;
    public boolean sortRows = true;

    public TypeInference.Mode inferMode = TypeInference.Mode.MISSING;
    public boolean inferDates;
    public boolean inferBooleans = true;
    public boolean autoWiden = true;

    public String charset;
    public String collation;
    public String serverVersion;
    public String databaseName;
    public boolean createDatabase;

    public int maxRowsPerInsert = 200;
    public int maxStatementBytes = 900_000;
}
