package cc.thonly.sqlswitch.detect;

import cc.thonly.sqlswitch.model.Dialect;

import java.util.List;

public record DetectionResult(
        Dialect dialect,
        boolean databaseFile,
        double confidence,
        List<String> reasons,
        String serverVersion
) {
    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(dialect.displayName());
        if (databaseFile) {
            sb.append(" database file");
        } else {
            sb.append(" SQL dump");
        }
        sb.append(String.format(" (confidence %.0f%%)", confidence * 100));
        if (serverVersion != null && !serverVersion.isBlank()) {
            sb.append(", server ").append(serverVersion);
        }
        if (!reasons.isEmpty()) {
            sb.append(" - ").append(String.join("; ", reasons));
        }
        return sb.toString();
    }
}
