package com.mimecast.robin.smtp.session;

import com.mimecast.robin.config.ConfigMapper;
import com.mimecast.robin.config.assertion.AssertConfig;
import com.mimecast.robin.config.client.CaseConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.smtp.MessageEnvelope;
import com.mimecast.robin.smtp.connection.SmtpFoundation;
import com.mimecast.robin.smtp.transaction.SessionTransactionList;
import com.mimecast.robin.util.Magic;
import org.apache.logging.log4j.ThreadContext;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Session.
 *
 * <p>This is the primary container for session data.
 */
@SuppressWarnings({"UnusedReturnValue", "rawtypes"})
public class Session implements Serializable, Cloneable {

    /**
     * Direction enum.
     */
    public enum Direction {
        INBOUND,
        OUTBOUND
    }

    /**
     * Session direction.
     * <p>Default: INBOUND.
     */
    private Direction direction = Direction.INBOUND;

    /**
     * UID.
     */
    private String uid = UUID.randomUUID().toString();

    /**
     * Current RFC 2822 compliant date.
     */
    private String date;

    /**
     * Supported TLS protocols.
     */
    private String[] protocols;

    /**
     * Supported TLS ciphers.
     */
    private String[] ciphers;

    /**
     * [Client] Retry count.
     */
    private int retry;

    /**
     * [Client] Delay in seconds.
     */
    private int delay;

    /**
     * [Client] Socket timeout in milliseconds.
     */
    private int timeout = SmtpFoundation.DEFAULTTIMEOUT;

    /**
     * [Client] Extended socket timeout in milliseconds.
     */
    private int extendedtimeout = SmtpFoundation.EXTENDEDTIMEOUT;

    /**
     * [Client] Connect socket timeout in milliseconds.
     */
    private int connectTimeout = SmtpFoundation.DEFAULTTIMEOUT;

    /**
     * [Server] Bind interface.
     */
    private String bind;

    /**
     * [Client] Destination MX.
     */
    private List<String> mx;

    /**
     * [Client] Destination port.
     */
    private int port;

    /**
     * Own rDNS.
     */
    private String rdns;

    /**
     * Own IP address.
     */
    private String addr;

    /**
     * Remote rDNS.
     */
    private String friendRdns;

    /**
     * Remote IP address.
     */
    private String friendAddr;

    /**
     * [Client] HELO domain.
     */
    private String helo = "";

    /**
     * [Client] LHLO domain.
     */
    private String lhlo = "";

    /**
     * [Client] EHLO domain.
     */
    private String ehlo = "";

    /**
     * [Client] EHLO advertised size.
     */
    private long ehloSize = -1;

    /**
     * [Client] EHLO advertised STARTTLS.
     */
    private boolean ehloTls = false;

    /**
     * [Client] EHLO advertised SMTPUTF8.
     */
    private boolean smtpUtf8 = false;

    /**
     * [Client] EHLO advertised 8BITMIME.
     */
    private boolean ehlo8bit = false;

    /**
     * [Client] EHLO advertised BINARYMIME.
     */
    private boolean ehloBinary = false;

    /**
     * [Client] EHLO advertised CHUNKING.
     */
    private boolean ehloBdat = false;

    /**
     * [Client] EHLO advertised CHUNKING.
     */
    private String ehloLog = "EHLO";

    /**
     * [Client] EHLO advertised authentication mechanisms.
     */
    private List<String> ehloAuth = new ArrayList<>();

    /**
     * Is TLS enabled.
     */
    private boolean tls = false;

    /**
     * TLS result.
     */
    private boolean startTls = false;

    /**
     * [Server] Is secure port.
     * <p>This supports submission unlike main port.
     */
    private boolean securePort = false;

    /**
     * [Client] Do auth before TLS.
     */
    private boolean authBeforeTls = false;

    /**
     * [Client] Authentication enabled.
     */
    private boolean auth = false;

    /**
     * [Client] AUTH LOGIN combined username and password login enabled.
     */
    private boolean authLoginCombined = false;

