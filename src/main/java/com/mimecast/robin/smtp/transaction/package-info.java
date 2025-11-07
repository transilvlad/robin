/**
 * Represents a single SMTP transaction, from connection to termination.
 *
 * <p>Every SMTP exchange is a transaction that gets recorded in its place.
 * <br>These can be at session level or envelope level.
 * <br>Session refers to the overall connection while envelope strictly to each message sent.
 * <br>Envelope SMTP extensions are: MAIL, RCPT, DATA, BDAT (also known as CHUNKING extension).
 */
package com.mimecast.robin.smtp.transaction;
