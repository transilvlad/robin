package com.mimecast.robin.mx;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mimecast.robin.mx.assets.DnsRecord;
import com.mimecast.robin.mx.client.XBillDnsRecordClient;
import com.mimecast.robin.mx.dane.DaneChecker;
import com.mimecast.robin.mx.dane.DaneRecord;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.help.HelpFormatter;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.config.Configurator;

import java.io.ByteArrayOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintStream;
import java.io.Writer;
import java.util.*;

/**
 * DANE CLI tool for checking TLSA records.
 * <p>Provides command-line interface for DANE TLSA record lookups and validation.
 *
 * @see <a href="https://tools.ietf.org/html/rfc7672">RFC 7672 - DANE for SMTP</a>
 * @see <a href="https://tools.ietf.org/html/rfc6698">RFC 6698 - DANE TLSA</a>
 */
public class DaneMain {

    /**
     * Main runnable.
     *
     * @param args String array.
     */
    public static void main(String[] args) {
        new DaneMain(args);
    }

    /**
     * Constructs a new DaneMain instance.
     *
     * @param args String array.
     */
    DaneMain(String[] args) {
        // Disable logging.
        Configurator.setAllLevels(LogManager.getRootLogger().getName(), Level.OFF);

        // Parse options.
        Options options = options();
        Optional<CommandLine> opt = parseArgs(options, args);

        if (opt.isPresent()) {
            CommandLine cmd = opt.get();

            // Run DANE check.
            if (cmd.hasOption("domain")) {
                String domain = cmd.getOptionValue("domain");
                checkDaneForDomain(cmd, domain);
            }
            // Check specific MX hostname.
            else if (cmd.hasOption("mx")) {
                String mxHostname = cmd.getOptionValue("mx");
                checkDaneForMx(cmd, mxHostname);
            }
            // Show usage.
            else {
                optionsUsage(options);
            }
        }
        // Show usage.
        else {
            optionsUsage(options);
        }
    }