    /**
     * [Client] AUTH LOGIN retry enabled.
     */
    private boolean authLoginRetry = false;

    /**
     * Authentication username.
     */
    private String username = "";

    /**
     * Authentication password.
     */
    private String password = "";

    /**
     * List of verbs to call in order.
     */
    private List<String> behaviour = new ArrayList<>();

    /**
     * List of envelopes.
     */
    private List<MessageEnvelope> envelopes = new ArrayList<>();

    /**
     * SessionTransactionList instance.
     */
    private SessionTransactionList sessionTransactionList = new SessionTransactionList();

    /**
     * AssertConfig.
     */
    private AssertConfig assertConfig = new AssertConfig();

    /**
     * List of magic variables.
     * <p>Handy place to store external data for reuse.
     */
    private final Map<String, Object> magic = new HashMap<>();

    /**
     * Saved results.
     */
    private final Map<String, List<?>> savedResults = new HashMap<>();

    /**
     * Constructs a new Session instance.
     */
    public Session() {
        ThreadContext.put("aCode", uid);

        Magic.putMagic(this);
        setDate();
    }

    /**
     * Maps CaseConfig to this session.
     *
     * @param caseConfig CaseConfig instance.
     */
    public void map(CaseConfig caseConfig) {
        new ConfigMapper(caseConfig).mapTo(this);
    }

    /**
     * Sets direction.
     *
     * @param direction Enum.
     * @return Self.
     */
    public Session setDirection(Direction direction) {
        this.direction = direction;
        return this;
    }

    /**
     * Gets direction.
     *
     * @return Enum.
     */
    public Direction getDirection() {
        return direction;
    }

    /**
     * Gets inbound direction.
     *
     * @return Boolean.
     */
    public boolean isInbound() {
        return direction == Direction.INBOUND;
    }

    /**
     * Gets outbound direction.
     *
     * @return Boolean.
     */
    public boolean isOutbound() {
        return direction == Direction.OUTBOUND;
    }

    /**
     * Sets UID.
     *
     * @return Self.
     */
    public Session setUID(String uid) {
        this.uid = uid;
        return this;
    }

    /**
     * Gets UID.
     *
     * @return String.
     */
    public String getUID() {
        return uid;
    }

    /**
     * Sets the date.
     */
    private void setDate() {
        this.date = new SimpleDateFormat("E, d MMM yyyy HH:mm:ss Z", Config.getProperties().getLocale()).format(new Date());
    }

    /**
     * Gets the date.
     *
     * @return Date.
     */
    public String getDate() {
        return date;
    }

    /**
     * Sets supported protocols.
     *
     * @param protocols Protocols list.
     * @return Self.
     */
    public Session setProtocols(String[] protocols) {
        if (protocols != null && protocols.length > 0) {
            this.protocols = protocols;
        }
        return this;
    }

    /**
     * Gets supported protocols.
     *
     * @return Protocols list.
     */
    public String[] getProtocols() {
        return protocols;
    }

    /**
     * Sets supported protocols.
     *
     * @param ciphers Ciphers list.
     * @return Self.
     */
    public Session setCiphers(String[] ciphers) {
        if (ciphers != null && ciphers.length > 0) {
            this.ciphers = ciphers;
        }
        return this;
    }

    /**
     * Gets supported ciphers.
     *
     * @return Ciphers list.
     */
    public String[] getCiphers() {
        return ciphers;
    }

    /**
     * Gets retry count.
     *
     * @return Port number.
     */
    public int getRetry() {
        return retry;
    }

    /**
     * Sets retry count.
     *
     * @param retry Retry count.
     * @return Self.
     */
    public Session setRetry(int retry) {
        this.retry = retry;
        return this;
    }

    /**
     * Gets retry delay in seconds.
     *
     * @return Retry delay.
     */
    public int getDelay() {
        return delay;
    }

