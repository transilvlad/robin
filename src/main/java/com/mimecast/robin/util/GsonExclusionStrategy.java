package com.mimecast.robin.util;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.session.Session;

import java.util.List;

public class GsonExclusionStrategy implements ExclusionStrategy {
    private final List<String> sessionExclude = List.of("timeout", "extendedtimeout", "connectTimeout",
            "bind", "ehloLog", "securePort", "password", "assertConfig", "magic", "savedResults");
    private final List<String> envelopeExclude = List.of("rcpt", "stream", "bytes", "assertConfig");
    private final List<String> transactionExclude = List.of("repeatable");

    @Override
    public boolean shouldSkipField(FieldAttributes f) {
        // Exclude heavy, sensitive or irrelevant fields from Session and TransactionList.
        if (f.getDeclaringClass() == Session.class || f.getDeclaringClass().getSimpleName().equals("TransactionList")) {
            String name = f.getName();
            return sessionExclude.contains(name) || transactionExclude.contains(name);
        }

        // Exclude binary fields from MessageEnvelope
        if (f.getDeclaringClass() == MessageEnvelope.class) {
            String name = f.getName();
            return envelopeExclude.contains(name);
        }
        return false;
    }

    @Override
    public boolean shouldSkipClass(Class<?> clazz) {
        return false;
    }
}
