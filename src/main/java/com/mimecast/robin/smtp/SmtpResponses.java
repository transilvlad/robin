package com.mimecast.robin.smtp;

/**
 * SMTP response code constants.
 *
 * <p>This class contains all SMTP response codes and messages used throughout the application.
 * Responses are organized by category: success codes (2xx), authentication codes (3xx),
 * temporary failure codes (4xx), and permanent failure codes (5xx).
 */
public final class SmtpResponses {

    private SmtpResponses() {
        // Utility class.
    }

    // ========== 2xx Success Codes ==========

    /**
     * 214 Help message.
     */
    public static final String HELP_214 = "214 ";

    /**
     * 220 Service ready - initial greeting.
     */
    public static final String GREETING_220 = "220 %s Robin ready at %s with ESMTP; %s";

    /**
     * 220 Ready for TLS handshake.
     */
    public static final String READY_HANDSHAKE_220 = "220 Ready for handshake [%s]";

    /**
     * 220 XCLIENT response with ESMTP.
     */
    public static final String XCLIENT_ESMTP_220 = "220 %s ESMTP; %s";

    /**
     * 221 Closing connection.
     */
    public static final String CLOSING_221 = "221 2.0.0 Closing connection";

    /**
     * 235 Authentication successful.
     */
    public static final String AUTH_SUCCESS_235 = "235 2.7.0 Authorized";

    /**
     * 250 Welcome message for single-line response.
     */
    public static final String WELCOME_250 = "250 ";

    /**
     * 250 Welcome message prefix for multi-line EHLO response.
     */
    public static final String WELCOME_250_MULTILINE = "250-";

    /**
     * 250 Extension advertisement separator (multi-line).
     */
    public static final String ADVERT_250_MULTILINE = "250-";

    /**
     * 250 Extension advertisement separator (last line).
     */
    public static final String ADVERT_250_LAST = "250 ";

    /**
     * 250 Sender OK.
     */
    public static final String SENDER_OK_250 = "250 2.1.0 Sender OK [%s]";

    /**
     * 250 Recipient OK.
     */
    public static final String RECIPIENT_OK_250 = "250 2.1.5 Recipient OK [%s]";

    /**
     * 250 All clear (RSET response).
     */
    public static final String ALL_CLEAR_250 = "250 2.1.5 All clear";

    /**
     * 250 Received OK (DATA response).
     */
    public static final String RECEIVED_OK_250 = "250 2.0.0 Received OK [%s]";

    /**
     * 250 Chunk OK (BDAT response).
     */
    public static final String CHUNK_OK_250 = "250 2.0.0 Chunk OK [%s]";

    /**
     * 250 Chunk OK (BDAT response).
     */
    public static final String DOVECOT_LDA_SUCCESS_250 = "250 Dovecot-LDA delivery successful";

    // ========== 3xx Intermediate Codes ==========

    /**
     * 334 AUTH challenge - Payload.
     */
    public static final String AUTH_PAYLOAD_334 = "334 UGF5bG9hZDo";

    /**
     * 334 AUTH challenge - Username.
     */
    public static final String AUTH_USERNAME_334 = "334 VXNlcm5hbWU6";

    /**
     * 334 AUTH challenge - Password.
     */
    public static final String AUTH_PASSWORD_334 = "334 UGFzc3dvcmQ6";

    /**
     * 354 Start mail input.
     */
    public static final String READY_WILLING_354 = "354 Ready and willing";

    // ========== 4xx Temporary Failure Codes ==========

    /**
     * 451 Internal server error.
     */
    public static final String INTERNAL_ERROR_451 = "451 4.3.2 Internal server error [%s]";

    // ========== 5xx Permanent Failure Codes ==========

    /**
     * 500 Syntax error.
     */
    public static final String SYNTAX_ERROR_500 = "500 Syntax error";

    /**
     * 500 Unrecognized command.
     */
    public static final String UNRECOGNIZED_CMD_500 = "500 5.3.3 Unrecognized command";

    /**
     * 501 Invalid arguments.
     */
    public static final String INVALID_ARGS_501 = "501 5.5.4 Invalid arguments";

    /**
     * 504 Unrecognized authentication mechanism.
     */
    public static final String UNRECOGNIZED_AUTH_504 = "504 5.7.4 Unrecognized authentication mechanism";

    /**
     * 530 Authentication required to relay mail.
     */
    public static final String AUTH_REQUIRED_530 = "530 5.7.57 Authentication required to relay mail [%s]";

    /**
     * 535 Authentication failed.
     */
    public static final String AUTH_FAILED_535 = "535 5.7.1 Unauthorized";

    /**
     * 538 Authentication not supported.
     */
    public static final String AUTH_NOT_SUPPORTED_538 = "538 5.7.1 Authentication not supported";

    /**
     * 538 Connection not secured.
     */
    public static final String CONNECTION_NOT_SECURED_538 = "538 5.7.1 Connection not secured";

    /**
     * 550 Unknown destination mailbox address.
     */
    public static final String UNKNOWN_MAILBOX_550 = "550 5.1.1 Unknown destination mailbox address [%s]";

    /**
     * 550 Dovecot-LDA delivery failed.
     */
    public static final String DOVECOT_LDA_FAILED_550 = "550 Dovecot-LDA delivery failed";

    /**
     * 554 No valid recipients.
     */
    public static final String NO_VALID_RECIPIENTS_554 = "554 5.5.1 No valid recipients [%s]";
}
