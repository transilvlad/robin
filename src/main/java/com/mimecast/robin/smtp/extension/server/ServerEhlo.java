package com.mimecast.robin.smtp.extension.server;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.mimecast.robin.config.server.ScenarioConfig;
import com.mimecast.robin.main.Config;
import com.mimecast.robin.main.Extensions;
import com.mimecast.robin.smtp.SmtpResponses;
import com.mimecast.robin.smtp.connection.Connection;
import com.mimecast.robin.smtp.extension.Extension;
import com.mimecast.robin.smtp.security.BlackholeMatcher;
import com.mimecast.robin.smtp.verb.EhloVerb;
import com.mimecast.robin.smtp.verb.Verb;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * EHLO extension processor.
 */
public class ServerEhlo extends ServerProcessor {

    /**
     * EHLO processor.
     *
     * @param connection Connection instance.
     * @param verb       Verb instance.
     * @return Boolean.
     * @throws IOException Unable to communicate.
     */
    @Override
    public boolean process(Connection connection, Verb verb) throws IOException {
        super.process(connection, verb);

        EhloVerb ehloVerb = new EhloVerb(verb);
        connection.getSession().setEhlo(ehloVerb.getDomain());

        // Check if session should be blackholed based on IP and EHLO
        if (BlackholeMatcher.shouldBlackhole(
                connection.getSession().getFriendAddr(),
                ehloVerb.getDomain(),
                null,
                null,
                Config.getServer().getBlackholeConfig())) {
            connection.getSession().setBlackholed(true);
        }

        // Prepare welcome message.
        String welcome = "Welcome [" + connection.getSession().getFriendRdns() + " (" + connection.getSession().getFriendAddr() + ")]";

        // ScenarioConfig response.
        Optional<ScenarioConfig> opt = connection.getScenario();
        if ("helo".equals(verb.getKey()) && opt.isPresent() && opt.get().getHelo() != null) {
            connection.write(opt.get().getHelo());
        } else if ("lhlo".equals(verb.getKey()) && opt.isPresent() && opt.get().getLhlo() != null) {
            connection.write(opt.get().getHelo());
        } else if ("ehlo".equals(verb.getKey()) && opt.isPresent() && opt.get().getEhlo() != null) {
            connection.write(opt.get().getEhlo());
        }

        // HELO/LHLO response.
        else if (verb.getKey().equals("helo") || verb.getKey().equals("lhlo")) {
            connection.write(SmtpResponses.WELCOME_250 + welcome);
        }

        // EHLO response.
        else {
            connection.write(SmtpResponses.WELCOME_250_MULTILINE + welcome);
            writeAdverts();
        }

        return true;
    }

    /**
     * Writes adverts to socket.
     *
     * @throws IOException Unable to communicate.
     */
    public void writeAdverts() throws IOException {
        List<String> adverts = Lists.newArrayList(Sets.newHashSet(collectAdverts(connection)));
        for (int i = 0; i < adverts.size(); i++) {
            String prefix = (adverts.size() - 1) > i ? SmtpResponses.ADVERT_250_MULTILINE : SmtpResponses.ADVERT_250_LAST;
            connection.write(prefix + adverts.get(i));
        }
    }

    /**
     * Collects adverts from extensions.
     *
     * @param connection Connection instance.
     * @return List of strings.
     */
    @SuppressWarnings("WeakerAccess")
    public static List<String> collectAdverts(Connection connection) {
        List<String> adverts = new ArrayList<>();
        adverts.add("PIPELINING");
        for (String s : Extensions.getExtensions().keySet()) {

            Optional<Extension> ept = Extensions.getExtension(s);
            if (ept.isPresent()) {

                // Don't advertise server AUTH extensions on main inbound port or insecure connections over submission port.
                // Secured ports are fine as the connection is already secured before AUTH is advertised and it's not the main port.
                if (ept.get().getServer() instanceof ServerAuth &&
                        connection.getSession().isInbound() &&
                        !connection.getSession().isSecurePort()) {
                    continue;
                }

                // Collect advert.
                String advert = ept.get().getServer().getAdvert();
                if (StringUtils.isNotBlank(advert)) {
                    adverts.add(advert);
                }
            }
        }

        return adverts;
    }
}
