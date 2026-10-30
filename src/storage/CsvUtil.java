package storage;

import java.util.ArrayList;
import java.util.List;

final class CsvUtil {
    private CsvUtil() {}

    static String row(String... fields) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < fields.length; i++) {
            if (i > 0) out.append(',');
            String v = fields[i] == null ? "" : fields[i];
            out.append('"').append(v.replace("\"", "\"\"")).append('"');
        }
        return out.toString();
    }

    static List<String> parse(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    cell.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                out.add(cell.toString()); cell.setLength(0);
            } else cell.append(c);
        }
        out.add(cell.toString());
        return out;
    }
}
