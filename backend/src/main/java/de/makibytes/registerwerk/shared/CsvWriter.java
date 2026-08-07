package de.makibytes.registerwerk.shared;

import java.util.List;

/**
 * Minimal RFC 4180 CSV writer — no dependency pulled in for what's a handful of export
 * endpoints (holder register, audit log) with modest row counts. A field is quoted whenever it
 * contains a comma, quote, or newline; embedded quotes are doubled per the spec.
 */
public final class CsvWriter {

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

    private static String escape(String field) {
        if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
            return "\"" + field.replace("\"", "\"\"") + "\"";
        }
        return field;
    }
}
