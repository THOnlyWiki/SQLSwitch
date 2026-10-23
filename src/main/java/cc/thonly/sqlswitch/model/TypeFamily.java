package cc.thonly.sqlswitch.model;

/**
 * Target-neutral type families used while converting between dialects.
 */
public enum TypeFamily {
    BOOLEAN,
    INTEGER,
    DECIMAL,
    FLOAT,
    DOUBLE,
    CHAR,
    VARCHAR,
    TEXT,
    BINARY,
    VARBINARY,
    BLOB,
    DATE,
    TIME,
    DATETIME,
    TIMESTAMP,
    YEAR,
    JSON,
    ENUM,
    SET,
    BIT,
    UUID,
    INET,
    GEOMETRY,
    UNKNOWN;

    public boolean isTextual() {
        return this == CHAR || this == VARCHAR || this == TEXT || this == JSON
                || this == ENUM || this == SET || this == UUID || this == INET;
    }

    public boolean isInteger() {
        return this == INTEGER || this == BOOLEAN || this == BIT || this == YEAR;
    }

    public boolean isNumeric() {
        return isInteger() || this == DECIMAL || this == FLOAT || this == DOUBLE;
    }

    public boolean isBinary() {
        return this == BINARY || this == VARBINARY || this == BLOB || this == GEOMETRY;
    }

    public boolean isTemporal() {
        return this == DATE || this == TIME || this == DATETIME || this == TIMESTAMP;
    }
}