    /**
     * Sets retry delay in seconds.
     *
     * @param delay Retry delay.
     * @return Self.
     */
    public Session setDelay(int delay) {
        this.delay = delay;
        return this;
    }

    /**
     * Gets socket timeout in milliseconds.
     *
     * @return Socket timeout in milliseconds.
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Sets socket timeout in milliseconds.
     *
     * @param timeout Socket timeout in milliseconds.
     * @return Self.
     */
    public Session setTimeout(int timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Gets extended socket timeout in milliseconds.
     * <p>Used for extendedRead().
     * <p>Handy for SMTP DATA and BDAT extensions.
     *
     * @return Socket timeout in milliseconds.
     */
    public int getExtendedTimeout() {
        return extendedtimeout;
    }

    /**
     * Sets extended socket timeout in milliseconds.
     * <p>In seconds.
     *
     * @param extendedtimeout Socket timeout in milliseconds.
     * @return Self.
     */
    public Session setExtendedTimeout(int extendedtimeout) {
        this.extendedtimeout = extendedtimeout;
        return this;
    }

    /**
     * Gets connect socket timeout in milliseconds.
     * <p>Used for initial connection.
     *
     * @return Socket timeout in milliseconds.
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets connect socket timeout in milliseconds.
     * <p>In seconds.
     *
     * @param connectTimeout Socket timeout in milliseconds.
     * @return Self.
     */
    public Session setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * Gets bind interface.
     *
     * @return Interface.
     */
    public String getBind() {
        return bind;
    }

    /**
     * Sets bind interface.
     *
     * @param bind Bind interface.
     * @return Self.
     */
    public Session setBind(String bind) {
        this.bind = bind;
        return this;
    }

    /**
     * Gets server MX.
     *
     * @return Address or IP list of string.
     */
    public List<String> getMx() {
        return mx;
    }

    /**
     * Sets server MX.
     *
     * @param mx MX list of string.
     * @return Self.
     */
    public Session setMx(List<String> mx) {
        this.mx = mx;
        return this;
    }

    /**
     * Gets port number.
     *
     * @return Port number.
     */
    public int getPort() {
        return port;
    }

    /**
     * Sets port number.
     *
     * @param port Port number.
     * @return Self.
     */
    public Session setPort(int port) {
        this.port = port;
        return this;
    }

    /**
     * Gets Reverse DNS.
     *
     * @return Reverse DNS string.
     */
    public String getRdns() {
        return rdns;
    }

    /**
     * Sets Reverse DNS.
     *
     * @param rdns Reverse DNS string.
     * @return Self.
     */
    public Session setRdns(String rdns) {
        this.rdns = rdns;
        return this;
    }

    /**
     * Gets own IP address.
     *
     * @return IP address.
     */
    public String getAddr() {
        return addr;
    }

    /**
     * Sets own IP address.
     *
     * @param addr IP address.
     * @return Self.
     */
    public Session setAddr(String addr) {
        this.addr = addr;
        return this;
    }

    /**
     * Gets remote rDNS.
     *
     * @return Remote rDNS.
     */
    public String getFriendRdns() {
        return friendRdns;
    }

    /**
     * Sets remote rDNS.
     *
     * @param friendRdns Remote rDNS string.
     * @return Self.
     */
    public Session setFriendRdns(String friendRdns) {
        this.friendRdns = friendRdns;
        return this;
    }

    /**
     * Gets remote IP address.
     *
     * @return IP address.
     */
    public String getFriendAddr() {
        return friendAddr;
    }

    /**
     * Sets remote IP address.
     *
     * @param friendAddr Remote IP address string.
     * @return Self.
     */
    public Session setFriendAddr(String friendAddr) {
        this.friendAddr = friendAddr;
        return this;
    }

    /**
     * Gets HELO domain.
     *
     * @return Domain.
     */
    public String getHelo() {
        return helo;
    }

    /**
     * Sets HELO domain.
     *
     * @param helo HELO domain.
     * @return Self.
     */
    public Session setHelo(String helo) {
        this.helo = helo;
        return this;
    }

    /**
     * Gets LHLO domain.
     *
     * @return Domain.
     */
    public String getLhlo() {
        return lhlo;
    }

    /**
     * Sets LHLO domain.
     *
     * @param lhlo LHLO domain.
     * @return Self.
     */
    public Session setLhlo(String lhlo) {
        this.lhlo = lhlo;
        return this;
    }

    /**
     * Gets EHLO domain.
     *
     * @return Domain.
     */
    public String getEhlo() {
        return ehlo;
    }

    /**
     * Sets EHLO domain.
     *
     * @param ehlo EHLO domain.
     * @return Self.
     */
    public Session setEhlo(String ehlo) {
        this.ehlo = ehlo;
        return this;
    }

    /**
     * Gets EHLO advertised size.
     *
     * @return Size.
     */
    public Long getEhloSize() {
        return ehloSize;
    }

    /**
     * Sets EHLO advertised size.
     *
     * @param ehloSize EHLO size long.
     * @return Self.
     */
    public Session setEhloSize(long ehloSize) {
        this.ehloSize = ehloSize;
        return this;
    }

    /**
     * Gets EHLO advertised STARTTLS.
     *
     * @return TLS enablement.
     */
    public boolean isEhloTls() {
        return ehloTls;
    }

    /**
     * Sets EHLO advertised STARTTLS.
     *
     * @param ehloTls EHLO TLS boolean.
     * @return Self.
     */
    public Session setEhloTls(boolean ehloTls) {
        this.ehloTls = ehloTls;
        return this;
    }

    /**
     * Gets EHLO advertised SMTPUTF8.
     *
     * @return Self.
     */
    public boolean isSmtpUtf8() {
        return smtpUtf8;
    }

    /**
     * Sets EHLO advertised SMTPUTF8.
     *
     * @param smtpUtf8 EHLO UTF-8 boolean.
     * @return Self.
     */
    public Session setSmtpUtf8(boolean smtpUtf8) {
        this.smtpUtf8 = smtpUtf8;
        return this;
    }

    /**
     * Gets EHLO advertised 8BITMIME.
     *
     * @return Self.
     */
    public boolean isEhlo8bit() {
        return ehlo8bit;
    }

    /**
     * Sets EHLO advertised 8BITMIME.
     *
     * @param ehlo8bit EHLO 8bit boolean.
     * @return Self.
     */
    public Session setEhlo8bit(boolean ehlo8bit) {
        this.ehlo8bit = ehlo8bit;
        return this;
    }

    /**
     * Gets EHLO advertised BINARYMIME.
     *
     * @return Self.
     */
    public boolean isEhloBinary() {
        return ehloBinary;
    }

    /**
     * Sets EHLO advertised BINARYMIME.
     *
     * @param ehloBinary EHLO binary boolean.
     * @return Self.
     */
    public Session setEhloBinary(boolean ehloBinary) {
        this.ehloBinary = ehloBinary;
        return this;
    }

    /**
     * Gets EHLO advertised CHUNKING.
     *
     * @return BDAT enablement.
     */
    public boolean isEhloBdat() {
        return ehloBdat;
    }

    /**
     * Sets EHLO advertised CHUNKING.
     *
     * @param ehloBdat EHLO CHUNKING boolean.
     * @return Self.
     */
    public Session setEhloBdat(boolean ehloBdat) {
        this.ehloBdat = ehloBdat;
        return this;
    }

    /**
     * Gets EHLO advertised authentication mechanisms.
     *
     * @return Advertised authentication mechanisms.
     */
    public List<String> getEhloAuth() {
        return ehloAuth;
    }

    /**
     * Sets EHLO logging short code.
     *
     * @param ehloLog EHLO logging short code string.
     * @return Self.
     */
    public Session setEhloLog(String ehloLog) {
        this.ehloLog = ehloLog != null ? ehloLog : this.ehloLog;
        return this;
    }

    /**
     * Gets EHLO logging short code.
     *
     * @return String.
     */
    public String getEhloLog() {
        return ehloLog;
    }

    /**
     * Sets EHLO advertised authentication mechanisms.
     *
     * @param ehloAuth EHLO AUTH list of strings.
     * @return Self.
     */
    public Session setEhloAuth(List<String> ehloAuth) {
        this.ehloAuth = ehloAuth;
        return this;
    }

    /**
     * Gets TLS enablement.
     *
     * @return TLS enabled.
     */
    public boolean isTls() {
        return tls;
    }

    /**
     * Sets TLS enablement.
     *
     * @param tls Enablement.
     * @return Self.
     */
    public Session setTls(boolean tls) {
        this.tls = tls;
        return this;
    }

    /**
     * Gets TLS result.
     *
     * @return TLS result.
     */
    public boolean isStartTls() {
        return startTls;
    }

    /**
     * Sets TLS result.
     *
     * @param startTls TLS result.
     * @return Self.
     */
    public Session setStartTls(boolean startTls) {
        this.startTls = startTls;
        return this;
    }

    /**
     * Gets secure port enablement.
     *
     * @return Secure port enablement.
     */
    public boolean isSecurePort() {
        return securePort;
    }

    /**
     * Sets secure port enablement.
     *
     * @param securePort Secure port enablement.
     * @return Self.
     */
    public Session setSecurePort(boolean securePort) {
        this.securePort = securePort;
        return this;
    }

    /**
     * Gets AUTH before TLS enablement.
     *
     * @return AUTH before TLS enablement.
     */
    public boolean isAuthBeforeTls() {
        return authBeforeTls;
    }

    /**
     * Sets AUTH before TLS enablement.
     *
     * @param authBeforeTls AUTH before TLS enablement.
     * @return Self.
     */
    public Session setAuthBeforeTls(boolean authBeforeTls) {
        this.authBeforeTls = authBeforeTls;
        return this;
    }

    /**
     * Is authentication enabled.
     *
     * @return AUTH enablement.
     */
    public boolean isAuth() {
        return auth;
    }

    /**
     * Sets authentication enablement.
     *
     * @param auth AUTH enablement.
     * @return Self.
     */
    public Session setAuth(boolean auth) {
        this.auth = auth;
        return this;
    }

    /**
     * Is AUTH LOGIN combined username and password login enabled.
     *
     * @return Combined enablement.
     */
    public boolean isAuthLoginCombined() {
        return authLoginCombined;
    }

    /**
     * Sets AUTH LOGIN combined username and password login enablement.
     *
     * @param authLoginCombined Combined enablement.
     * @return Self.
     */
    public Session setAuthLoginCombined(boolean authLoginCombined) {
        this.authLoginCombined = authLoginCombined;
        return this;
    }

    /**
     * Is AUTH LOGIN retry enabled.
     *
     * @return Retry enablement.
     */
    public boolean isAuthLoginRetry() {
        return authLoginRetry;
    }

    /**
     * Sets AUTH LOGIN retry enablement.
     *
     * @param authLoginRetry Retry enablement.
     * @return Self.
     */
    public Session setAuthLoginRetry(boolean authLoginRetry) {
        this.authLoginRetry = authLoginRetry;
        return this;
    }

    /**
     * Gets authentication username.
     *
     * @return Username.
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets authentication username.
     *
     * @param username Username string.
     * @return Self.
     */
    public Session setUsername(String username) {
        this.username = username;
        return this;
    }

    /**
     * Gets authentication password.
     *
     * @return Password.
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets authentication password.
     *
     * @param password Password string.
     * @return Self.
     */
    public Session setPassword(String password) {
        this.password = password;
        return this;
    }

    /**
     * Gets list of behaviour.
     *
     * @return String list.
     */
    public List<String> getBehaviour() {
        return behaviour;
    }

    /**
     * Sets behaviour list.
     *
     * @param list List of String.
     * @return Self.
     */
    public Session setBehaviour(List<String> list) {
        behaviour.addAll(list);
        return this;
    }

    /**
     * Gets list of envelopes.
     *
     * @return MessageEnvelope list.
     */
    public List<MessageEnvelope> getEnvelopes() {
        return envelopes;
    }

    /**
     * Adds envelope to list.
     *
     * @param envelope MessageEnvelope instance.
     * @return Self.
     */
    public Session addEnvelope(MessageEnvelope envelope) {
        envelopes.add(envelope);
        return this;
    }

    /**
     * Gets SessionTransactionList instance.
     *
     * @return SessionTransactionList instance.
     */
    public SessionTransactionList getSessionTransactionList() {
        return sessionTransactionList;
    }

    /**
     * Gets assertion config.
     *
     * @return AssertConfig instance.
     */
    public AssertConfig getAssertions() {
        return assertConfig;
    }

    /**
     * Adds assertion config.
     *
     * @param assertConfig AssertConfig instance.
     * @return Self.
     */
    public Session addAssertions(AssertConfig assertConfig) {
        this.assertConfig = assertConfig;
        return this;
    }

    /**
     * Has magic key.
     *
     * @param key Magic key.
     * @return Self.
     */
    public boolean hasMagic(String key) {
        return magic.containsKey(key);
    }

    /**
     * Gets magic.
     *
     * @return Map of String, Object.
     */
    public Map<String, Object> getMagic() {
        return magic;
    }

    /**
     * Gets magic by key.
     *
     * @param key Magic key.
     * @return Object.
     */
    public Object getMagic(String key) {
        return magic.get(key);
    }

    /**
     * Puts magic by key.
     *
     * @param key   Magic key.
     * @param value Magic value of String or List of Strings.
     * @return Self.
     */
    public Session putMagic(String key, Object value) {
        magic.put(key, value);
        return this;
    }

    /**
     * Saves results.
     *
     * @param key     Save name.
     * @param results Results.
     */
    public void saveResults(String key, List results) {
        savedResults.put(key, results);
    }

    /**
     * Gets saved results.
     *
     * @return Map of String, List.
     */
    public Map<String, List<?>> getSavedResults() {
        return savedResults;
    }

    /**
     * Creates a copy of this Session.
     * <p>Uses Object.clone() for a field-by-field copy, then deep-copies
     * mutable arrays and non-final collections that would otherwise be shared.
     * Final collections and objects without a clone implementation are left as-is.
     *
     * @return A cloned Session instance.
     */
    @Override
    public Session clone() {
        try {
            Session clone = (Session) super.clone();

            // Assign new UID.
            clone.setUID(UUID.randomUUID().toString());

            // Deep copy arrays.
            if (this.protocols != null) {
                clone.protocols = this.protocols.clone();
            }
            if (this.ciphers != null) {
                clone.ciphers = this.ciphers.clone();
            }

            // Copy non-final lists to new instances to avoid sharing.
            if (this.mx != null) {
                clone.mx = new ArrayList<>(this.mx);
            }
            if (this.ehloAuth != null) {
                clone.ehloAuth = new ArrayList<>(this.ehloAuth);
            }
            if (this.behaviour != null) {
                clone.behaviour = new ArrayList<>(this.behaviour);
            }

            // Deep clone envelopes and sessionTransactionList.
            if (this.envelopes != null) {
                clone.envelopes = new ArrayList<>();
                this.envelopes.forEach(env -> clone.envelopes.add(env != null ? env.clone() : null));
            }
            if (this.sessionTransactionList != null) {
                clone.sessionTransactionList = sessionTransactionList.clone();
            }

            // Note: magic and savedResults are final and thus remain shared references after a shallow clone.
            // This is acceptable as they are intended for cross-component data sharing.
            // They will however be copied when dequeued from the persistent cache which always creates new instances.

            return clone;
        } catch (CloneNotSupportedException e) {
            throw new AssertionError("Clone should be supported", e);
        }
    }
}
