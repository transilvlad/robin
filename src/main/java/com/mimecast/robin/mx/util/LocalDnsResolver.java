package com.mimecast.robin.mx.util;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.xbill.DNS.*;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Local DNS Resolver.
 * <p>This provides a static resolver for DNS Java to aid in testing.
 * <p>It has limited capabilities but more than needed for this lib.
 * <p>It can only handle NS, A, AAAA, CNAME, MX, PTR and TXT types.
 * <p>Strings should not exceed 255 bytes.
 * <p>A strings should be valid IPv4 addresses.
 * <p>NS, MX and PTR strings should not be empty.
 * <p>The database is static and shared by every test class in the JVM.
 * As tests run concurrently (see junit-platform.properties) it must be
 * thread safe, otherwise concurrent registrations corrupt it and records
 * silently disappear.
 *
 * @author "Vlad Marian" (vmarian@mimecast.com)
 * @link <a href="http://mimecast.com">Mimecast</a>
 */
@SuppressWarnings("squid:S1186")
public class LocalDnsResolver implements Resolver {
    private static final Logger log = LogManager.getLogger(LocalDnsResolver.class);

    /**
     * Static database.
     * <p>Concurrent as test classes register records from parallel threads.
     */
    private static final Map<String, Map<Integer, List<String>>> map = new ConcurrentHashMap<>();

    /**
     * Put entries in database.
     *
     * @param record Record string.
     * @param type   Record type.
     * @param answer Answer list of strings.
     */
    public static void put(String record, int type, List<String> answer) {
        Map<Integer, List<String>> types = map.computeIfAbsent(record, k -> new ConcurrentHashMap<>());

        if (answer == null) {
            types.remove(type);
        } else {
            // Store an immutable snapshot so readers never observe a partially built list.
            types.put(type, List.copyOf(answer));
        }
    }

    /**
     * Clears the database.
     * <p>For testing.
     */
    public static void clear() {
        map.clear();
    }

    /**
     * Lookup record.
     *
     * @param question Record question instance.
     * @return List of Record.
     */
    private List<org.xbill.DNS.Record> lookup(org.xbill.DNS.Record question) {
        Map<Integer, List<String>> answer = map.get(question.getName().toString(true));
        List<org.xbill.DNS.Record> response = new ArrayList<>();

        if (answer != null && !answer.isEmpty()) {
            List<String> records = answer.get(question.getType());

            if (records != null && !records.isEmpty()) {
                try {
                    response = loop(question.getName(), records, question.getType());
                } catch (TextParseException e) {
                    log.error("Record cannot be parsed: {}", e.getMessage());
                } catch (UnknownHostException e) {
                    log.error("Record host could not be resolved: {}", e.getMessage());
                }
            }
        }

        return response;
    }

    /**
     * Loop results and build responses.
     *
     * @param name    Record name.
     * @param records List of String records.
     * @return List of Record.
     */
    private List<org.xbill.DNS.Record> loop(Name name, List<String> records, int type) throws TextParseException, UnknownHostException {
        List<org.xbill.DNS.Record> response = new ArrayList<>();

        switch (type) {
            case Type.NS:
                for (String record : records) {
                    response.add(new NSRecord(name, 1, 300L, new Name(record)));
                }
                break;
            case Type.A:
                for (String record : records) {
                    response.add(new ARecord(name, 1, 300L, InetAddress.getByName(record)));
                }
                break;
            case Type.AAAA:
                for (String record : records) {
                    response.add(new AAAARecord(name, 1, 300L, InetAddress.getByName(record)));
                }
                break;
            case Type.CNAME:
                for (String record : records) {
                    response.add(new CNAMERecord(name, 1, 300L, new Name(record)));
                }
                break;
            case Type.MX:
                for (String record : records) {
                    int priority = 1;
                    String target = record;
                    String[] parts = record.trim().split("\\s+", 2);
                    if (parts.length == 2) {
                        try {
                            priority = Integer.parseInt(parts[0]);
                            target = parts[1];
                        } catch (NumberFormatException ignored) {
                            // Keep compatibility with existing "hostname" only format.
                        }
                    }
                    response.add(new MXRecord(name, 1, 300L, priority, new Name(target)));
                }
                break;
            case Type.PTR:
                for (String record : records) {
                    response.add(new PTRRecord(name, 1, 300L, new Name(record)));
                }
                break;
            case Type.TXT:
                for (String record : records) {
                    response.add(new TXTRecord(name, 1, 300L, record));
                }
                break;
            default:
                log.fatal("Record type unsupported");
                throw new IllegalArgumentException("Record type unsupported");
        }

        return response;
    }

    /**
     * Resolves DNS queries from the static deque.
     *
     * @param question Record question instance.
     * @return Record answer instance.
     */
    @Override
    public Message send(Message question) {
        Message answer = new Message();
        answer.getHeader().setID(question.getHeader().getID());
        answer.getHeader().setOpcode(question.getHeader().getOpcode());
        answer.addRecord(question.getQuestion(), 0);

        // Answer.
        List<org.xbill.DNS.Record> records = lookup(question.getQuestion());
        if (!records.isEmpty()) {
            for (org.xbill.DNS.Record record : records) {
                answer.addRecord(record, 1);
            }
        }

        return answer;
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query) {
        return new ExtendedResolver().sendAsync(query);
    }

    @Override
    public CompletionStage<Message> sendAsync(Message query, Executor executor) {
        return new ExtendedResolver().sendAsync(query, executor);
    }

    /**
     * Unused.
     */

    @Override
    public void setPort(int i) {
    }

    @Override
    public void setTCP(boolean b) {
    }

    @Override
    public void setIgnoreTruncation(boolean b) {
    }

    @Override
    public void setEDNS(int i) {
    }

    @Override
    public void setEDNS(int version, int payloadSize, int flags, EDNSOption... options) {
        Resolver.super.setEDNS(version, payloadSize, flags, options);
    }

    @Override
    public void setEDNS(int i, int i1, int i2, List list) {
    }

    @Override
    public void setTSIGKey(TSIG tsig) {
    }

    @Override
    public void setTimeout(Duration timeout) {
    }

    @Override
    public Duration getTimeout() {
        return Resolver.super.getTimeout();
    }
}
