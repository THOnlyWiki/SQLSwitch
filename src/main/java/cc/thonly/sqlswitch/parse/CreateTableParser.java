package cc.thonly.sqlswitch.parse;

import cc.thonly.sqlswitch.model.CheckConstraint;
import cc.thonly.sqlswitch.model.Column;
import cc.thonly.sqlswitch.model.DataType;
import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.model.ForeignKey;
import cc.thonly.sqlswitch.model.Index;
import cc.thonly.sqlswitch.model.IndexColumn;
import cc.thonly.sqlswitch.model.Schema;
import cc.thonly.sqlswitch.model.Table;
import cc.thonly.sqlswitch.model.TypeFamily;
import cc.thonly.sqlswitch.model.Value;
import cc.thonly.sqlswitch.util.TextUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Parser for CREATE TABLE statements in both MySQL/MariaDB and SQLite
 * flavours.  It intentionally keeps unknown table options out of the model
 * instead of failing, but warns so the user knows what was dropped.
 */
public final class CreateTableParser {
    private final Dialect dialect;
    private final ValueParser valueParser;
    private final Schema schema;

    public CreateTableParser(Dialect dialect, ValueParser valueParser, Schema schema) {
        this.dialect = dialect;
        this.valueParser = valueParser;
        this.schema = schema;
    }

    public Table parse(TokenStream ts, int line) {
        boolean ifNotExists = false;
        if (ts.matchKeyword("IF")) {
            ts.expectKeyword("NOT");
            ts.expectKeyword("EXISTS");
            ifNotExists = true;
        }
        String qualifiedName = ts.readQualifiedIdentifier();
        String tableName = TextUtils.stripSchema(qualifiedName);
        if (tableName.equalsIgnoreCase("sqlite_sequence")) {
            return null;
        }
        if (!ts.matchSymbol("(")) {
            if (ts.matchKeyword("LIKE")) {
                schema.addSkippedTable(tableName);
                schema.addWarning("CREATE TABLE ... LIKE is not supported and was skipped: " + tableName);
                return null;
            }
            if (ts.matchKeyword("AS")) {
                schema.addSkippedTable(tableName);
                schema.addWarning("CREATE TABLE ... AS SELECT is not supported and was skipped: " + tableName);
                return null;
            }
            throw new ParseException("Expected '(' after table name " + tableName, ts.peek().line, ts.peek().column);
        }

        int openIndex = ts.index() - 1;
        int closeIndex = findMatchingParen(ts.tokens(), openIndex);
        List<int[]> groups = splitGroups(ts.tokens(), openIndex + 1, closeIndex);

        Table table = new Table(tableName);
        table.sourceOrder = schema.tables.size();
        table.rawCreateSql = ts.sql();
        for (int[] range : groups) {
            if (range[0] >= range[1]) {
                continue;
            }
            TokenStream group = new TokenStream(ts.sql(), ts.tokens());
            group.index(range[0]);
            parseGroup(group, table, range[1]);
        }

        ts.index(closeIndex + 1);
        parseTableOptions(ts, table);

        if (dialect == Dialect.SQLITE && !table.withoutRowid) {
            List<Column> pk = table.primaryKeyColumns();
            if (pk.size() == 1) {
                Column column = pk.get(0);
                boolean descending = false;
                for (IndexColumn part : table.primaryKey) {
                    if (part.column().equalsIgnoreCase(column.name)) {
                        descending = part.descending();
                        break;
                    }
                }
                if (!descending && column.type != null
                        && column.type.family == TypeFamily.INTEGER
                        && "INTEGER".equalsIgnoreCase(column.type.base)) {
                    table.rowidAlias = true;
                }
            }
        }

        if (schema.hasTable(tableName)) {
            schema.addWarning("Duplicate CREATE TABLE for " + tableName + "; replacing the previous definition");
            schema.replaceTable(table);
        } else {
            schema.addTable(table);
        }
        if (ifNotExists) {
            // IF NOT EXISTS is a property of the dump, not of the schema.  The
            // generated dump uses DROP TABLE IF EXISTS instead.
        }
        return table;
    }

