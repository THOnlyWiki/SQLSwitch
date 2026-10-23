package cc.thonly.sqlswitch.model;

/**
 * One column inside a table.
 */
public final class Column {
    public String name;
    public DataType type = new DataType();
    public boolean nullable = true;
    public int primaryKeyOrder;
    public boolean autoIncrement;
    public boolean hasDefault;
    public Value defaultValue;
    public String onUpdate;
    public String comment;
    public String charset;
    public String collation;
    public String generatedExpression;
    public boolean generatedStored;
    public boolean invisible;
    public String rawDefinition;

    public Column() {
    }

    public Column(String name, DataType type) {
        this.name = name;
        this.type = type;
    }

    public boolean isPrimaryKey() {
        return primaryKeyOrder > 0;
    }

    public boolean isGenerated() {
        return generatedExpression != null && !generatedExpression.isBlank();
    }

    public boolean isNullableEffective() {
        return nullable && !isPrimaryKey() && !autoIncrement;
    }

    public String displayType() {
        return type != null && type.isPresent() ? type.raw : "TEXT";
    }

    public Column copy() {
        Column c = new Column(name, type == null ? new DataType() : type.copy());
        c.nullable = nullable;
        c.primaryKeyOrder = primaryKeyOrder;
        c.autoIncrement = autoIncrement;
        c.hasDefault = hasDefault;
        c.defaultValue = defaultValue;
        c.onUpdate = onUpdate;
        c.comment = comment;
        c.charset = charset;
        c.collation = collation;
        c.generatedExpression = generatedExpression;
        c.generatedStored = generatedStored;
        c.invisible = invisible;
        c.rawDefinition = rawDefinition;
        return c;
    }

    @Override
    public String toString() {
        return name + " " + displayType();
    }
}
