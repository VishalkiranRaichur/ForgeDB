package io.forgedb.cli;

import io.forgedb.engine.ForgeDbEngine;
import io.forgedb.exception.ForgeDbException;
import io.forgedb.sql.SqlParser;
import io.forgedb.sql.Statement;
import io.forgedb.sql.Statements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class ForgeDbShell {
    private final ForgeDbEngine engine;
    private final SqlParser parser = new SqlParser();

    public ForgeDbShell(ForgeDbEngine engine) {
        this.engine = engine;
    }

    public void run() throws IOException {
        System.out.println("ForgeDB " + ForgeDbEngine.VERSION + " — type HELP; for commands.");
        System.out.println("Data directory: " + engine.getDataDirectory());

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            StringBuilder statement = new StringBuilder();
            while (true) {
                System.out.print(prompt(statement.length() > 0));
                String line = reader.readLine();
                if (line == null) {
                    break;
                }

                if (statement.length() == 0 && line.trim().matches("(?i)^(quit|exit)$")) {
                    System.out.println("Bye from ForgeDB.");
                    break;
                }

                statement.append(line).append('\n');
                if (!hasTerminatingSemicolon(statement.toString())) {
                    continue;
                }

                for (String sql : parser.splitStatements(statement.toString())) {
                    try {
                        Statement parsed = parser.parse(sql);
                        if (parsed instanceof Statements.Quit) {
                            System.out.println("Bye from ForgeDB.");
                            return;
                        }
                        String output = engine.execute(parsed);
                        if (!output.isBlank()) {
                            System.out.println(output);
                        }
                    } catch (ForgeDbException e) {
                        System.err.println("ERROR: " + e.getMessage());
                    }
                }
                statement.setLength(0);
            }
        } finally {
            engine.close();
        }
    }

    private String prompt(boolean continuation) {
        if (continuation) return "       -> ";
        String database = engine.getCurrentDatabase();
        return database == null ? "forgedb> " : "forgedb[" + database + "]> ";
    }

    private boolean hasTerminatingSemicolon(String text) {
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c == '\'' || c == '"') && (i == 0 || text.charAt(i - 1) != '\\')) {
                if (quote == 0) quote = c;
                else if (quote == c) quote = 0;
            }
            if (c == ';' && quote == 0) return true;
        }
        return false;
    }
}