    private void parseGroup(TokenStream group, Table table, int end) {
        String constraintName = null;
        if (group.matchKeyword("CONSTRAINT")) {
            if (!isTableConstraintHead(group.peek())) {
                constraintName = group.expectIdentifier();
            }
        }
        Token head = group.peek();
        if (isTableConstraintHead(head)) {
            parseTableConstraint(group, table, end, constraintName);
        } else {
            parseColumn(group, table, end);
        }
    }

    private void parseColumn(TokenStream g, Table table, int end) {
        String columnName = g.expectIdentifier();
        int typeStart = g.index();
        int i = typeStart;
        while (i < end) {
            Token t = g.tokens().get(i);
            if (t.isSymbol("(")) {
                i = skipBalanced(g.tokens(), i);
                continue;
            }
            if (t.kind == Token.Kind.SYMBOL) {
                if (t.text.equals(",") || t.text.equals(")")) {
                    break;
                }
                i++;
                continue;
            }
            if (t.kind == Token.Kind.IDENT && i > typeStart
                    && isConstraintStart(g.tokens(), i, typeStart)) {
                break;
            }
            i++;
        }
        String typeRaw = g.rawBetween(typeStart, i).replaceAll("\\s+", " ").trim();
        DataType type = DataType.parse(typeRaw);
        g.index(i);

        Column column = new Column(columnName, type);
        column.rawDefinition = g.rawBetween(typeStart == 0 ? 0 : typeStart - 1, end);
        table.addColumn(column);

        while (g.index() < end && !g.atEnd()) {
            Token t = g.peek();
            String upper = t.upper();
            if (t.isSymbol(",") || t.isSymbol(")")) {
                break;
            }
            if (upper.equals("NOT") && g.peek(1).isKeyword("NULL")) {
                g.next();
                g.next();
                column.nullable = false;
            } else if (upper.equals("NULL")) {
                g.next();
                column.nullable = true;
            } else if (upper.equals("PRIMARY")) {
                g.next();
                g.matchKeyword("KEY");
                boolean desc = false;
                if (g.matchKeyword("ASC")) {
                    desc = false;
                } else if (g.matchKeyword("DESC")) {
                    desc = true;
                }
                addPrimaryKey(table, columnName, desc);
                if (g.matchKeyword("AUTOINCREMENT")) {
                    column.autoIncrement = true;
                    table.autoIncrementValue = Math.max(table.autoIncrementValue, 1);
                }
                if (g.matchKeyword("ON") && g.matchKeyword("CONFLICT")) {
                    if (!g.atEnd()) {
                        g.next();
                    }
                }
            } else if (upper.equals("UNIQUE")) {
                g.next();
                g.matchKeyword("KEY");
                Index index = new Index();
                index.unique = true;
                index.origin = "UNIQUE";
                index.columns.add(new IndexColumn(columnName, null, false));
                table.indexes.add(index);
                if (g.matchKeyword("ON") && g.matchKeyword("CONFLICT")) {
                    if (!g.atEnd()) {
                        g.next();
                    }
                }
            } else if (upper.equals("AUTO_INCREMENT") || upper.equals("AUTOINCREMENT")) {
                g.next();
                column.autoIncrement = true;
                table.autoIncrementValue = Math.max(table.autoIncrementValue, 1);
            } else if (upper.equals("DEFAULT")) {
                g.next();
                Value value = valueParser.parseDefault(g);
                column.hasDefault = true;
                column.defaultValue = value;
            } else if (upper.equals("ON") && g.peek(1).isKeyword("UPDATE")) {
                g.next();
                g.next();
                column.onUpdate = readExpressionUntilConstraint(g, end);
            } else if (upper.equals("ON") && g.peek(1).isKeyword("CONFLICT")) {
                g.next();
                g.next();
                if (!g.atEnd()) {
                    g.next();
                }
            } else if (upper.equals("CHECK")) {
                g.next();
                String expression = readParenthesizedInner(g);
                table.checks.add(new CheckConstraint(null, expression));
                if (g.matchKeyword("NOT")) {
                    g.matchKeyword("ENFORCED");
                } else {
                    g.matchKeyword("ENFORCED");
                }
            } else if (upper.equals("REFERENCES")) {
                g.next();
                ForeignKey fk = new ForeignKey();
                fk.columns = List.of(columnName);
                fk.referencedTable = TextUtils.stripSchema(g.readQualifiedIdentifier());
                if (g.atSymbol("(")) {
                    fk.referencedColumns = parseColumnNameList(g);
                }
                parseForeignKeyActions(g, fk);
                table.foreignKeys.add(fk);
            } else if (upper.equals("COMMENT")) {
                g.next();
                if (g.peek().kind == Token.Kind.STRING) {
                    column.comment = g.next().text;
                } else {
                    g.next();
                }
            } else if (upper.equals("COLLATE")) {
                g.next();
                if (g.peek().isIdentifier()) {
                    column.collation = g.next().text;
                }
            } else if (upper.equals("CHARACTER") && g.peek(1).isKeyword("SET")) {
                g.next();
                g.next();
                if (g.peek().isIdentifier()) {
                    column.charset = g.next().text;
                }
            } else if (upper.equals("CHARSET")) {
                g.next();
                if (g.peek().isIdentifier()) {
                    column.charset = g.next().text;
                }
            } else if (upper.equals("BINARY")) {
                g.next();
                type.binaryAttribute = true;
            } else if (upper.equals("GENERATED") || upper.equals("AS")) {
                if (upper.equals("GENERATED")) {
                    g.next();
                    g.matchKeyword("ALWAYS");
                    g.matchKeyword("AS");
                } else {
                    g.next();
                }
                String expression = readParenthesizedInner(g);
                column.generatedExpression = expression;
                if (g.matchKeyword("STORED")) {
                    column.generatedStored = true;
                } else if (g.matchKeyword("VIRTUAL")) {
                    column.generatedStored = false;
                }
            } else if (upper.equals("VISIBLE")) {
                g.next();
                column.invisible = false;
            } else if (upper.equals("INVISIBLE")) {
                g.next();
                column.invisible = true;
            } else if (upper.equals("SRID")) {
                g.next();
                if (g.peek().kind == Token.Kind.NUMBER) {
                    g.next();
                }
            } else if (upper.equals("COLUMN_FORMAT") || upper.equals("STORAGE")) {
                g.next();
                if (!g.atEnd()) {
                    g.next();
                }
            } else if (upper.equals("DEFERRABLE") || upper.equals("INITIALLY")) {
                g.next();
                if (upper.equals("INITIALLY") && !g.atEnd()) {
                    g.next();
                }
            } else if (upper.equals("NOT") && g.peek(1).isKeyword("DEFERRABLE")) {
                g.next();
                g.next();
            } else if (upper.equals("UNSIGNED") || upper.equals("ZEROFILL") || upper.equals("SIGNED")) {
                g.next();
            } else {
                schema.addWarning("Unrecognised column attribute in " + table.name + "." + columnName
                        + ": " + g.peek().raw);
                g.next();
            }
        }
    }

