package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.Index;
import cc.thonly.sqlswitch.model.IndexColumn;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.util.TextUtils;

import java.util.List;

/**
 * Parses CREATE [UNIQUE|FULLTEXT|SPATIAL] INDEX statements.
 */
public final class IndexParser {
    private final Schema schema;
    private final CreateTableParser createTableParser;

    public IndexParser(Schema schema, CreateTableParser createTableParser) {
        this.schema = schema;
        this.createTableParser = createTableParser;
    }

    public void parse(TokenStream ts, int line, boolean unique, String type) {
        if (ts.matchKeyword("IF")) {
            ts.expectKeyword("NOT");
            ts.expectKeyword("EXISTS");
        }
        String name = ts.expectIdentifier();
        if (ts.matchKeyword("USING")) {
            if (!ts.atEnd()) {
                type = ts.next().text.toUpperCase();
            }
        }
        ts.expectKeyword("ON");
        String tableName = TextUtils.stripSchema(ts.readQualifiedIdentifier());
        Table table = schema.getTable(tableName);
        if (table == null) {
            schema.addWarning("Index " + name + " refers to unknown table " + tableName + " (line " + line + ")");
            return;
        }
        List<IndexColumn> columns = createTableParser.parseIndexColumnList(ts);
        Index index = new Index(name, unique, columns);
        index.origin = "INDEX";
        if (type != null && !type.isBlank()) {
            index.type = type.toUpperCase();
        }
        if (ts.matchKeyword("USING")) {
            if (!ts.atEnd()) {
                index.type = ts.next().text.toUpperCase();
            }
        }
        if (ts.matchKeyword("WHERE")) {
            int start = ts.index();
            int depth = 0;
            while (!ts.atEnd()) {
                Token t = ts.peek();
                if (t.isSymbol("(")) {
                    depth++;
                } else if (t.isSymbol(")")) {
                    depth--;
                } else if (depth == 0 && t.isSymbol(";")) {
                    break;
                }
                ts.next();
            }
            index.where = ts.rawBetween(start, ts.index()).trim();
        }
        for (IndexColumn ic : index.columns) {
            if (ic.column().startsWith("(")) {
                index.unsupportedReason = "expression index";
            } else if (table.getColumn(ic.column()) == null) {
                index.unsupportedReason = "unknown column " + ic.column();
            }
        }
        table.indexes.add(index);
    }
}