    /**
     * Check DANE for a domain (checks all MX hosts).
     *
     * @param cmd    CommandLine instance.
     * @param domain Domain to check.
     */
    private void checkDaneForDomain(CommandLine cmd, String domain) {
        log("DANE Check for Domain: " + domain);
        log("=".repeat(70));
        log("");

        // Get MX records.
        XBillDnsRecordClient dnsClient = new XBillDnsRecordClient();
        Optional<List<DnsRecord>> mxOpt = dnsClient.getMxRecords(domain);

        if (mxOpt.isEmpty() || mxOpt.get().isEmpty()) {
            log("No MX records found for domain: " + domain);
            return;
        }

        List<DnsRecord> mxRecords = mxOpt.get();
        log("Found " + mxRecords.size() + " MX record(s):");
        log("");

        Map<String, Object> jsonOutput = new LinkedHashMap<>();
        jsonOutput.put("domain", domain);
        jsonOutput.put("mxCount", mxRecords.size());

        List<Map<String, Object>> mxList = new ArrayList<>();

        // Check DANE for each MX.
        for (DnsRecord mxRecord : mxRecords) {
            String mxHostname = mxRecord.getValue();
            int priority = mxRecord.getPriority();

            log("MX Host: " + mxHostname + " (Priority: " + priority + ")");
            log("-".repeat(70));

            List<DaneRecord> daneRecords = DaneChecker.checkDane(mxHostname);

            Map<String, Object> mxData = new LinkedHashMap<>();
            mxData.put("hostname", mxHostname);
            mxData.put("priority", priority);
            mxData.put("daneEnabled", !daneRecords.isEmpty());
            mxData.put("tlsaRecordCount", daneRecords.size());

            if (daneRecords.isEmpty()) {
                log("DANE Enabled:      No");
                log("TLSA Records:      0");
                log("Security Level:    Opportunistic TLS");
                mxData.put("tlsaRecords", Collections.emptyList());
            } else {
                log("DANE Enabled:      Yes");
                log("TLSA Records:      " + daneRecords.size());
                log("Security Level:    MANDATORY TLS with TLSA validation");
                log("");

                List<Map<String, Object>> tlsaList = new ArrayList<>();

                for (int i = 0; i < daneRecords.size(); i++) {
                    DaneRecord record = daneRecords.get(i);

                    log("TLSA Record " + (i + 1) + ":");
                    log("  Usage:           " + record.getUsage() + " - " + record.getUsageDescription());
                    log("  Selector:        " + record.getSelector() + " - " + record.getSelectorDescription());
                    log("  Matching Type:   " + record.getMatchingType() + " - " + record.getMatchingTypeDescription());
                    log("  Certificate Data: " + record.getCertificateData().substring(0, Math.min(64, record.getCertificateData().length())) + "...");
                    log("  Full Record:     " + record.getTlsaRecord());

                    Map<String, Object> tlsaData = new LinkedHashMap<>();
                    tlsaData.put("usage", record.getUsage());
                    tlsaData.put("usageDescription", record.getUsageDescription());
                    tlsaData.put("selector", record.getSelector());
                    tlsaData.put("selectorDescription", record.getSelectorDescription());
                    tlsaData.put("matchingType", record.getMatchingType());
                    tlsaData.put("matchingTypeDescription", record.getMatchingTypeDescription());
                    tlsaData.put("certificateData", record.getCertificateData());
                    tlsaData.put("fullRecord", record.getTlsaRecord());
                    tlsaList.add(tlsaData);

                    if (i < daneRecords.size() - 1) {
                        log("");
                    }
                }

                mxData.put("tlsaRecords", tlsaList);
            }

            mxList.add(mxData);
            log("");
        }

        jsonOutput.put("mxRecords", mxList);

        // Output JSON if requested.
        if (cmd.hasOption("json")) {
            log("=".repeat(70));
            log("JSON Output:");
            log("=".repeat(70));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            log(gson.toJson(jsonOutput));
        }

        // Save to file if requested.
        if (cmd.hasOption("file")) {
            String filePath = cmd.getOptionValue("file");
            saveJson(jsonOutput, filePath);
            log("Output saved to: " + filePath);
        }
    }

