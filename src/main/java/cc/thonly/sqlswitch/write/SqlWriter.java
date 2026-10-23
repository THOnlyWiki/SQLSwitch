package cc.thonly.sqlswitch.write;

import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.TargetConfig;

import java.io.IOException;
import java.io.Writer;

public interface SqlWriter {
    void write(Schema schema, TargetConfig config, Writer out) throws IOException;
}
