package com.mimecast.robin.queue;

import com.mimecast.robin.mx.dane.DaneRecord;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.security.SecurityPolicy;
import com.mimecast.robin.smtp.session.EmailDirection;
import com.mimecast.robin.smtp.session.Session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Encodes queue payloads using a compact RelaySession format with a Java serialization fallback.
 */
final class QueuePayloadCodec {
    private static final byte JAVA_OBJECT_FORMAT = 0;
    private static final byte RELAY_SESSION_FORMAT_V1 = 1;

    private QueuePayloadCodec() {
    }

    static byte[] serialize(Serializable payload) {
        try {
            if (payload instanceof RelaySession relaySession) {
                return serializeRelaySession(relaySession);
            }
            return serializeJavaPayload(payload);
        } catch (IOException e) {
            throw new RuntimeException("Failed to serialize queue payload", e);
        }
    }

    @SuppressWarnings("unchecked")
    static <T extends Serializable> T deserialize(byte[] data) {
        if (data == null || data.length == 0) {
            throw new RuntimeException("Queue payload is empty");
        }

        try (ByteArrayInputStream bis = new ByteArrayInputStream(data);
             DataInputStream input = new DataInputStream(bis)) {
            byte format = input.readByte();
            if (format == RELAY_SESSION_FORMAT_V1) {
                return (T) deserializeRelaySession(input);
            }
            if (format == JAVA_OBJECT_FORMAT) {
                return (T) deserializeJavaPayload(input);
            }
            throw new RuntimeException("Unsupported queue payload format: " + format);
        } catch (IOException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to deserialize queue payload", e);
        }
    }