    private void addPrimaryKey(Table table, String columnName, boolean desc) {
        if (!table.primaryKey.isEmpty()) {
            schema.addWarning("Multiple PRIMARY KEY definitions in table " + table.name);
            return;
        }
        table.primaryKey.add(new IndexColumn(columnName, null, desc));
        Column column = table.getColumn(columnName);
        if (column != null) {
            column.primaryKeyOrder = 1;
            column.nullable = false;
        }
    }

    public void parseTableConstraint(TokenStream g, Table table, int end, String constraintName) {
        Token head = g.peek();
        String upper = head.upper();
        if (upper.equals("PRIMARY")) {
            g.next();
            g.matchKeyword("KEY");
            skipIndexOptions(g);
            List<IndexColumn> columns = parseIndexColumnList(g);
            setPrimaryKey(table, columns);
        } else if (upper.equals("UNIQUE")) {
            g.next();
            g.matchKeyword("KEY");
            g.matchKeyword("INDEX");
            String name = constraintName;
            if (name == null && looksLikeIndexName(g.peek(), g.peek(1))) {
                name = g.next().text;
            }
            skipIndexOptions(g);
            List<IndexColumn> columns = parseIndexColumnList(g);
            Index index = new Index(name, true, columns);
            index.origin = "UNIQUE";
            addIndex(table, index);
        } else if (upper.equals("KEY") || upper.equals("INDEX")) {
            g.next();
            String name = constraintName;
            if (name == null && looksLikeIndexName(g.peek(), g.peek(1))) {
                name = g.next().text;
            }
            skipIndexOptions(g);
            List<IndexColumn> columns = parseIndexColumnList(g);
            Index index = new Index(name, false, columns);
            index.origin = "INDEX";
            addIndex(table, index);
        } else if (upper.equals("FULLTEXT") || upper.equals("SPATIAL")) {
            String type = upper;
            g.next();
            g.matchKeyword("KEY");
            g.matchKeyword("INDEX");
            String name = constraintName;
            if (name == null && looksLikeIndexName(g.peek(), g.peek(1))) {
                name = g.next().text;
            }
            List<IndexColumn> columns = parseIndexColumnList(g);
            Index index = new Index(name, false, columns);
            index.type = type;
            index.origin = "INDEX";
            addIndex(table, index);
        } else if (upper.equals("FOREIGN")) {
            g.next();
            g.matchKeyword("KEY");
            ForeignKey fk = new ForeignKey();
            fk.name = constraintName;
            if (g.peek().isIdentifier() && g.peek(1).isSymbol("(")) {
                fk.name = g.next().text;
            }
            fk.columns = parseColumnNameList(g);
            g.expectKeyword("REFERENCES");
            fk.referencedTable = TextUtils.stripSchema(g.readQualifiedIdentifier());
            if (g.atSymbol("(")) {
                fk.referencedColumns = parseColumnNameList(g);
            }
            parseForeignKeyActions(g, fk);
            table.foreignKeys.add(fk);
        } else if (upper.equals("CHECK")) {
            g.next();
            String expression = readParenthesizedInner(g);
            table.checks.add(new CheckConstraint(constraintName, expression));
            if (g.matchKeyword("NOT")) {
                g.matchKeyword("ENFORCED");
            } else {
                g.matchKeyword("ENFORCED");
            }
        } else {
            schema.addWarning("Unrecognised table constraint in " + table.name + ": " + head.raw
                    + " (rest of the definition was skipped)");
            g.index(end);
        }
    }

