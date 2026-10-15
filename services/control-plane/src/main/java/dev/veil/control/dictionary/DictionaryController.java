package dev.veil.control.dictionary;

import dev.veil.contracts.OrgConfig;
import dev.veil.control.config.Caller;
import dev.veil.control.publish.AdminAudit;
import dev.veil.control.publish.ConfigPublisher;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Company-specific knowledge: dictionary terms (codenames, client names, hostnames) and example sentences that
 * teach the semantic detector new confidential topics. Both replace the whole list, like a settings file.
 */
@RestController
@RequestMapping("/api")
public class DictionaryController {

    private static final int MAX_TERMS = 10_000;
    private static final int MAX_EXAMPLES = 200;

    private final JdbcClient jdbc;
    private final ConfigPublisher publisher;
    private final AdminAudit audit;

    public DictionaryController(JdbcClient jdbc, ConfigPublisher publisher, AdminAudit audit) {
        this.jdbc = jdbc;
        this.publisher = publisher;
        this.audit = audit;
    }

    @GetMapping("/dictionary")
    public List<OrgConfig.DictionaryTerm> terms(HttpServletRequest req) {
        return jdbc.sql("SELECT term, label FROM dictionary_terms WHERE org_id = ? ORDER BY lower(term)")
                .param(Caller.from(req).orgId()).query(OrgConfig.DictionaryTerm.class).list();
    }

    @PutMapping("/dictionary")
    @Transactional
    public List<OrgConfig.DictionaryTerm> replaceTerms(@RequestBody List<OrgConfig.DictionaryTerm> terms, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (terms.size() > MAX_TERMS) throw new IllegalArgumentException("At most " + MAX_TERMS + " terms");
        Set<String> seen = new HashSet<>();
        jdbc.sql("DELETE FROM dictionary_terms WHERE org_id = ?").param(c.orgId()).update();
        for (var t : terms) {
            String term = t.term() == null ? "" : t.term().trim();
            if (term.length() < 2 || term.length() > 120) throw new IllegalArgumentException("Terms must be 2-120 characters: '" + term + "'");
            if (!seen.add(term.toLowerCase(Locale.ROOT))) continue;
            jdbc.sql("INSERT INTO dictionary_terms (org_id, term, label) VALUES (?, ?, ?)")
                    .params(c.orgId(), term, t.label() == null ? "" : t.label().trim()).update();
        }
        audit.record(c, "dictionary.replace", null, seen.size() + " terms");
        publisher.publish(c.orgId());
        return terms(req);
    }

    @GetMapping("/topics")
    public List<OrgConfig.TopicExample> topics(HttpServletRequest req) {
        return jdbc.sql("SELECT label, description, example FROM topic_examples WHERE org_id = ? ORDER BY id")
                .param(Caller.from(req).orgId()).query(OrgConfig.TopicExample.class).list();
    }

    @PutMapping("/topics")
    @Transactional
    public List<OrgConfig.TopicExample> replaceTopics(@RequestBody List<OrgConfig.TopicExample> examples, HttpServletRequest req) {
        Caller c = Caller.from(req).requireAdmin();
        if (examples.size() > MAX_EXAMPLES) throw new IllegalArgumentException("At most " + MAX_EXAMPLES + " examples");
        jdbc.sql("DELETE FROM topic_examples WHERE org_id = ?").param(c.orgId()).update();
        for (var e : examples) {
            if (e.label() == null || !e.label().matches("[a-z][a-z0-9-]{1,30}")) throw new IllegalArgumentException("Topic label: lowercase letters, digits, dashes");
            if (e.label().equals("neutral")) throw new IllegalArgumentException("'neutral' is reserved");
            if (e.example() == null || e.example().trim().length() < 15) throw new IllegalArgumentException("Examples need at least 15 characters");
            jdbc.sql("INSERT INTO topic_examples (org_id, label, description, example) VALUES (?, ?, ?, ?)")
                    .params(c.orgId(), e.label(), e.description() == null ? e.label() : e.description().trim(), e.example().trim()).update();
        }
        audit.record(c, "topics.replace", null, examples.size() + " examples");
        publisher.publish(c.orgId());
        return topics(req);
    }
}
