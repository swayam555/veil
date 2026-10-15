package dev.veil.detect;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds person names without a heavyweight NER model: runs of capitalised words, scored by a first-name
 * gazetteer, honorifics ("Mr.", "Dr.", "Smt.") and context ("customer", "patient", "Dear"). Words that are
 * also ordinary English ("Grace", "Will") need a surname or a context word before they count.
 */
public final class NameDetector implements Detector {

    private static final Pattern CANDIDATE = Pattern.compile(
            "\\b[A-Z][a-z]{1,20}(?:-[A-Z][a-z]{1,20})?(?:[ ](?:[A-Z]\\.[ ])?[A-Z][a-z]{1,20}(?:-[A-Z][a-z]{1,20})?){0,2}\\b");
    private static final Pattern HONORIFIC = Pattern.compile(
            "(?:\\b(?:Mr|Mrs|Ms|Miss|Dr|Prof|Shri|Smt|Sri|Kumari|Mx|Sir|Madam)\\.?\\s+)$");
    private static final List<String> CONTEXT = List.of(
            "customer", "client", "patient", "employee", "candidate", "applicant", "user ", "member", "tenant",
            "dear ", "hi ", "hello ", "name is", "name:", "named", "contact", "manager", "cc:",
            "signed", "regards", "thanks,", "account holder", "borrower", "guest", "student", "father", "mother",
            "son of", "daughter of", "wife of", "husband of", "s/o", "d/o", "w/o", "spoke to", "talked to", "spoke with",
            "met ", "assigned to", "reported by", "approved by");
    private static final Set<String> STOP = Set.of(
            "The", "This", "That", "These", "Those", "There", "Their", "They", "Then", "When", "Where", "What", "Which",
            "Who", "Why", "How", "Please", "Thanks", "Thank", "Hello", "Hi", "Dear", "Regards", "Best", "Yes", "No",
            "And", "But", "Or", "If", "For", "From", "With", "About", "Also", "Our", "Your", "My", "We", "You", "It",
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday", "January", "February",
            "March", "July", "September", "October", "November", "December", "India", "Indian", "Delhi", "Mumbai",
            "Bangalore", "Bengaluru", "Chennai", "Hyderabad", "Pune", "Kolkata", "Street", "Road", "Nagar", "Bank",
            "Limited", "Ltd", "Pvt", "Inc", "Corp", "Company", "Team", "Project", "Customer", "Client", "Patient",
            "Employee", "Manager", "Account", "Card", "Number", "Email", "Phone", "Address", "Summary", "Note",
            "Subject", "Re", "Fwd", "Can", "Could", "Would", "Should", "Is", "Are", "Was", "Were", "Has", "Have",
            "Write", "Draft", "Explain", "Summarize", "Summarise", "Translate", "Help", "Tell", "Give", "Make",
            "Create", "Fix", "Review", "Check", "Find", "Show", "List", "Use", "Add", "Ignore", "Act", "Pretend",
            "Hey", "Sir", "Madam", "Mr", "Mrs", "Ms", "Dr", "Prof", "Here", "Today", "Tomorrow", "Yesterday", "Q1",
            "Q2", "Q3", "Q4", "Board", "Revenue", "Sales", "Legal", "Finance", "Engineering", "Support", "Sent",
            "Ok", "Okay", "Sure", "Hindi", "English", "Diagnosis", "Name", "Date", "Notes", "Policy");

    private final Set<String> firstNames = new HashSet<>();
    private final Set<String> ambiguous = new HashSet<>();

    public NameDetector() {
        load();
    }

    @Override
    public String name() {
        return "names";
    }

    @Override
    public List<Finding> detect(String text) {
        List<Finding> out = new ArrayList<>();
        Matcher m = CANDIDATE.matcher(text);
        while (m.find()) {
            int s = m.start();
            int e = m.end();
            String span = text.substring(s, e);
            String[] tokens = span.split(" ");
            // Drop leading and trailing words that aren't names ("Please call Priya Sharma" -> "Priya Sharma").
            int from = 0;
            int to = tokens.length;
            while (from < to && STOP.contains(tokens[from])) from++;
            while (to > from && STOP.contains(tokens[to - 1])) to--;
            if (from >= to) continue;
            int startOffset = s;
            for (int i = 0; i < from; i++) startOffset += tokens[i].length() + 1;
            int endOffset = startOffset;
            for (int i = from; i < to; i++) endOffset += tokens[i].length() + (i > from ? 1 : 0);
            List<String> words = List.of(tokens).subList(from, to);
            double c = score(text, startOffset, words);
            if (c > 0) {
                out.add(new Finding(EntityType.PERSON, startOffset, endOffset, text.substring(startOffset, endOffset),
                        c, name(), "Person name"));
            }
        }
        return out;
    }

    private double score(String text, int start, List<String> words) {
        String before = text.substring(Math.max(0, start - 32), start);
        boolean honorific = HONORIFIC.matcher(before).find();
        String first = words.getFirst().toLowerCase(Locale.ROOT);
        boolean known = firstNames.contains(first);
        boolean isAmbiguous = ambiguous.contains(first);
        boolean hasSurname = words.size() >= 2;
        boolean context = hasContext(before.toLowerCase(Locale.ROOT));
        boolean sentenceStart = isSentenceStart(text, start);

        if (honorific) return 0.95;
        if (known && !isAmbiguous && hasSurname) return 0.92;
        if (known && isAmbiguous && hasSurname) return context ? 0.9 : 0.75;
        if (known && !isAmbiguous) return context ? 0.88 : (sentenceStart ? 0.6 : 0.72);
        if (known) return context ? 0.75 : 0;
        // Unknown first name: only with strong context and a surname ("customer Tenzin Norgay").
        if (context && hasSurname && !sentenceStart) return 0.72;
        return 0;
    }

    private static boolean hasContext(String before) {
        for (String w : CONTEXT) {
            if (before.endsWith(w) || before.endsWith(w.trim() + " ") || before.contains(w)) return true;
        }
        return false;
    }

    private static boolean isSentenceStart(String text, int start) {
        int i = start - 1;
        while (i >= 0 && text.charAt(i) == ' ') i--;
        return i < 0 || ".!?\n".indexOf(text.charAt(i)) >= 0;
    }

    private void load() {
        try (InputStream in = NameDetector.class.getResourceAsStream("/veil/first-names.txt")) {
            if (in == null) throw new IllegalStateException("first-names.txt missing");
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (line.startsWith("#")) continue;
                    for (String w : line.trim().split("\\s+")) {
                        if (w.isEmpty()) continue;
                        if (w.endsWith("*")) {
                            String n = w.substring(0, w.length() - 1);
                            firstNames.add(n);
                            ambiguous.add(n);
                        } else {
                            firstNames.add(w);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
