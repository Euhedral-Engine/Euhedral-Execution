package io.euhedral_execution.hardware_utils.compatibility.helpers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

public final class DefectLedger {

    private static final String HEADER =
            "defect_id\towner_phase\tsubject\told_behavior\tnew_invariant\tregression_test_id";
    private static final Pattern OWNERS = Pattern.compile("P[1-8](,P[1-8])*");
    private static final Pattern TEST_ID = Pattern.compile(
            "[a-zA-Z_$][\\w$]*(\\.[a-zA-Z_$][\\w$]*)+#[a-zA-Z_$][\\w$]*");

    public static DefectLedger read(Path path) throws IOException {
        String text = Files.readString(path, StandardCharsets.UTF_8);
        if (text.indexOf('\r') >= 0) {
            throw new IOException(path + ": CRLF is not canonical");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new IOException(path + ": invalid defect ledger header");
        }
        List<Defect> defects = new ArrayList<>();
        Set<String> tuples = new HashSet<>();
        for (int index = 1; index < lines.length; index++) {
            if (lines[index].isEmpty()) {
                if (index == lines.length - 1) {
                    continue;
                }
                throw new IOException(path + ":" + (index + 1) + ": blank row");
            }
            String[] columns = lines[index].split("\t", -1);
            if (columns.length != 6) {
                throw new IOException(path + ":" + (index + 1) + ": expected six columns");
            }
            for (String column : columns) {
                if (column.isBlank()) {
                    throw new IOException(path + ":" + (index + 1) + ": blank field");
                }
            }
            if (!columns[0].matches("(B0[1-7]|T0[1-6]|A0[1-4]|R(0[1-9]|1[0-4])|N0[1-2]|C0[1-2])")) {
                throw new IOException(path + ":" + (index + 1) + ": unknown defect ID");
            }
            if (!OWNERS.matcher(columns[1]).matches() || columns[1].contains("P0")) {
                throw new IOException(path + ":" + (index + 1) + ": invalid owner phases");
            }
            String subject = columns[2];
            if (subject.contains("*") || subject.contains("...") || subject.equalsIgnoreCase("all")
                    || subject.endsWith(".*")) {
                throw new IOException(path + ":" + (index + 1) + ": inexact subject");
            }
            String oldLower = columns[3].toLowerCase(java.util.Locale.ROOT);
            if (oldLower.contains("buggy") || oldLower.contains("wrong")) {
                throw new IOException(path + ":" + (index + 1) + ": content-free old behavior");
            }
            if (columns[4].equalsIgnoreCase("fixed")) {
                throw new IOException(path + ":" + (index + 1) + ": content-free invariant");
            }
            if (!TEST_ID.matcher(columns[5]).matches()) {
                throw new IOException(path + ":" + (index + 1) + ": invalid regression test ID");
            }
            String tuple = columns[0] + '\t' + subject + '\t' + columns[5];
            if (!tuples.add(tuple)) {
                throw new IOException(path + ":" + (index + 1) + ": duplicate defect tuple");
            }
            defects.add(new Defect(columns[0], columns[1], subject, columns[3], columns[4],
                    columns[5]));
        }
        return new DefectLedger(defects);
    }
    private final List<Defect> defects;

    private DefectLedger(List<Defect> defects) {
        this.defects = List.copyOf(defects);
    }

    public List<Defect> defects() {
        return this.defects;
    }

    public boolean hasSubject(String subject) {
        return this.defects.stream().anyMatch(defect -> defect.subject().equals(subject));
    }

    public record Defect(String id, String ownerPhases, String subject, String oldBehavior,
                  String newInvariant, String regressionTestId) {

    }
}
