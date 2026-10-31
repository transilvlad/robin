package com.mimecast.robin.sasl;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RecipientDirectoryTest {
    private final FakeDirectory data = new FakeDirectory();

    @Test
    void expandsAllBranchesDeduplicatesAndUsesAnExplicitMailboxCopy() throws Exception {
        data.mailboxes.add("team@example.com");
        data.aliases.put("team@example.com", List.of("team@example.com", "alice@example.com",
                "group@example.com", "external@example.net"));
        data.aliases.put("group@example.com", List.of("alice@example.com", "bob@example.com"));
        assertEquals(Map.of("team@example.com", true, "alice@example.com", true, "bob@example.com", true,
                "external@example.net", false), router(true, 10, 100).resolve(" TEAM@EXAMPLE.COM ", true));
    }

    @Test
    void mailboxAliasOverridesImplicitCopyAndSelfEdgeRequiresARealMailbox() throws Exception {
        data.aliases.put("alice@example.com", List.of("bob@example.com"));
        assertEquals(Map.of("bob@example.com", true), router(false, 10, 100).resolve("alice@example.com", true));
        data.aliases.put("missing@example.com", List.of("missing@example.com"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(false, 10, 100).resolve("missing@example.com", true));
    }

    @Test
    void plusAddressingIsOptInAndExactAliasesAndMailboxesWin() throws Exception {
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(false, 10, 100).resolve("alice+tag@example.com", true));
        data.domains.put("example.com", new RecipientDirectory.DomainPolicy(true, true, null));
        assertEquals(Map.of("alice@example.com", true), router(false, 10, 100).resolve("alice+tag@example.com", true));
        data.aliases.put("alice+tag@example.com", List.of("bob@example.com"));
        assertEquals(Map.of("bob@example.com", true), router(false, 10, 100).resolve("alice+tag@example.com", true));
        data.aliases.clear();
        data.mailboxes.add("alice+tag@example.com");
        assertEquals(Map.of("alice+tag@example.com", true), router(false, 10, 100).resolve("alice+tag@example.com", true));
    }

    @Test
    void catchAllIsExplicitAndAlsoSubjectToLoopsAndExternalPolicy() throws Exception {
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("unknown@example.com", true));
        data.domains.put("example.com", new RecipientDirectory.DomainPolicy(true, false, "bob@example.com"));
        assertEquals(Map.of("bob@example.com", true), router(false, 10, 100).resolve("unknown@example.com", true));
        data.domains.put("example.com", new RecipientDirectory.DomainPolicy(true, false, "external@example.net"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(false, 10, 100).resolve("unknown@example.com", true));
        assertEquals(Map.of("external@example.net", false), router(true, 10, 100).resolve("unknown@example.com", true));
    }

    @Test
    void externalRootsRequireSubmissionAndExternalAliasTargetsRequireSeparateOptIn() throws Exception {
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("external@example.net", true));
        assertEquals(Map.of("external@example.net", false), router(false, 10, 100).resolve("external@example.net", false));
        data.aliases.put("team@example.com", List.of("external@example.net"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(false, 10, 100).resolve("team@example.com", true));
    }

    @Test
    void disabledDomainNeverBecomesAnExternalForwardOrSubmissionTarget() {
        data.domains.put("blocked.example.com", new RecipientDirectory.DomainPolicy(false, false, null));
        data.aliases.put("team@example.com", List.of("alice@example.com", "target@blocked.example.com"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("team@example.com", true));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("target@blocked.example.com", false));
    }

    @Test
    void loopDepthAndFanoutFailuresReturnNoPartialExpansion() {
        data.aliases.put("team@example.com", List.of("alice@example.com", "group@example.com"));
        data.aliases.put("group@example.com", List.of("team@example.com"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("team@example.com", true));
        data.aliases.put("group@example.com", List.of("bob@example.com"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 1, 100).resolve("team@example.com", true));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 1).resolve("team@example.com", true));
    }

    @Test
    void malformedTargetsAndDatabaseOutagesAreNotUnknownMailboxOrSuccess() {
        data.aliases.put("team@example.com", List.of("alice@example.com", "not an address"));
        assertThrows(RecipientDirectory.RoutingException.class,
                () -> router(true, 10, 100).resolve("team@example.com", true));
        data.fail = true;
        assertThrows(IllegalStateException.class,
                () -> router(true, 10, 100).resolve("alice@example.com", true));
    }

    private RecipientDirectory router(boolean external, int depth, int recipients) {
        return new RecipientDirectory(data, external, depth, recipients);
    }

    private static final class FakeDirectory implements RecipientDirectory.Lookup {
        final Map<String, RecipientDirectory.DomainPolicy> domains = new HashMap<>(Map.of("example.com",
                new RecipientDirectory.DomainPolicy(true, false, null)));
        final Map<String, List<String>> aliases = new HashMap<>();
        final Set<String> mailboxes = new HashSet<>(Set.of("alice@example.com", "bob@example.com"));
        boolean fail;
        @Override public Optional<RecipientDirectory.DomainPolicy> findDomain(String domain) {
            if (fail) throw new IllegalStateException("Synthetic directory outage");
            return Optional.ofNullable(domains.get(domain));
        }
        @Override public List<String> findAliases(String address) { return aliases.getOrDefault(address, List.of()); }
        @Override public boolean hasMailbox(String address) { return mailboxes.contains(address); }
    }
}
