package io.euhedral_execution.hardware_utils.compatibility;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class DefectLedgerTest {

    private static void add(Map<String, String> mapping, String owners, String... ids) {
        for (String id : ids) {
            mapping.put(id, owners);
        }
    }

    @Test
    void mapsEveryKnownCorrectionToAnExactLaterRegression() throws Exception {
        DefectLedger ledger = DefectLedger.read(TestPaths.resource("defect-ledger.tsv"));
        Map<String, String> actual = ledger.defects().stream().collect(Collectors.toMap(
                DefectLedger.Defect::id, DefectLedger.Defect::ownerPhases,
                (left, right) -> {
                    assertEquals(left, right, "one defect ID has inconsistent owners");
                    return left;
                }, TreeMap::new));

        Map<String, String> expected = new TreeMap<>();
        add(expected, "P1", "B01", "B02", "B03", "B04", "B05", "B07");
        add(expected, "P1,P5,P6,P7", "B06");
        add(expected, "P2,P7", "T01");
        add(expected, "P2,P5", "T02");
        add(expected, "P2,P6", "T03");
        add(expected, "P2", "T04", "T06");
        add(expected, "P2,P4", "T05");
        add(expected, "P3", "A01", "A02");
        add(expected, "P6", "A03", "N01");
        add(expected, "P7", "A04", "N02");
        add(expected, "P4,P5,P6,P7", "R01", "R13");
        add(expected, "P4,P5", "R02", "R06", "R14");
        add(expected, "P4,P7", "R03");
        add(expected, "P4,P6", "R04");
        add(expected, "P4", "R05", "R07", "R08", "R09", "R10");
        add(expected, "P5", "R11", "R12");
        add(expected, "P8", "C01", "C02");
        assertEquals(expected, actual);
    }
}
