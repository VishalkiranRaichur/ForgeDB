package io.forgedb.sql;

import io.forgedb.catalog.Column;
import io.forgedb.catalog.DataType;
import io.forgedb.exception.ForgeDbException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SqlParser {
    private static final String NAME = "([A-Za-z_][A-Za-z0-9_]*)";

    public Statement parse(String sql) {
        String text = clean(sql);
        if (text.isBlank()) {
            throw syntax("Empty SQL statement");
        }

        if (text.matches("(?i)^help$")) return new Statements.Help();
        if (text.matches("(?i)^(quit|exit)$")) return new Statements.Quit();
        if (text.matches("(?i)^show\\s+databases$")) return new Statements.ShowDatabases();
        if (text.matches("(?i)^show\\s+tables$")) return new Statements.ShowTables();

        Matcher matcher;

        matcher = match("(?i)^create\\s+database\\s+" + NAME + "$", text);
        if (matcher != null) return new Statements.CreateDatabase(matcher.group(1));

        matcher = match("(?i)^drop\\s+database\\s+" + NAME + "$", text);
        if (matcher != null) return new Statements.DropDatabase(matcher.group(1));

        matcher = match("(?i)^use\\s+" + NAME + "$", text);
        if (matcher != null) return new Statements.UseDatabase(matcher.group(1));

        matcher = match("(?is)^create\\s+table\\s+" + NAME + "\\s*\\((.*)\\)$", text);
        if (matcher != null) return parseCreateTable(matcher.group(1), matcher.group(2));

        matcher = match("(?i)^drop\\s+table\\s+" + NAME + "$", text);
        if (matcher != null) return new Statements.DropTable(matcher.group(1));

        matcher = match("(?i)^create\\s+index\\s+" + NAME + "\\s+on\\s+" + NAME
                + "\\s*\\(\\s*" + NAME + "\\s*\\)$", text);
        if (matcher != null) {
            return new Statements.CreateIndex(matcher.group(1), matcher.group(2), matcher.group(3));
        }

        matcher = match("(?i)^drop\\s+index\\s+" + NAME + "$", text);
        if (matcher != null) return new Statements.DropIndex(matcher.group(1));

        matcher = match("(?is)^insert\\s+into\\s+" + NAME + "\\s+values\\s*\\((.*)\\)$", text);
        if (matcher != null) {
            List<String> values = splitCommaAware(matcher.group(2)).stream().map(this::unquote).toList();
            return new Statements.Insert(matcher.group(1), values);
        }

        matcher = match("(?is)^select\\s+\\*\\s+from\\s+" + NAME + "(?:\\s+where\\s+(.+))?$", text);
        if (matcher != null) {
            return new Statements.Select(matcher.group(1), parseConditions(matcher.group(2)));
        }

        matcher = match("(?is)^delete\\s+from\\s+" + NAME + "(?:\\s+where\\s+(.+))?$", text);
        if (matcher != null) {
            return new Statements.Delete(matcher.group(1), parseConditions(matcher.group(2)));
        }

        matcher = match("(?is)^update\\s+" + NAME + "\\s+set\\s+(.+?)\\s+where\\s+(.+)$", text);
        if (matcher != null) {
            return new Statements.Update(matcher.group(1), parseAssignments(matcher.group(2)),
                    parseConditions(matcher.group(3)));
        }

        matcher = match("(?is)^exec\\s+(.+)$", text);
        if (matcher != null) return new Statements.Exec(unquote(matcher.group(1).trim()));

        throw syntax("Unsupported or invalid SQL: " + text);
    }

    private Statement parseCreateTable(String tableName, String body) {
        List<String> parts = splitCommaAware(body);
        List<ColumnDraft> drafts = new ArrayList<>();
        String primaryKey = null;

        Pattern primaryPattern = Pattern.compile("(?i)^primary\\s+key\\s*\\(\\s*" + NAME + "\\s*\\)$");
        Pattern columnPattern = Pattern.compile("(?i)^" + NAME
                + "\\s+(int|float|char\\s*\\(\\s*(\\d+)\\s*\\))$");

        for (String part : parts) {
            String item = part.trim();
            Matcher primary = primaryPattern.matcher(item);
            if (primary.matches()) {
                if (primaryKey != null) {
                    throw syntax("Only one PRIMARY KEY is supported");
                }
                primaryKey = primary.group(1);
                continue;
            }

            Matcher column = columnPattern.matcher(item);
            if (!column.matches()) {
                throw syntax("Invalid column definition: " + item);
            }

            String name = column.group(1);
            String typeText = column.group(2).toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            DataType type;
            int length;
            if (typeText.equals("int")) {
                type = DataType.INT;
                length = Integer.BYTES;
            } else if (typeText.equals("float")) {
                type = DataType.FLOAT;
                length = Float.BYTES;
            } else {
                type = DataType.CHAR;
                length = Integer.parseInt(column.group(3));
                if (length <= 0 || length > 2048) {
                    throw syntax("CHAR length must be between 1 and 2048");
                }
            }

            boolean duplicate = drafts.stream().anyMatch(d -> d.name.equalsIgnoreCase(name));
            if (duplicate) {
                throw syntax("Duplicate column: " + name);
            }
            drafts.add(new ColumnDraft(name, type, length));
        }

        if (drafts.isEmpty()) {
            throw syntax("A table needs at least one column");
        }

        if (primaryKey != null) {
            String pk = primaryKey;
            boolean exists = drafts.stream().anyMatch(d -> d.name.equalsIgnoreCase(pk));
            if (!exists) {
                throw syntax("PRIMARY KEY column does not exist: " + primaryKey);
            }
        }

        String pk = primaryKey;
        List<Column> columns = drafts.stream()
                .map(d -> new Column(d.name, d.type, d.length,
                        pk != null && d.name.equalsIgnoreCase(pk)))
                .toList();
        return new Statements.CreateTable(tableName, columns);
    }

    private List<Condition> parseConditions(String whereText) {
        if (whereText == null || whereText.isBlank()) {
            return List.of();
        }

        List<Condition> conditions = new ArrayList<>();
        Pattern conditionPattern = Pattern.compile("(?is)^" + NAME + "\\s*(<=|>=|<>|=|<|>)\\s*(.+)$");
        for (String part : splitKeywordAware(whereText, "and")) {
            Matcher matcher = conditionPattern.matcher(part.trim());
            if (!matcher.matches()) {
                throw syntax("Invalid WHERE condition: " + part.trim());
            }
            conditions.add(new Condition(matcher.group(1), Operator.fromSymbol(matcher.group(2)),
                    unquote(matcher.group(3).trim())));
        }
        return conditions;
    }

    private Map<String, String> parseAssignments(String text) {
        Map<String, String> assignments = new LinkedHashMap<>();
        Pattern assignmentPattern = Pattern.compile("(?is)^" + NAME + "\\s*=\\s*(.+)$");
        for (String part : splitCommaAware(text)) {
            Matcher matcher = assignmentPattern.matcher(part.trim());
            if (!matcher.matches()) {
                throw syntax("Invalid SET assignment: " + part.trim());
            }
            assignments.put(matcher.group(1), unquote(matcher.group(2).trim()));
        }
        return assignments;
    }

    public List<String> splitStatements(String script) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || script.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            }
            if (c == ';' && quote == 0) {
                if (!current.toString().isBlank()) result.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (!current.toString().isBlank()) result.add(current.toString().trim());
        return result;
    }

    private List<String> splitCommaAware(String text) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int parentheses = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            }
            if (quote == 0) {
                if (c == '(') parentheses++;
                if (c == ')') parentheses--;
                if (c == ',' && parentheses == 0) {
                    parts.add(current.toString().trim());
                    current.setLength(0);
                    continue;
                }
            }
            current.append(c);
        }
        if (!current.toString().isBlank()) parts.add(current.toString().trim());
        return parts;
    }

    private List<String> splitKeywordAware(String text, String keyword) {
        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
                current.append(c);
                i++;
                continue;
            }

            if (quote == 0 && matchesKeywordAt(text, i, keyword)) {
                parts.add(current.toString().trim());
                current.setLength(0);
                i += keyword.length();
                continue;
            }
            current.append(c);
            i++;
        }
        if (!current.toString().isBlank()) parts.add(current.toString().trim());
        return parts;
    }

    private boolean matchesKeywordAt(String text, int index, String keyword) {
        if (index + keyword.length() > text.length()) return false;
        if (!text.regionMatches(true, index, keyword, 0, keyword.length())) return false;
        boolean leftBoundary = index == 0 || Character.isWhitespace(text.charAt(index - 1));
        int right = index + keyword.length();
        boolean rightBoundary = right == text.length() || Character.isWhitespace(text.charAt(right));
        return leftBoundary && rightBoundary;
    }

    private Matcher match(String regex, String text) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        return matcher.matches() ? matcher : null;
    }

    private String clean(String sql) {
        String text = sql.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim();
        while (text.endsWith(";")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return text.replaceAll("\\s+", " ");
    }

    private String unquote(String value) {
        String text = value.trim();
        if (text.length() >= 2) {
            char first = text.charAt(0);
            char last = text.charAt(text.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return text.substring(1, text.length() - 1);
            }
        }
        return text;
    }

    private ForgeDbException syntax(String message) {
        return new ForgeDbException("Syntax error: " + message);
    }

    private record ColumnDraft(String name, DataType type, int length) {
    }
}
