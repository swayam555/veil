package dev.veil.detect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Source code is the data type employees upload to AI tools most (Verizon DBIR 2026). This detector scores
 * each line for code-like syntax and reports a document-level finding when a block of lines is mostly code,
 * with a best guess at the language.
 */
public final class SourceCodeDetector implements Detector {

    private static final Pattern CODE_LINE = Pattern.compile(
            "^\\s*(?:[}\\])]+;?|.*[;{]\\s*(?://.*)?|(?:public|private|protected|static|final|class|interface|enum|import|package|"
                    + "def|return|fn|func|const|let|var|function|async|await|if|for|while|switch|case|try|catch|throw|"
                    + "#include|#define|using|namespace|SELECT|INSERT|UPDATE|DELETE|CREATE|ALTER|FROM|WHERE|JOIN)\\b.*|"
                    + ".*=>.*|.*\\)\\s*:\\s*(?:#.*)?|@\\w+.*|\\s*#.*|\\s*//.*)$");
    private static final Map<String, Pattern> LANGUAGES = new LinkedHashMap<>();

    static {
        LANGUAGES.put("Java", Pattern.compile("\\b(?:public|private) (?:static )?(?:final )?[\\w<>\\[\\]]+ \\w+\\(|import java\\.|System\\.out|@Override|new \\w+<"));
        LANGUAGES.put("Python", Pattern.compile("(?m)^\\s*def \\w+\\(.*\\):|^\\s*import \\w+|^\\s*from \\w+ import|self\\.|print\\("));
        LANGUAGES.put("TypeScript/JavaScript", Pattern.compile("\\bconst \\w+ =|\\bfunction \\w+\\(|=>|console\\.log|\\binterface \\w+ \\{|require\\("));
        LANGUAGES.put("SQL", Pattern.compile("(?i)\\bSELECT\\b[\\s\\S]+\\bFROM\\b|\\bINSERT INTO\\b|\\bCREATE TABLE\\b"));
        LANGUAGES.put("Go", Pattern.compile("\\bfunc \\w+\\(|\\bpackage main\\b|:= "));
        LANGUAGES.put("C/C++", Pattern.compile("#include <|std::|int main\\("));
    }

    @Override
    public String name() {
        return "source-code";
    }

    @Override
    public List<Finding> detect(String text) {
        String[] lines = text.split("\n", -1);
        int bestStart = -1;
        int bestEnd = -1;
        int bestCode = 0;
        // Find the densest run of code-like lines (allowing blank lines inside it).
        int runStart = -1;
        int runCode = 0;
        int gap = 0;
        for (int i = 0; i <= lines.length; i++) {
            boolean isCode = i < lines.length && !lines[i].isBlank() && CODE_LINE.matcher(lines[i]).matches()
                    && lines[i].trim().length() > 1;
            boolean blank = i < lines.length && lines[i].isBlank();
            if (isCode) {
                if (runStart < 0) runStart = i;
                runCode++;
                gap = 0;
            } else if (blank && runStart >= 0 && gap < 1) {
                gap++;
            } else {
                if (runCode > bestCode) {
                    bestCode = runCode;
                    bestStart = runStart;
                    bestEnd = i - 1 - gap;
                }
                runStart = -1;
                runCode = 0;
                gap = 0;
            }
        }
        if (bestCode < 3) return List.of();
        int startOffset = offsetOfLine(lines, bestStart);
        int endOffset = offsetOfLine(lines, bestEnd) + lines[bestEnd].length();
        String block = text.substring(startOffset, endOffset);
        String language = guessLanguage(block);
        double confidence = Math.min(0.97, 0.6 + 0.05 * bestCode + (language.equals("Unknown") ? 0 : 0.1));
        String excerpt = block.length() > 80 ? block.substring(0, 80) + "…" : block;
        List<Finding> out = new ArrayList<>(1);
        out.add(new Finding(EntityType.SOURCE_CODE, startOffset, endOffset, excerpt, confidence, name(),
                language + " source code, " + bestCode + " lines"));
        return out;
    }

    static String guessLanguage(String block) {
        String best = "Unknown";
        int bestHits = 0;
        for (var e : LANGUAGES.entrySet()) {
            int hits = (int) e.getValue().matcher(block).results().count();
            if (hits > bestHits) {
                bestHits = hits;
                best = e.getKey();
            }
        }
        return best;
    }

    private static int offsetOfLine(String[] lines, int index) {
        int off = 0;
        for (int i = 0; i < index; i++) off += lines[i].length() + 1;
        return off;
    }
}
