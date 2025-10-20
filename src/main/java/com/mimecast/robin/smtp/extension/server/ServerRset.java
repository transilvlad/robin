package com.mimecast.robin.smtp.extension.server;

import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.verb.Verb;

import java.io.IOException;

/**
 * RSET extension processor.
 */
public class ServerRset extends ServerProcessor {

    /**
     * RSET processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        connection.write(SmtpResponses.ALL_CLEAR_250);
        connection.reset();

        return true;
    }
}
