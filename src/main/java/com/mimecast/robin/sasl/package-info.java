/**
 * Implements SASL authentication, including integration with Dovecot.
 *
 * <p>This package contains classes for SASL authentication.
 *
 * <p>It integrates with Dovecot using two separate UNIX domain sockets:
 * <ul>
 *     <li>Authentication (SASL): {@link com.mimecast.robin.sasl.DovecotSaslAuthNative} -> /run/dovecot/auth-client</li>
 *     <li>User existence lookup: {@link com.mimecast.robin.sasl.DovecotUserLookupNative} -> /run/dovecot/auth-userdb</li>
 * </ul>
 *
 * <p>The separation allows lightweight recipient validation (RCPT) without exposing passwords
 * <br>while keeping full SASL AUTH logic independent.
 *
 * @see com.mimecast.robin.sasl.DovecotSaslAuthNative
 * @see com.mimecast.robin.sasl.DovecotUserLookupNative
 */
package com.mimecast.robin.sasl;

