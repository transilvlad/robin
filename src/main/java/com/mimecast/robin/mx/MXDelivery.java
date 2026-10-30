package com.mimecast.robin.mx;

import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.EmailDelivery;
import com.mimecast.robin.smtp.security.SecureMxRecord;
import com.mimecast.robin.smtp.session.Session;
import com.mimecast.robin.smtp.transaction.SessionTransactionList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;

/**
 * Resolves a queued recipient domain at delivery time, keeping per-MX TLS policy.
 */
public class MXDelivery {
    private final MXResolver resolver;
    private final Consumer<Session> sender;

    public MXDelivery() {
        this(new MXResolver(), session -> new EmailDelivery(session).send());
    }

    MXDelivery(MXResolver resolver, Consumer<Session> sender) {
        this.resolver = resolver;
        this.sender = sender;
    }

    public void deliver(Session queued) {
        SessionTransactionList result = queued.getSessionTransactionList();
        result.clear();
        if (queued.getMx() == null || queued.getMx().size() != 1) {
            throw new IllegalArgumentException("MX delivery requires one queued recipient domain");
        }
        try {
            List<SecureMxRecord> records = new ArrayList<>(resolver.resolveDeliveryMx(queued.getMx().getFirst()));
            records.sort(Comparator.comparingInt(SecureMxRecord::getPriority));
            int candidates = 0;
            int limit = Math.toIntExact(Config.getServer().getRelay().getLongProperty("maxMxCandidates", 16L));
            for (SecureMxRecord mx : records) {
                for (String address : new MXServer(mx.getHostname(), mx.getPriority()).getIpAddresses()) {
                    if (++candidates > limit) {
                        break;
                    }
                    Session attempt = new Session().setDirection(queued.getDirection())
                            .setEhlo(Config.getServer().getHostname())
                            .setMx(List.of(address)).setPort(25).setTls(true)
                            .setSecurityPolicy(mx.getSecurityPolicy())
                            .setBind(queued.getBind()).setConnectTimeout(queued.getConnectTimeout())
                            .setTimeout(queued.getTimeout()).setExtendedTimeout(queued.getExtendedTimeout());
                    // Delivery borrows queue-owned envelopes; never close/delete their sources here.
                    queued.getEnvelopes().forEach(attempt::addEnvelope);
                    sender.accept(attempt);
                    queued.setFriendRdns(mx.getHostname()).setFriendAddr(address);
                    result.clear();
                    result.getTransactions().addAll(attempt.getSessionTransactionList().getTransactions());
                    result.getEnvelopes().addAll(attempt.getSessionTransactionList().getEnvelopes());
                    // Once MAIL has started, DATA may have committed; do not duplicate on another MX.
                    if (!result.getEnvelopes().isEmpty()) {
                        return;
                    }
                }
            }
            if (result.getErrors().isEmpty()) {
                result.addTransaction("DNS", "451 4.4.3 No reachable MX address", true);
            }
        } catch (MailRoutingException e) {
            result.addTransaction("DNS", (e.isPermanent() ? "550 5.1.2 " : "451 4.4.3 ") + e.getMessage(), true);
        }
    }
}
