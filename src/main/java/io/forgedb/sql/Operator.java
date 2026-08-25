package io.forgedb.sql;

public enum Operator {
    EQ("="),
    NE("<>"),
    LT("<"),
    GT(">"),
    LE("<="),
    GE(">=");

    private final String symbol;

    Operator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    public static Operator fromSymbol(String symbol) {
        for (Operator operator : values()) {
            if (operator.symbol.equals(symbol)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("Unknown operator: " + symbol);
    }
}
