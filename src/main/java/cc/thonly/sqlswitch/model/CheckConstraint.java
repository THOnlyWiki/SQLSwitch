package cc.thonly.sqlswitch.model;

/**
 * A CHECK constraint, stored as raw SQL so that dialect-specific expressions
 * survive as far as possible.
 */
public final class CheckConstraint {
    public String name;
    public String expression;

    public CheckConstraint() {
    }

    public CheckConstraint(String name, String expression) {
        this.name = name;
        this.expression = expression;
    }
}
