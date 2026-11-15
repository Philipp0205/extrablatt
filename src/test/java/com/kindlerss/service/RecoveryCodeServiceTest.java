package com.kindlerss.service;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecoveryCodeServiceTest {

    private static final String FORMAT = "[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}(-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{4}){2}";

    private final RecoveryCodeService service = new RecoveryCodeService(null);

    @Test
    void generatedCodesHaveTheRightFormatAndDiffer() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = service.newCode();
            assertTrue(code.matches(FORMAT), code);
            seen.add(code);
        }
        assertEquals(200, seen.size());
    }

    @Test
    void normalizeStripsSeparatorsAndUppercases() {
        assertEquals(Optional.of("ABCD-EFGH-JKLM"), RecoveryCodeService.normalize("abcd-efgh-jklm"));
        assertEquals(Optional.of("ABCD-EFGH-JKLM"), RecoveryCodeService.normalize("  abcd efgh\tjklm \n"));
        assertEquals(Optional.of("ABCD-EFGH-JKLM"), RecoveryCodeService.normalize("ABCDEFGHJKLM"));
        assertEquals(Optional.of("ABCD-2345-6789"), RecoveryCodeService.normalize("abcd_2345.6789"));
    }

    @Test
    void normalizeRejectsWhatIsNotACode() {
        assertEquals(Optional.empty(), RecoveryCodeService.normalize(null));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize(""));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("----"));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKL"));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKLMN"));
        // I, O, 0 and 1 are not in the alphabet.
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKL0"));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKLI"));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKL1"));
        assertEquals(Optional.empty(), RecoveryCodeService.normalize("ABCD-EFGH-JKLO"));
    }

    @Test
    void theHashIgnoresHowTheCodeWasTyped() {
        String hash = RecoveryCodeService.hash("ABCD-EFGH-JKLM");

        assertEquals(64, hash.length());
        assertEquals(hash, RecoveryCodeService.hash("abcd efgh jklm"));
        assertNotEquals(hash, RecoveryCodeService.hash("ABCD-EFGH-JKLN"));
        assertThrows(IllegalArgumentException.class, () -> RecoveryCodeService.hash("nope"));
    }
}
