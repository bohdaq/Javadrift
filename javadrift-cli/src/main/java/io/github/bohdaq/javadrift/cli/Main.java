package io.github.bohdaq.javadrift.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "javadrift", mixinStandardHelpOptions = true, version = "Javadrift 0.1.0-SNAPSHOT",
        description = "Offline stale documentation detection for JVM projects")
public final class Main implements Runnable {
    public void run() { new CommandLine(this).usage(System.out); }
    public static void main(String[] args) { System.exit(new CommandLine(new Main()).execute(args)); }
}