    /**
     * Check DANE for a specific MX hostname.
     *
     * @param cmd        CommandLine instance.
     * @param mxHostname MX hostname to check.
     */
    private void checkDaneForMx(CommandLine cmd, String mxHostname) {
        log("DANE Check for MX Host: " + mxHostname);
        log("=".repeat(70));
        log("");

        List<DaneRecord> daneRecords = DaneChecker.checkDane(mxHostname);

        Map<String, Object> jsonOutput = new LinkedHashMap<>();
        jsonOutput.put("mxHostname", mxHostname);
        jsonOutput.put("daneEnabled", !daneRecords.isEmpty());
        jsonOutput.put("tlsaRecordCount", daneRecords.size());

        if (daneRecords.isEmpty()) {
            log("DANE Enabled:      No");
            log("TLSA Records:      0");
            log("Security Level:    Opportunistic TLS");
            log("");
            log("No DANE TLSA records found for " + mxHostname);
            log("TLS is opportunistic - not required by DANE policy.");

            jsonOutput.put("tlsaRecords", Collections.emptyList());
        } else {
            log("DANE Enabled:      Yes");
            log("TLSA Records:      " + daneRecords.size());
            log("Security Level:    MANDATORY TLS with TLSA validation");
            log("");

            List<Map<String, Object>> tlsaList = new ArrayList<>();

            for (int i = 0; i < daneRecords.size(); i++) {
                DaneRecord record = daneRecords.get(i);

                log("TLSA Record " + (i + 1) + ":");
                log("  Usage:           " + record.getUsage() + " - " + record.getUsageDescription());
                log("  Selector:        " + record.getSelector() + " - " + record.getSelectorDescription());
                log("  Matching Type:   " + record.getMatchingType() + " - " + record.getMatchingTypeDescription());
                log("  Certificate Data: " + record.getCertificateData());
                log("  Full Record:     " + record.getTlsaRecord());

                Map<String, Object> tlsaData = new LinkedHashMap<>();
                tlsaData.put("usage", record.getUsage());
                tlsaData.put("usageDescription", record.getUsageDescription());
                tlsaData.put("selector", record.getSelector());
                tlsaData.put("selectorDescription", record.getSelectorDescription());
                tlsaData.put("matchingType", record.getMatchingType());
                tlsaData.put("matchingTypeDescription", record.getMatchingTypeDescription());
                tlsaData.put("certificateData", record.getCertificateData());
                tlsaData.put("fullRecord", record.getTlsaRecord());
                tlsaList.add(tlsaData);

                if (i < daneRecords.size() - 1) {
                    log("");
                }
            }

            jsonOutput.put("tlsaRecords", tlsaList);
            log("");
            log("RFC 7672 Implications:");
            log("  - TLS is MANDATORY for connections to this MX");
            log("  - Server certificate MUST validate against TLSA records");
            log("  - Delivery will FAIL if TLS cannot be established");
            log("  - No fallback to cleartext allowed");
        }

        log("");

        // Output JSON if requested.
        if (cmd.hasOption("json")) {
            log("=".repeat(70));
            log("JSON Output:");
            log("=".repeat(70));
            Gson gson = new GsonBuilder().setPrettyPrinting().create();
            log(gson.toJson(jsonOutput));
        }

        // Save to file if requested.
        if (cmd.hasOption("file")) {
            String filePath = cmd.getOptionValue("file");
            saveJson(jsonOutput, filePath);
            log("");
            log("Output saved to: " + filePath);
        }
    }

    /**
     * Saves JSON to file.
     *
     * @param jsonMap  JSON map.
     * @param filePath File path.
     */
    private void saveJson(Map<String, Object> jsonMap, String filePath) {
        try (Writer writer = new FileWriter(filePath)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(jsonMap, writer);
        } catch (IOException e) {
            log("Error writing JSON: " + e.getMessage());
        }
    }

    /**
     * CLI options.
     * <p>Listing order will be alphabetical.
     *
     * @return Options instance.
     */
    private Options options() {
        Options options = new Options();
        options.addOption("d", "domain", true, "Domain to check (resolves MX and checks all)");
        options.addOption("m", "mx", true, "Specific MX hostname to check");
        options.addOption("j", "json", false, "Show DANE details as JSON");
        options.addOption("f", "file", true, "Write DANE details to JSON file");
        return options;
    }

    /**
     * CLI usage.
     *
     * @param options CLI options.
     */
    private void optionsUsage(Options options) {
        log("java -jar robin.jar --dane");
        log(" Robin DANE client tool");
        log("");
        log("Examples:");
        log("  # Check DANE for all MX hosts of a domain");
        log("  java -jar robin.jar --dane --domain example.com");
        log("");
        log("  # Check DANE for a specific MX hostname");
        log("  java -jar robin.jar --dane --mx mail.example.com");
        log("");
        log("  # Output as JSON");
        log("  java -jar robin.jar --dane --domain example.com --json");
        log("");
        log("  # Save to file");
        log("  java -jar robin.jar --dane --domain example.com --file dane-report.json");
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
     * @param args    Arguments string array.
     * @return Optional of CommandLine.
     */
    private Optional<CommandLine> parseArgs(Options options, String[] args) {
        CommandLine cmd = null;

        try {
            cmd = new DefaultParser().parse(options, args, true);
        } catch (Exception e) {
            log("Ran into a problem: " + e.getMessage());
            log("");
            optionsUsage(options);
        }

        return Optional.ofNullable(cmd);
    }

    /**
     * Logging wrapper.
     *
     * @param string String.
     */
    void log(String string) {
        System.out.println(string);
    }
}
