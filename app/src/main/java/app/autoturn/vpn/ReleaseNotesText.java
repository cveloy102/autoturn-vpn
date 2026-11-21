package app.autoturn.vpn;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the small Markdown subset used in GitHub release notes into plain text plus style ranges:
 * headings, **bold**, list items, `code` and [links](url). Pure Java so it can be unit-tested.
 */
final class ReleaseNotesText {
    static final int BOLD = 1, HEADING = 2;

    static final class Range {
        final int start, end, style;
        Range(int start, int end, int style) { this.start = start; this.end = end; this.style = style; }
    }

    final String text;
    final List<Range> ranges;
    private ReleaseNotesText(String text, List<Range> ranges) { this.text = text; this.ranges = ranges; }

    private static final Pattern HEADING_LINE = Pattern.compile("^(#{1,6})\\s+(.*?)\\s*#*\\s*$");
    private static final Pattern LIST_LINE = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\([^)]*\\)");

    static ReleaseNotesText parse(String markdown) {
        String source = markdown == null ? "" : markdown.replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder out = new StringBuilder();
        List<Range> ranges = new ArrayList<>();
        boolean first = true;
        int blank = 0;
        for (String raw : source.split("\n", -1)) {
            String line = raw.replaceAll("\\s+$", "");
            if (line.isEmpty()) { blank++; continue; }
            Matcher heading = HEADING_LINE.matcher(line);
            // The dialog already shows "AutoTurn X.Y.Z" as its title, so drop a leading top heading.
            if (first && heading.matches() && heading.group(1).length() == 1) { first = false; blank = 0; continue; }
            first = false;
            if (out.length() > 0) out.append(blank > 0 ? "\n\n" : "\n");
            blank = 0;
            if (heading.matches()) {
                int start = out.length();
                appendInline(out, ranges, heading.group(2));
                ranges.add(new Range(start, out.length(), HEADING));
                continue;
            }
            Matcher item = LIST_LINE.matcher(line);
            if (item.matches()) {
                out.append(item.group(1).isEmpty() ? "• " : "  ◦ ");
                appendInline(out, ranges, item.group(2));
            } else appendInline(out, ranges, line.trim());
        }
        return new ReleaseNotesText(out.toString(), ranges);
    }

    private static void appendInline(StringBuilder out, List<Range> ranges, String line) {
        line = LINK.matcher(line).replaceAll("$1").replace("`", "");
        int i = 0;
        while (i < line.length()) {
            int open = line.indexOf("**", i);
            int close = open < 0 ? -1 : line.indexOf("**", open + 2);
            if (open < 0 || close < 0) { out.append(line, i, line.length()); return; }
            out.append(line, i, open);
            int start = out.length();
            out.append(line, open + 2, close);
            if (out.length() > start) ranges.add(new Range(start, out.length(), BOLD));
            i = close + 2;
        }
    }
}
