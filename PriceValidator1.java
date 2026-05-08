import java.math.BigDecimal;
import java.util.Objects;

/**
 * Validates configured price sources against the system's chosen "winning"
 * price.
 *
 * Domain in plain words
 * ---------------------
 * The user configures up to 4 ordered price sources: P1, P2, P3, P4.
 * The system picks the FIRST one that has a value — that's the "winner".
 * The other sources that also have a value are sanity-checked against the
 * winner,
 * each one governed by its own validation flag.
 *
 * Flag-to-price binding (fixed):
 * VF1 belongs to P2 (compares P2 against the winner)
 * VF2 belongs to P3 (compares P3 against the winner)
 * VF3 belongs to P4 (compares P4 against the winner)
 *
 * For each i in 1..3, the output ValF_i ends up as one of:
 * NULL → the flag had nothing to do (its source was missing, or that
 * source IS the winner itself, so there's nothing to compare)
 * NV → flag was N, meaning "skip validation on purpose"
 * Y → flag was Y and the source agrees with the winner (within threshold)
 * N → flag was Y and the source disagrees with the winner
 */
public final class PriceValidator {

    public enum Flag {
        Y, N
    }

    public enum Result {
        Y, N, NV
    } // the NULL state is just Java null

    /**
     * Inputs. Prices may be null (source has no value); flags and threshold must
     * not be null.
     */
    public record Request(
            BigDecimal p1, BigDecimal p2, BigDecimal p3, BigDecimal p4,
            Flag vf1, Flag vf2, Flag vf3,
            BigDecimal threshold) {

        public Request {
            Objects.requireNonNull(vf1, "vf1");
            Objects.requireNonNull(vf2, "vf2");
            Objects.requireNonNull(vf3, "vf3");
            Objects.requireNonNull(threshold, "threshold");
            if (threshold.signum() < 0) {
                throw new IllegalArgumentException("threshold must be >= 0");
            }
        }
    }

    public record Response(Result valF1, Result valF2, Result valF3) {
    }

    private PriceValidator() {
    }

    public static Response validate(Request req) {

        // Pack the inputs into arrays so we can index them.
        // Index meaning: 0 = P1, 1 = P2, 2 = P3, 3 = P4.
        BigDecimal[] prices = { req.p1(), req.p2(), req.p3(), req.p4() };
        Flag[] flags = { req.vf1(), req.vf2(), req.vf3() };

        // STEP 1 — find the winner.
        // Walk P1 → P4 and pick the first source that actually has a value.
        // That's the price the system has chosen, because the user configured
        // the sources in priority order. Everything else gets checked against it.
        int winnerIdx = firstNonNullIndex(prices); // -1 if every source is null

        // STEP 2 — fill the three result slots, one per validation flag.
        // Flag i (0,1,2 → VF1, VF2, VF3) is "owned" by the price one slot to its
        // right (P2, P3, P4). We call that price the "other price" — the candidate
        // being checked against the winner.
        Result[] results = new Result[3];

        for (int i = 0; i < 3; i++) {

            int otherIdx = i + 1; // VF1→P2, VF2→P3, VF3→P4
            BigDecimal otherPrice = prices[otherIdx];

            // Case A — flag has no work to do, so its slot is NULL.
            // This happens in two situations:
            // 1) The owning source has no value (otherPrice is null).
            // 2) The owning source IS the winner itself (e.g. P1 was null and
            // P2 became the winner — VF1 has no second price to validate).
            if (otherPrice == null || otherIdx == winnerIdx) {
                results[i] = null;
                continue;
            }

            // Case B — flag is N, meaning the user said "don't validate this source".
            // We had a price but were told to skip the check, so the slot is NV.
            if (flags[i] == Flag.N) {
                results[i] = Result.NV;
                continue;
            }

            // Case C — real validation.
            // Flag is Y and we have both a winner and another price. Compare them:
            // within threshold → sources agree → Y
            // outside threshold → sources disagree → N
            BigDecimal winnerPrice = prices[winnerIdx];
            BigDecimal diff = winnerPrice.subtract(otherPrice).abs();
            boolean withinLimit = diff.compareTo(req.threshold()) <= 0;

            results[i] = withinLimit ? Result.Y : Result.N;
        }

        return new Response(results[0], results[1], results[2]);
    }

    /** Returns the index of the first non-null entry, or -1 if all are null. */
    private static int firstNonNullIndex(BigDecimal[] prices) {
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] != null)
                return i;
        }
        return -1;
    }

    // ---------- demo ----------
    public static void main(String[] args) {
        BigDecimal t = new BigDecimal("0.50"); // absolute threshold

        run("All non-null, all Y",
                new Request(bd("100.00"), bd("100.20"), bd("100.80"), bd("100.10"),
                        Flag.Y, Flag.Y, Flag.Y, t));

        run("P1 null   → winner = P2, ValF1 = NULL",
                new Request(null, bd("100.00"), bd("100.30"), bd("100.10"),
                        Flag.Y, Flag.Y, Flag.Y, t));

        run("P3 null   → ValF2 = NULL",
                new Request(bd("100.00"), bd("100.20"), null, bd("100.10"),
                        Flag.Y, Flag.Y, Flag.Y, t));

        run("VF2 = N   → ValF2 = NV (skipped)",
                new Request(bd("100.00"), bd("100.20"), bd("999.99"), bd("100.10"),
                        Flag.Y, Flag.N, Flag.Y, t));

        run("P1, P2 null → winner = P3",
                new Request(null, null, bd("100.00"), bd("100.40"),
                        Flag.Y, Flag.Y, Flag.Y, t));

        run("Only P1 non-null → all NULL",
                new Request(bd("100.00"), null, null, null,
                        Flag.Y, Flag.Y, Flag.Y, t));
    }

    private static BigDecimal bd(String s) {
        return new BigDecimal(s);
    }

    private static void run(String label, Request req) {
        Response r = validate(req);
        System.out.printf("%-45s → ValF1=%s, ValF2=%s, ValF3=%s%n",
                label, fmt(r.valF1()), fmt(r.valF2()), fmt(r.valF3()));
    }

    private static String fmt(Result r) {
        return r == null ? "NULL" : r.name();
    }
}
