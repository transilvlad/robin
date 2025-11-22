package com.mimecast.robin;

import com.mimecast.robin.main.ClientCLI;
import com.mimecast.robin.main.ServerCLI;
import com.mimecast.robin.mx.DaneMain;
import com.mimecast.robin.mx.MtaStsMain;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;

/**
 * Main runnable.
 *
 * <p>This implements the commandline --client and --server options.
 * <p>Further CLI options are implemented individually within each component.
 *
 * @see ServerCLI
 * @see ClientCLI
 */
public class Main {

    /**
     * Application jar name.
     */
    private static final String NAME = "robin.jar";

    /**
     * Application jar usage.
     */
    public static final String USAGE = "java -jar " + NAME;

    /**
     * Application description.
     */
    public static final String DESCRIPTION = "MTA server and tester";

    private String[] args;

    /**
     * Main runnable.
     *
     * @param args String array.
     */
    public static void main(String[] args) {
        new Main(args);
    }

    /**
     * Constructs a new Main instance.
     *
     * @param args String array.
     */
    Main(String[] args) {
        this.args = args;

        // Disable logging.
        Configurator.setAllLevels(LogManager.getRootLogger().getName(), Level.OFF);

        // Parse options.
        Optional<CommandLine> opt = parseArgs(options());

        if (opt.isPresent()) {
            CommandLine cmd = opt.get();

            // Run client.
            if (cmd.hasOption("client")) {
                purgeArg("--client");
                new ClientCLI(this);
            }

            // Run server.
            else if (cmd.hasOption("server")) {
                purgeArg("--server");
                ServerCLI.main(this);
            }

            // Run MTA-STS library.
            else if (cmd.hasOption("mtasts")) {
                purgeArg("--mtasts");
                MtaStsMain.main(args);
            }

            // Run DANE library.
            else if (cmd.hasOption("dane")) {
                purgeArg("--dane");
                DaneMain.main(args);
            }

            // Show usage.
            else {
                optionsUsage(options());
            }
        }

        // Show usage.
        else {
            optionsUsage(options());
        }
    }

    /**
     * CLI options.
     * <i>Listing order will be alphabetical</i>.
     *
     * @return Options instance.
     */
    private Options options() {
        Options options = new Options();
        options.addOption(null, "client", false, "Run as client");
        options.addOption(null, "server", false, "Run as server");
        options.addOption(null, "mtasts", false, "Run as MTA-STS client");
        options.addOption(null, "dane", false, "Run as DANE client");
        return options;
    }

    /**
     * CLI usage.
     *
     * @param options Options instance.
     */
    public void optionsUsage(Options options) {
        log(USAGE);
        log(" " + DESCRIPTION);
        log("");

        // Capture System.out to get help output.
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(baos);
        PrintStream oldOut = System.out;
        System.setOut(ps);

        try {
            HelpFormatter formatter = HelpFormatter.builder()
                .setShowSince(false)
                .get();
            formatter.printHelp(" ", "", options, "", true);
            System.out.flush();
        } catch (java.io.IOException e) {
            // Should not happen with ByteArrayOutputStream.
            throw new RuntimeException(e);
        } finally {
            System.setOut(oldOut);
        }

        log(baos.toString());
        log("");
    }

    /**
     * Parser for CLI arguments.
     *
     * @param options Options instance.
     * @return Optional of CommandLine.
     */
    public Optional<CommandLine> parseArgs(Options options) {
        CommandLine cmd = null;

        try {
            cmd = new DefaultParser().parse(options, args, true);
        } catch (Exception e) {
            log("Options error: " + e.getMessage());
            log("");
            optionsUsage(options);
        }

        return Optional.ofNullable(cmd);
    }

    /**
     * Remove entry from string array.
     *
     * @param entry Entry string.
     */
    private void purgeArg(String entry) {
        List<String> list = new LinkedList<>(Arrays.asList(args));
        list.remove(entry);
        args = list.toArray(new String[0]);
    }

    /**
     * Gets args.
     *
     * @return String array.
     */
    public String[] getArgs() {
        return args;
    }

    /**
     * Logging wrapper.
     *
     * @param string String.
     */
    public void log(String string) {
        System.out.println(string);
    }
}
