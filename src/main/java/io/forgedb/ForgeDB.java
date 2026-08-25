package io.forgedb;

import io.forgedb.cli.ForgeDbShell;
import io.forgedb.engine.ForgeDbEngine;

public class ForgeDB {
    public static void main(String[] args) {
        try {
            new ForgeDbShell(new ForgeDbEngine()).run();
        } catch (Exception e) {
            System.err.println("ForgeDB could not start: " + e.getMessage());
            System.exit(1);
        }
    }
}