    private void setPrimaryKey(Table table, List<IndexColumn> columns) {
        if (!table.primaryKey.isEmpty()) {
            schema.addWarning("Multiple PRIMARY KEY definitions in table " + table.name);
            return;
        }
        table.primaryKey.addAll(columns);
        int order = 1;
        for (IndexColumn ic : columns) {
            Column c = table.getColumn(ic.column());
            if (c == null) {
                schema.addWarning("Primary key in " + table.name + " references unknown column " + ic.column());
                continue;
            }
            c.primaryKeyOrder = order++;
            c.nullable = false;
        }
    }

    private void addIndex(Table table, Index index) {
        for (IndexColumn ic : index.columns) {
            if (table.getColumn(ic.column()) == null) {
                schema.addWarning("Index " + (index.name == null ? "(unnamed)" : index.name)
                        + " in " + table.name + " references unknown column " + ic.column());
            }
        }
        table.indexes.add(index);
    }

    private void skipIndexOptions(TokenStream g) {
        while (true) {
            if (g.matchKeyword("USING")) {
                if (!g.atEnd()) {
                    g.next();
                }
            } else if (g.matchKeyword("COMMENT")) {
                if (!g.atEnd()) {
                    g.next();
                }
            } else {
                return;
            }
        }
    }

    private static boolean isIndexNameToken(Token token) {
        if (!token.isIdentifier()) {
            return false;
        }
        return switch (token.upper()) {
            case "USING", "COMMENT", "KEY", "INDEX", "ASC", "DESC", "VISIBLE", "INVISIBLE" -> false;
            default -> true;
        };
    }

