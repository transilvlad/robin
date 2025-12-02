/**
 * Implements SASL authentication, including integration with Dovecot and optional SQL backends.
 *
 * <p>This package contains classes for SASL authentication.
 *
 * <p>It integrates with Dovecot using two separate UNIX domain sockets:
 * <ul>
 *     <li>Authentication (SASL): {@link com.mimecast.robin.sasl.DovecotSaslAuthNative} -> /run/dovecot/auth-client</li>
 *     <li>User existence lookup: {@link com.mimecast.robin.sasl.DovecotUserLookupNative} -> /run/dovecot/auth-userdb</li>
 * </ul>
 *
 * <p>Alternatively, a SQL-backed implementation is available for environments that prefer
 * a database-driven user store:
 * <ul>
 *     <li>{@link com.mimecast.robin.sasl.SqlUserLookup} - SQL-based user existence and userdb lookup</li>
 *     <li>{@link com.mimecast.robin.sasl.SqlAuthProvider} - SQL-based authentication using Postgres pgcrypto</li>
 * </ul>
 *
 * <p>The separation allows lightweight recipient validation (RCPT) without exposing passwords
 * <br>while keeping full SASL AUTH logic independent.
 *
 * @see com.mimecast.robin.sasl.DovecotSaslAuthNative
 * @see com.mimecast.robin.sasl.DovecotUserLookupNative
 * @see com.mimecast.robin.sasl.SqlUserLookup
 * @see com.mimecast.robin.sasl.SqlAuthProvider
 */
package com.mimecast.robin.sasl;