    private static byte[] serializeRelaySession(RelaySession relaySession) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             DataOutputStream output = new DataOutputStream(bos)) {
            output.writeByte(RELAY_SESSION_FORMAT_V1);
            writeString(output, relaySession.getUID());
            writeString(output, relaySession.getProtocol());
            writeString(output, relaySession.getMailbox());
            writeString(output, relaySession.getPoolKey());
            output.writeInt(relaySession.getRetryCount());
            output.writeInt(relaySession.getMaxRetryCount());
            output.writeLong(relaySession.getCreateTime());
            output.writeLong(relaySession.getLastRetryTime());
            writeSession(output, relaySession.getSession());
            output.flush();
            return bos.toByteArray();
        }
    }

    private static RelaySession deserializeRelaySession(DataInputStream input) throws IOException, ClassNotFoundException {
        String uid = readString(input);
        String protocol = readString(input);
        String mailbox = readString(input);
        String poolKey = readString(input);
        int retryCount = input.readInt();
        int maxRetryCount = input.readInt();
        long createTime = input.readLong();
        long lastRetryTime = input.readLong();
        Session session = readSession(input);
        return RelaySession.restore(session, uid, protocol, mailbox, poolKey, retryCount, maxRetryCount, createTime,
                lastRetryTime);
    }

    private static byte[] serializeJavaPayload(Serializable payload) throws IOException {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            bos.write(JAVA_OBJECT_FORMAT);
            try (ObjectOutputStream objectOutput = new ObjectOutputStream(bos)) {
                objectOutput.writeObject(payload);
                objectOutput.flush();
            }
            return bos.toByteArray();
        }
    }

    private static Object deserializeJavaPayload(DataInputStream input) throws IOException, ClassNotFoundException {
        try (ObjectInputStream objectInput = new ObjectInputStream(input)) {
            return objectInput.readObject();
        }
    }

    private static void writeSession(DataOutputStream output, Session session) throws IOException {
        output.writeBoolean(session != null);
        if (session == null) {
            return;
        }

        writeEnum(output, session.getDirection());
        writeString(output, session.getUID());
        writeStringArray(output, session.getProtocols());
        writeStringArray(output, session.getCiphers());
        output.writeInt(session.getRetry());
        output.writeInt(session.getDelay());
        output.writeInt(session.getTimeout());
        output.writeInt(session.getExtendedTimeout());
        output.writeInt(session.getConnectTimeout());
        writeString(output, session.getBind());
        writeStringList(output, session.getMx());
        output.writeInt(session.getPort());
        writeString(output, session.getRdns());
        writeString(output, session.getAddr());
        writeString(output, session.getFriendRdns());
        writeString(output, session.getFriendAddr());
        output.writeBoolean(session.isFriendInRbl());
        writeString(output, session.getFriendRbl());
        output.writeBoolean(session.isBlackholed());
        writeString(output, session.getHelo());
        writeString(output, session.getLhlo());
        writeString(output, session.getEhlo());
        output.writeLong(session.getEhloSize() != null ? session.getEhloSize() : -1L);
        output.writeBoolean(session.isEhloTls());
        output.writeBoolean(session.isSmtpUtf8());
        output.writeBoolean(session.isEhlo8bit());
        output.writeBoolean(session.isEhloBinary());
        output.writeBoolean(session.isEhloBdat());
        writeString(output, session.getEhloLog());
        writeStringList(output, session.getEhloAuth());
        output.writeBoolean(session.isTls());
        output.writeBoolean(session.isStartTls());
        writeSecurityPolicy(output, session.getSecurityPolicy());
        output.writeBoolean(session.isSecurePort());
        output.writeBoolean(session.isAuthBeforeTls());
        output.writeBoolean(session.isAuth());
        output.writeBoolean(session.isAuthLoginCombined());
        output.writeBoolean(session.isAuthLoginRetry());
        writeString(output, session.getUsername());
        writeString(output, session.getPassword());
        writeStringList(output, session.getBehaviour());
        writeSerializedObject(output, session.getMagic());
        writeSerializedObject(output, session.getSavedResults());

        List<MessageEnvelope> envelopes = session.getEnvelopes();
        output.writeInt(envelopes != null ? envelopes.size() : -1);
        if (envelopes != null) {
            for (MessageEnvelope envelope : envelopes) {
                writeSerializedObject(output, envelope);
            }
        }
    }

    private static Session readSession(DataInputStream input) throws IOException, ClassNotFoundException {
        if (!input.readBoolean()) {
            return null;
        }

        Session session = new Session();
        session.setDirection(readEnum(input, EmailDirection.class));
        session.setUID(readString(input));
        session.setProtocols(readStringArray(input));
        session.setCiphers(readStringArray(input));
        session.setRetry(input.readInt());
        session.setDelay(input.readInt());
        session.setTimeout(input.readInt());
        session.setExtendedTimeout(input.readInt());
        session.setConnectTimeout(input.readInt());
        session.setBind(readString(input));
        List<String> mx = readStringList(input);
        if (mx != null) {
            session.setMx(mx);
        }
        session.setPort(input.readInt());
        session.setRdns(readString(input));
        session.setAddr(readString(input));
        session.setFriendRdns(readString(input));
        session.setFriendAddr(readString(input));
        session.setFriendInRbl(input.readBoolean());
        session.setFriendRbl(readString(input));
        session.setBlackholed(input.readBoolean());
        session.setHelo(readString(input));
        session.setLhlo(readString(input));
        session.setEhlo(readString(input));
        session.setEhloSize(input.readLong());
        session.setEhloTls(input.readBoolean());
        session.setSmtpUtf8(input.readBoolean());
        session.setEhlo8bit(input.readBoolean());
        session.setEhloBinary(input.readBoolean());
        session.setEhloBdat(input.readBoolean());
        session.setEhloLog(readString(input));
        List<String> ehloAuth = readStringList(input);
        if (ehloAuth != null) {
            session.setEhloAuth(ehloAuth);
        }
        session.setTls(input.readBoolean());
        session.setStartTls(input.readBoolean());
        session.setSecurityPolicy(readSecurityPolicy(input));
        session.setSecurePort(input.readBoolean());
        session.setAuthBeforeTls(input.readBoolean());
        session.setAuth(input.readBoolean());
        session.setAuthLoginCombined(input.readBoolean());
        session.setAuthLoginRetry(input.readBoolean());
        session.setUsername(readString(input));
        session.setPassword(readString(input));
        List<String> behaviour = readStringList(input);
        if (behaviour != null) {
            session.setBehaviour(behaviour);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> magic = (Map<String, Object>) readSerializedObject(input);
        if (magic != null) {
            session.getMagic().clear();
            session.getMagic().putAll(magic);
        }
        @SuppressWarnings("unchecked")
        Map<String, List<?>> savedResults = (Map<String, List<?>>) readSerializedObject(input);
        if (savedResults != null) {
            session.getSavedResults().clear();
            session.getSavedResults().putAll(savedResults);
        }

        int envelopeCount = input.readInt();
        if (envelopeCount > 0) {
            for (int i = 0; i < envelopeCount; i++) {
                session.addEnvelope((MessageEnvelope) readSerializedObject(input));
            }
        }

        return session;
    }

    private static void writeSecurityPolicy(DataOutputStream output, SecurityPolicy securityPolicy) throws IOException {
        output.writeBoolean(securityPolicy != null);
        if (securityPolicy == null) {
            return;
        }
        writeEnum(output, securityPolicy.getType());
        writeString(output, securityPolicy.getMxHostname());
        writeString(output, securityPolicy.getMtaStsPolicy());
        List<DaneRecord> daneRecords = securityPolicy.getDaneRecords();
        output.writeInt(daneRecords.size());
        for (DaneRecord record : daneRecords) {
            writeString(output, record.getHostname());
            output.writeInt(record.getUsage());
            output.writeInt(record.getSelector());
            output.writeInt(record.getMatchingType());
            writeString(output, record.getCertificateData());
            writeString(output, record.getTlsaRecord());
        }
    }

    private static SecurityPolicy readSecurityPolicy(DataInputStream input) throws IOException {
        if (!input.readBoolean()) {
            return null;
        }

        SecurityPolicy.PolicyType type = readEnum(input, SecurityPolicy.PolicyType.class);
        String mxHostname = readString(input);
        String mtaStsPolicy = readString(input);
        int daneCount = input.readInt();
        List<DaneRecord> daneRecords = new ArrayList<>(Math.max(0, daneCount));
        for (int i = 0; i < daneCount; i++) {
            daneRecords.add(new DaneRecord(
                    readString(input),
                    input.readInt(),
                    input.readInt(),
                    input.readInt(),
                    readString(input),
                    readString(input)
            ));
        }

        if (type == SecurityPolicy.PolicyType.DANE) {
            return SecurityPolicy.dane(mxHostname, daneRecords);
        }
        if (type == SecurityPolicy.PolicyType.MTA_STS) {
            return SecurityPolicy.mtaSts(mxHostname, mtaStsPolicy);
        }
        return SecurityPolicy.opportunistic(mxHostname);
    }

    private static void writeSerializedObject(DataOutputStream output, Object value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }

        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream objectOutput = new ObjectOutputStream(bos)) {
            objectOutput.writeObject(value);
            objectOutput.flush();
            byte[] bytes = bos.toByteArray();
            output.writeInt(bytes.length);
            output.write(bytes);
        }
    }

    private static Object readSerializedObject(DataInputStream input) throws IOException, ClassNotFoundException {
        int length = input.readInt();
        if (length < 0) {
            return null;
        }

        byte[] bytes = input.readNBytes(length);
        try (ObjectInputStream objectInput = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return objectInput.readObject();
        }
    }

    private static void writeStringArray(DataOutputStream output, String[] values) throws IOException {
        if (values == null) {
            output.writeInt(-1);
            return;
        }
        output.writeInt(values.length);
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static String[] readStringArray(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0) {
            return null;
        }
        String[] values = new String[size];
        for (int i = 0; i < size; i++) {
            values[i] = readString(input);
        }
        return values;
    }

    private static void writeStringList(DataOutputStream output, List<String> values) throws IOException {
        if (values == null) {
            output.writeInt(-1);
            return;
        }
        output.writeInt(values.size());
        for (String value : values) {
            writeString(output, value);
        }
    }

    private static List<String> readStringList(DataInputStream input) throws IOException {
        int size = input.readInt();
        if (size < 0) {
            return null;
        }
        List<String> values = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            values.add(readString(input));
        }
        return values;
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        if (value == null) {
            output.writeInt(-1);
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = input.readNBytes(length);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeEnum(DataOutputStream output, Enum<?> value) throws IOException {
        writeString(output, value != null ? value.name() : null);
    }

    private static <E extends Enum<E>> E readEnum(DataInputStream input, Class<E> enumClass) throws IOException {
        String name = readString(input);
        return name == null ? null : Enum.valueOf(enumClass, name);
    }
}