    private static boolean looksLikeIndexName(Token name, Token next) {
        if (!isIndexNameToken(name)) {
            return false;
        }
        return next.isSymbol("(") || next.isKeyword("USING") || next.isKeyword("COMMENT");
    }

    public List<IndexColumn> parseIndexColumnList(TokenStream g) {
        List<IndexColumn> columns = new ArrayList<>();
        g.expectSymbol("(");
        int depth = 1;
        while (!g.atEnd() && depth > 0) {
            if (g.atSymbol("(")) {
                // Expression index.  We cannot safely translate it, so keep the
                // raw text as a pseudo-column and let the writer warn/skip.
                int start = g.index();
                g.next();
                int innerDepth = 1;
                while (!g.atEnd() && innerDepth > 0) {
                    Token t = g.next();
                    if (t.isSymbol("(")) {
                        innerDepth++;
                    } else if (t.isSymbol(")")) {
                        innerDepth--;
                    }
                }
                columns.add(new IndexColumn(g.rawBetween(start, g.index()), null, false));
            } else {
                String name;
                if (g.peek().isIdentifier()) {
                    name = g.next().text;
                } else if (g.peek().kind == Token.Kind.STRING) {
                    name = g.next().text;
                } else {
                    throw new ParseException("Expected index column but found '" + g.peek().text + "'",
                            g.peek().line, g.peek().column);
                }
                Integer prefix = null;
                if (g.matchSymbol("(")) {
                    if (g.peek().kind == Token.Kind.NUMBER) {
                        try {
                            prefix = Integer.parseInt(g.next().text);
                        } catch (NumberFormatException e) {
                            prefix = null;
                        }
                    }
                    g.expectSymbol(")");
                }
                boolean desc = false;
                if (g.matchKeyword("ASC")) {
                    desc = false;
                } else if (g.matchKeyword("DESC")) {
                    desc = true;
                }
                columns.add(new IndexColumn(name, prefix, desc));
            }
            if (g.matchSymbol(",")) {
                continue;
            }
            if (g.matchSymbol(")")) {
                depth = 0;
            } else {
                break;
            }
        }
        return columns;
    }

    public List<String> parseColumnNameList(TokenStream g) {
        List<String> columns = new ArrayList<>();
        g.expectSymbol("(");
        int depth = 1;
        while (!g.atEnd() && depth > 0) {
            if (g.matchSymbol(")")) {
                depth = 0;
                break;
            }
            if (g.peek().isIdentifier()) {
                columns.add(g.next().text);
            } else if (g.peek().kind == Token.Kind.STRING) {
                columns.add(g.next().text);
            } else {
                // Skip unexpected tokens rather than aborting the whole table.
                g.next();
            }
            if (g.matchSymbol(",")) {
                continue;
            }
            if (g.matchSymbol(")")) {
                depth = 0;
            }
        }
        return columns;
    }

    private void parseForeignKeyActions(TokenStream g, ForeignKey fk) {
        while (!g.atEnd()) {
            if (g.matchKeyword("ON")) {
                if (g.matchKeyword("DELETE")) {
                    fk.onDelete = readAction(g);
                } else if (g.matchKeyword("UPDATE")) {
                    fk.onUpdate = readAction(g);
                } else {
                    g.next();
                }
            } else if (g.matchKeyword("MATCH")) {
                fk.match = readAction(g);
            } else if (g.matchKeyword("DEFERRABLE")) {
                if (g.matchKeyword("INITIALLY")) {
                    readAction(g);
                }
            } else if (g.matchKeyword("NOT")) {
                if (g.matchKeyword("DEFERRABLE")) {
                    if (g.matchKeyword("INITIALLY")) {
                        readAction(g);
                    }
                }
            } else if (g.matchKeyword("INITIALLY")) {
                readAction(g);
            } else {
                return;
            }
        }
    }

