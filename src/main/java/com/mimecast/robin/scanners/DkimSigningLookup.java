package com.mimecast.robin.scanners;

import com.mimecast.robin.config.server.RspamdConfig.DkimSigningConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * DKIM signing lookup.
 * <p>
 * Queries a SQL database to retrieve the list of DKIM signing domain and selector pairs for a
 * given sender domain. Multiple pairs may be returned, enabling outbound emails to carry more
 * than one DKIM signature (e.g., the sender domain key and an ESP domain key).
 * <p>
 * This class manages its own {@link HikariDataSource} connection pool, initialized lazily on
 * first use from the provided {@link DkimSigningConfig}. The singleton instance is shared
 * across all calls within the same JVM lifetime; call {@link #close()} on server shutdown to
 * release the pool.
 * <p>
 * The backing database schema is managed externally. A two-table reference schema for use with
 * the Robin Management Solution would typically look like:
 * <pre>
 * -- Maps sender domains to signing configurations.
 * CREATE TABLE dkim_signing_domains (
 *     id       SERIAL PRIMARY KEY,
 *     domain   VARCHAR(255) NOT NULL UNIQUE
 * );
 *
 * -- Each signing entry: signing domain and selector (multiple per sender domain).
 * CREATE TABLE dkim_signing_selectors (
 *     id          SERIAL PRIMARY KEY,
 *     domain_id   INTEGER REFERENCES dkim_signing_domains(id),
 *     sign_domain VARCHAR(255) NOT NULL,
 *     selector    VARCHAR(255) NOT NULL
 * );
 * </pre>
 *
 * @see RspamdConfig.DkimSigningConfig
 * @see RspamdClient#addDkimSigningOption(String, String)
 */
public class DkimSigningLookup {
    private static final Logger log = LogManager.getLogger(DkimSigningLookup.class);

    private static volatile DkimSigningLookup instance;

    private final HikariDataSource ds;
    private final String signingQuery;

    /**
     * Constructs a new DkimSigningLookup with a dedicated connection pool.
     *
     * @param config DKIM signing configuration.
     */
    private DkimSigningLookup(DkimSigningConfig config) {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(config.getJdbcUrl());
        hc.setUsername(config.getUser());
        hc.setPassword(config.getPassword());
        hc.setMaximumPoolSize(4);
        hc.setPoolName("DkimSigningPool");
        this.ds = new HikariDataSource(hc);
        this.signingQuery = config.getSigningQuery();
        log.info("Initialized DkimSigningLookup pool: {}", config.getJdbcUrl());
    }

    /**
     * Package-private constructor for testing — accepts a pre-built datasource.
     *
     * @param ds           Pre-built datasource.
     * @param signingQuery SQL query string.
     */
    DkimSigningLookup(HikariDataSource ds, String signingQuery) {
        this.ds = ds;
        this.signingQuery = signingQuery;
    }

    /**
     * Returns the shared singleton instance, creating it on first call.
     *
     * @param config DKIM signing configuration (used only when creating the instance).
     * @return Shared DkimSigningLookup instance.
     */
    public static DkimSigningLookup getInstance(DkimSigningConfig config) {
        if (instance == null) {
            synchronized (DkimSigningLookup.class) {
                if (instance == null) {
                    instance = new DkimSigningLookup(config);
                }
            }
        }
        return instance;
    }

    /**
     * Looks up all DKIM signing domain and selector pairs for the given sender domain.
     *
     * @param domain Sender domain to look up (e.g., "example.com").
     * @return List of {@code [domain, selector]} pairs; empty list if none found or on error.
     */
    public List<String[]> lookup(String domain) {
        if (ds == null) {
            return Collections.emptyList();
        }
        List<String[]> results = new ArrayList<>();
        try (Connection c = ds.getConnection();
             PreparedStatement ps = c.prepareStatement(signingQuery)) {
            ps.setString(1, domain);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String signingDomain = rs.getString("domain");
                    String selector = rs.getString("selector");
                    if (signingDomain != null && selector != null) {
                        results.add(new String[]{signingDomain, selector});
                    }
                }
            }
        } catch (SQLException e) {
            log.error("DKIM signing lookup failed for domain {}: {}", domain, e.getMessage());
            return Collections.emptyList();
        }
        return results;
    }

    /**
     * Closes the connection pool and clears the singleton instance.
     * <p>
     * Should be called during server shutdown alongside other datasource cleanup.
     */
    public static synchronized void close() {
        if (instance != null) {
            try {
                if (instance.ds != null) {
                    instance.ds.close();
                    log.info("Closed DkimSigningLookup pool");
                }
            } catch (Exception e) {
                log.warn("Error closing DkimSigningLookup pool: {}", e.getMessage());
            } finally {
                instance = null;
            }
        }
    }
}
