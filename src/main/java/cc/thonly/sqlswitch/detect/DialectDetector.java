package cc.thonly.sqlswitch.detect;

import cc.thonly.sqlswitch.model.Dialect;
import cc.thonly.sqlswitch.util.IoUtils;
import cc.thonly.sqlswitch.util.TextUtils;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Heuristic dialect detection for dumps.  SQLite database files are detected
 * from their binary header; text dumps are scored using the markers emitted by
 * mysqldump, mariadb-dump, sqlite3, Navicat and phpMyAdmin.
 */
public final class DialectDetector {
    private static final Pattern VERSION = Pattern.compile(
            "(?i)(?:server version|source server version)\\s*:\\s*([0-9][0-9A-Za-z._\\-]*(?:\\s*\\([^)]*\\))?)");

    private DialectDetector() {
    }

    public static DetectionResult detect(Path path) throws IOException {
        if (!path.toFile().isFile()) {
            throw new IOException("Input file not found: " + path);
        }
        if (IoUtils.isSqliteFile(path)) {
            return new DetectionResult(Dialect.SQLITE, true, 1.0,
                    List.of("SQLite format 3 file header"), null);
        }
        byte[] head = IoUtils.firstBytes(path, 512 * 1024);
        if (head.length == 0) {
            throw new IOException("Input file is empty: " + path);
        }
        IoUtils.DetectedText detected = IoUtils.detectCharset(path);
        String sample = IoUtils.decode(head, detected.charset());
        String lower = sample.toLowerCase(Locale.ROOT);
        String compact = lower.replaceAll("\\s+", " ");
        List<String> reasons = new ArrayList<>();

        int sqlite = 0;
        int mysql = 0;
        int mariadb = 0;

        if (compact.contains("source server type : sqlite")
                || compact.contains("target server type : sqlite")) {
            sqlite += 6;
            reasons.add("Navicat SQLite header");
        }
        if (lower.contains("pragma foreign_keys")) {
            sqlite += 3;
            reasons.add("PRAGMA statement");
        }
        if (lower.contains("sqlite_sequence")) {
            sqlite += 4;
            reasons.add("sqlite_sequence table");
        }
        if (lower.contains("autoincrement")) {
            sqlite += 3;
            reasons.add("AUTOINCREMENT");
        }
        if (lower.contains("insert into \"") || lower.contains("create table \"")) {
            sqlite += 2;
        }
        if (lower.contains("-- sqlite")) {
            sqlite += 2;
        }

        if (lower.contains("-- mysql dump") || lower.contains("mysql dump 10.")) {
            mysql += 8;
            reasons.add("mysqldump header");
        }
        if (compact.contains("source server type : mysql")
                || compact.contains("target server type : mysql")) {
            mysql += 6;
            reasons.add("Navicat MySQL header");
        }
        if (lower.contains("-- target: mysql")) {
            mysql += 7;
            reasons.add("SQLSwitch MySQL target header");
        }
        if (lower.contains("mysqldump")) {
            mysql += 3;
        }
        if (lower.contains("auto_increment")) {
            mysql += 4;
            reasons.add("AUTO_INCREMENT");
        }
        if (lower.contains("engine=")) {
            mysql += 2;
            reasons.add("table ENGINE option");
        }
        if (lower.contains("/*!40101") || lower.contains("/*!40014")) {
            mysql += 2;
            reasons.add("MySQL conditional comments");
        }
        if (lower.contains("lock tables") || lower.contains("unlock tables")) {
            mysql += 2;
        }
        if (sample.contains("`")) {
            mysql += 2;
            reasons.add("backtick identifiers");
        }

        if (lower.contains("-- mariadb dump") || lower.contains("mariadb dump")) {
            mariadb += 9;
            reasons.add("mariadb-dump header");
        }
        if (lower.contains("-- target: mariadb")) {
            mariadb += 7;
            reasons.add("SQLSwitch MariaDB target header");
        }
        if (compact.contains("source server type : mariadb")
                || compact.contains("target server type : mariadb")) {
            mariadb += 7;
            reasons.add("Navicat MariaDB header");
        }
        if (lower.contains("-mariadb")) {
            mariadb += 5;
            reasons.add("MariaDB server version");
        }
        if (Pattern.compile("\\d+\\.\\d+\\.\\d+-mariadb").matcher(lower).find()) {
            mariadb += 10;
        }
        if (lower.contains("mariadb")) {
            mariadb += 1;
        }

        Dialect dialect;
        double confidence;
        if (mariadb > 0 && mariadb >= mysql && mariadb >= sqlite) {
            dialect = Dialect.MARIADB;
            int total = sqlite + mysql + mariadb;
            confidence = total == 0 ? 0.5 : Math.min(1.0, 0.45 + (double) mariadb / total * 0.55);
        } else if (mysql > 0 && mysql >= sqlite) {
            dialect = Dialect.MYSQL;
            int total = sqlite + mysql + mariadb;
            confidence = total == 0 ? 0.5 : Math.min(1.0, 0.45 + (double) mysql / total * 0.55);
        } else if (sqlite > 0) {
            dialect = Dialect.SQLITE;
            int total = sqlite + mysql + mariadb;
            confidence = Math.min(1.0, 0.45 + (double) sqlite / Math.max(1, total) * 0.55);
        } else {
            // sqlite3 dumps without PRAGMAs and MySQL dumps with only CREATE
            // TABLE are still common; use a conservative default.
            dialect = sample.contains("`") ? Dialect.MYSQL : Dialect.SQLITE;
            confidence = 0.25;
            reasons.add("no strong marker; assumed " + dialect.displayName());
        }

        String version = extractVersion(sample);
        if (dialect == Dialect.MARIADB && version == null) {
            version = extractMariaDbVersion(sample);
        }
        return new DetectionResult(dialect, false, confidence, dedupe(reasons), version);
    }

    private static List<String> dedupe(List<String> input) {
        return input.stream().distinct().limit(8).toList();
    }

    private static String extractVersion(String sample) {
        Matcher matcher = VERSION.matcher(sample);
        if (matcher.find()) {
            return cleanVersion(matcher.group(1));
        }
        return null;
    }

    private static String extractMariaDbVersion(String sample) {
        Matcher matcher = Pattern.compile("(\\d+\\.\\d+\\.\\d+)-MariaDB", Pattern.CASE_INSENSITIVE)
                .matcher(sample);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static String cleanVersion(String raw) {
        String v = raw.trim();
        // Navicat writes e.g. "3035005 (3.35.5)"; prefer the parenthesised value.
        Matcher paren = Pattern.compile("\\(([0-9][^)]*)\\)").matcher(v);
        if (paren.find()) {
            v = paren.group(1);
        }
        return TextUtils.truncate(v.replaceAll("[^0-9A-Za-z._\\-].*$", ""), 40);
    }
}