    private String readAction(TokenStream g) {
        if (g.atEnd()) {
            return null;
        }
        String first = g.next().text;
        if ((first.equalsIgnoreCase("SET") || first.equalsIgnoreCase("NO"))
                && !g.atEnd() && (g.peek().isKeyword("NULL") || g.peek().isKeyword("DEFAULT")
                || g.peek().isKeyword("ACTION"))) {
            return first + " " + g.next().text;
        }
        return first;
    }

    private String readParenthesizedInner(TokenStream g) {
        g.expectSymbol("(");
        int depth = 1;
        int start = g.index();
        while (!g.atEnd() && depth > 0) {
            Token t = g.next();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    int end = g.index() - 1;
                    return g.rawBetween(start, end).trim();
                }
            }
        }
        throw new ParseException("Unterminated parenthesised expression", g.peek().line, g.peek().column);
    }

    private String readExpressionUntilConstraint(TokenStream g, int end) {
        int start = g.index();
        int depth = 0;
        while (g.index() < end && !g.atEnd()) {
            Token t = g.peek();
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                if (depth == 0) {
                    break;
                }
                depth--;
            } else if (depth == 0 && t.kind == Token.Kind.IDENT
                    && isConstraintStart(g.tokens(), g.index(), start)) {
                break;
            } else if (depth == 0 && (t.isSymbol(",") || t.isSymbol(")"))) {
                break;
            }
            g.next();
        }
        return g.rawBetween(start, g.index()).trim();
    }

    private void parseTableOptions(TokenStream ts, Table table) {
        boolean warnedPartition = false;
        while (!ts.atEnd()) {
            if (ts.atSymbol(";")) {
                ts.next();
                break;
            }
            Token t = ts.peek();
            String upper = t.upper();
            if (upper.equals("ENGINE")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.engine = ts.next().text;
                }
            } else if (upper.equals("AUTO_INCREMENT")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().kind == Token.Kind.NUMBER) {
                    table.autoIncrementValue = Long.parseLong(ts.next().text);
                }
            } else if (upper.equals("DEFAULT")) {
                ts.next();
                if (ts.matchKeyword("CHARSET")) {
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.charset = ts.next().text;
                    }
                } else if (ts.matchKeyword("CHARACTER")) {
                    ts.matchKeyword("SET");
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.charset = ts.next().text;
                    }
                } else if (ts.matchKeyword("COLLATE")) {
                    ts.matchSymbol("=");
                    if (ts.peek().isIdentifier()) {
                        table.collation = ts.next().text;
                    }
                } else {
                    // Unknown DEFAULT table option; skip one token.
                    if (!ts.atEnd()) {
                        ts.next();
                    }
                }
            } else if (upper.equals("CHARSET")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.charset = ts.next().text;
                }
            } else if (upper.equals("CHARACTER") && ts.peek(1).isKeyword("SET")) {
                ts.next();
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.charset = ts.next().text;
                }
            } else if (upper.equals("COLLATE")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.collation = ts.next().text;
                }
            } else if (upper.equals("ROW_FORMAT")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().isIdentifier()) {
                    table.rowFormat = ts.next().text;
                }
            } else if (upper.equals("COMMENT")) {
                ts.next();
                ts.matchSymbol("=");
                if (ts.peek().kind == Token.Kind.STRING) {
                    table.comment = ts.next().text;
                }
            } else if (upper.equals("WITHOUT") && ts.peek(1).isKeyword("ROWID")) {
                ts.next();
                ts.next();
                table.withoutRowid = true;
            } else if (upper.equals("STRICT")) {
                ts.next();
                table.strict = true;
            } else if (ts.atSymbol(",")) {
                ts.next();
            } else if (upper.equals("PARTITION")) {
                if (!warnedPartition) {
                    schema.addWarning("PARTITION BY clauses are not converted; table "
                            + table.name + " will be created without partitioning");
                    warnedPartition = true;
                }
                break;
            } else if (upper.equals("AS")) {
                schema.addWarning("CREATE TABLE ... AS SELECT data is not exported for " + table.name);
                break;
            } else if (upper.equals("TABLESPACE") || upper.equals("STORAGE") || upper.equals("ENCRYPTION")
                    || upper.equals("COMPRESSION") || upper.equals("KEY_BLOCK_SIZE")
                    || upper.equals("AVG_ROW_LENGTH") || upper.equals("CHECKSUM")
                    || upper.equals("DELAY_KEY_WRITE") || upper.equals("PACK_KEYS")
                    || upper.equals("MAX_ROWS") || upper.equals("MIN_ROWS")
                    || upper.equals("STATS_AUTO_RECALC") || upper.equals("STATS_PERSISTENT")
                    || upper.equals("STATS_SAMPLE_PAGES") || upper.equals("PAGE_COMPRESSED")
                    || upper.equals("PAGE_COMPRESSION_LEVEL") || upper.equals("SEQUENCE")) {
                ts.next();
                ts.matchSymbol("=");
                if (!ts.atEnd() && !ts.atSymbol(";") && !ts.atSymbol(",")) {
                    ts.next();
                }
            } else if (upper.equals("START")) {
                break;
            } else {
                schema.addWarning("Ignoring unrecognised table option for " + table.name + ": " + t.raw);
                ts.next();
            }
        }
    }

    private static int findMatchingParen(List<Token> tokens, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        throw new ParseException("Unclosed '(' in table definition", tokens.get(openIndex).line,
                tokens.get(openIndex).column);
    }

    private static int skipBalanced(List<Token> tokens, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < tokens.size(); i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return tokens.size();
    }

    private static List<int[]> splitGroups(List<Token> tokens, int start, int end) {
        List<int[]> groups = new ArrayList<>();
        int depth = 0;
        int groupStart = start;
        for (int i = start; i < end; i++) {
            Token t = tokens.get(i);
            if (t.isSymbol("(")) {
                depth++;
            } else if (t.isSymbol(")")) {
                depth--;
            } else if (t.isSymbol(",") && depth == 0) {
                groups.add(new int[]{groupStart, i});
                groupStart = i + 1;
            }
        }
        if (groupStart < end) {
            groups.add(new int[]{groupStart, end});
        }
        return groups;
    }

    private static boolean isTableConstraintHead(Token token) {
        return switch (token.upper()) {
            case "PRIMARY", "UNIQUE", "KEY", "INDEX", "FULLTEXT", "SPATIAL", "FOREIGN", "CHECK", "PERIOD" -> true;
            default -> false;
        };
    }

    private static boolean isConstraintStart(List<Token> tokens, int index, int typeStart) {
        Token token = tokens.get(index);
        String upper = token.upper();
        Token next = index + 1 < tokens.size() ? tokens.get(index + 1) : null;
        return switch (upper) {
            case "NOT", "NULL", "DEFAULT", "PRIMARY", "UNIQUE", "AUTO_INCREMENT", "AUTOINCREMENT",
                    "COMMENT", "COLLATE", "CHARSET", "CHECK", "REFERENCES", "GENERATED", "AS",
                    "VISIBLE", "INVISIBLE", "ON" -> true;
            case "CHARACTER" -> next != null && next.isKeyword("SET");
            case "BINARY" -> index > typeStart;
            default -> false;
        };
    }

    @SuppressWarnings("unused")
    private static String normalize(String raw) {
        return raw == null ? "" : raw.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }
}
