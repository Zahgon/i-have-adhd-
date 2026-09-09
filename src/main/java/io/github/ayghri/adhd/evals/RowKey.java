package io.github.ayghri.adhd.evals;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.ayghri.adhd.util.PythonStr;
import io.github.ayghri.adhd.util.PythonValue;

import java.util.Comparator;
import java.util.Objects;

/**
 * The {@code (case_id, trial)} pair used as a Counter key in {@code _check_pairing}.
 *
 * <p>{@code trial} is required but never type-checked by {@code _validate_score}, so it is kept as a
 * raw node. Equality follows Python's numeric tower, where {@code 1}, {@code 1.0} and {@code True}
 * all hash and compare equal, hence the canonical form rather than direct node equality.
 */
public record RowKey(String caseId, String canonicalTrial, JsonNode trialNode) {

    public static RowKey of(JsonNode row) {
        JsonNode trial = row.get("trial");
        return new RowKey(PythonValue.str(row.get("case_id")), canonicalize(trial), trial);
    }

    private static String canonicalize(JsonNode trial) {
        if (PythonValue.isPythonNumber(trial)) {
            return "n:" + PythonValue.numericValue(trial);
        }
        return "s:" + PythonValue.str(trial);
    }

    public String describe() {
        return caseId + "/trial " + PythonValue.str(trialNode);
    }

    /**
     * Python sorts these tuples element-wise. Numeric trials compare numerically; anything else
     * falls back to text so that a malformed catalog still produces a deterministic message instead
     * of the TypeError Python would raise on mixed types.
     */
    private static final Comparator<RowKey> BY_TRIAL = (a, b) -> {
        boolean an = PythonValue.isPythonNumber(a.trialNode());
        boolean bn = PythonValue.isPythonNumber(b.trialNode());
        if (an && bn) {
            return Double.compare(
                    PythonValue.numericValue(a.trialNode()), PythonValue.numericValue(b.trialNode()));
        }
        if (an != bn) {
            return an ? -1 : 1;
        }
        return PythonStr.CODE_POINT_ORDER.compare(
                PythonValue.str(a.trialNode()), PythonValue.str(b.trialNode()));
    };

    public static final Comparator<RowKey> ORDER =
            Comparator.<RowKey, String>comparing(RowKey::caseId, PythonStr.CODE_POINT_ORDER).thenComparing(BY_TRIAL);

    @Override
    public boolean equals(Object other) {
        return other instanceof RowKey key
                && caseId.equals(key.caseId)
                && canonicalTrial.equals(key.canonicalTrial);
    }

    @Override
    public int hashCode() {
        return Objects.hash(caseId, canonicalTrial);
    }
}
