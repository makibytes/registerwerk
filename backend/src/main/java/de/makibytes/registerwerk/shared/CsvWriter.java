package de.makibytes.registerwerk.shared;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Minimal RFC 4180 CSV writer — no dependency pulled in for what's a handful of export
 * endpoints (holder register, audit log) with modest row counts. A field is quoted whenever it
 * contains a comma, quote, or newline; embedded quotes are doubled per the spec.
 *
 * <p>Formula-injection guard (6-35): issuer-supplied text such as {@code =HYPERLINK(...)} would execute when an
 * operator opens the file in a spreadsheet. A cell whose first character is {@code = + - @}, TAB or CR gets a
 * leading apostrophe, except pure numbers (so numeric columns and negative amounts stay numbers) — the apostrophe
 * makes spreadsheets treat the cell as text. Machine consumers should use the JSON endpoints.
 */
public final class CsvWriter {

    private static final Pattern PURE_NUMBER = Pattern.compile("^[+-]?\\d+([.,]\\d+)?([eE][+-]?\\d+)?$");

    private CsvWriter() {}

    public static String write(List<String> header, List<List<Object>> rows) {
        StringBuilder sb = new StringBuilder();
        writeRow(sb, header);
        for (List<Object> row : rows) {
            writeRow(sb, row.stream().map(v -> v == null ? "" : String.valueOf(v)).toList());
        }
        return sb.toString();
    }

    private static void writeRow(StringBuilder sb, List<String> fields) {
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(escape(fields.get(i)));
        }
        sb.append("\r\n");
    }

    static String escape(String field) {
        if (!field.isEmpty() && "=+-@\t\r".indexOf(field.charAt(0)) >= 0 && !PURE_NUMBER.matcher(field).matches()) {
            field = "'" + field;
        }
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
