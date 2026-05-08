import java.math.BigDecimal;
import java.util.Objects;

/**
 * PriceValidator
 * --------------
 * Picks the first non-null source price (priority order S1 -> S2 -> S3 -> S4)
 * and compares the remaining sources against it under TWO independent
 * thresholds: a price threshold and a materiality threshold. Each threshold
 * is gated by its own use-flag.
 *
 *   For each of the 3 comparisons (winner vs S2, winner vs S3, winner vs S4):
 *     - Compute the absolute difference once.
 *     - Run it through the price threshold      -> Y / N / NV / null
 *     - Run it through the materiality threshold -> Y / N / NV / null
 *
 * Then roll up the 6 individual results into a single overallValidationFlag.
 *
 * Output is exactly 14 fields:
 *   - 3 price diffs + 3 price results + 1 overallPriceValidationFlag
 *   - 3 materiality diffs + 3 materiality results + 1 overallMaterialityValidationFlag
 *
 * The two overall flags are computed independently — each one rolls up only
 * its own three results, using the same PASS1/FAIL1/null rule.
 *
 * Output state legend:
 *   Y    -> within threshold
 *   N    -> outside threshold
 *   NV   -> not validated (validation flag was N, or use-threshold flag was N,
 *                          or the threshold itself was null)
 *   null -> nothing to compare (source missing, or source IS the winner)
 */
public final class PriceValidator {

    private PriceValidator() {}

    /** Generic Y/N flag used everywhere a boolean-style toggle is needed. */
    public enum Flag { Y, N }

    /** Per-comparison result. The "no work" state is Java null, not an enum constant. */
    public enum Result { Y, N, NV }

    /**
     * Pass/Fail flag — used for BOTH the request input and the rolled-up output.
     * The "not set" / "no decision" state is Java null.
     *
     *   Input  side: null means "do not compute the overall flag, just return null".
     *                Either PASS1 or FAIL1 means "please compute it".
     *   Output side: PASS1 if any individual threshold result is Y;
     *                FAIL1 if at least one was decided and none of them is Y;
     *                null  if the input was null OR nothing was decisive.
     */
    public enum PassFail { PASS1, FAIL1 }

    /** A single pricing source: a name plus its (possibly null) price. */
    public record Source(String name, BigDecimal price) {}

    /**
     * The full request.
     *
     * Inputs are deliberately flat — no nested config object — so the field
     * order matches the spec top-to-bottom.
     *
     * tradePrice is accepted and carried through; it is not part of the
     * comparison math (the math runs strictly across the 4 source prices).
     */
    public record Request(
            BigDecimal tradePrice,
            Source s1, Source s2, Source s3, Source s4,
            Flag vf1, Flag vf2, Flag vf3,                               // gate per-source comparisons
            BigDecimal priceThreshold,       Flag usePriceThreshold,
            BigDecimal materialityThreshold, Flag useMaterialityThreshold,
            PassFail   passFailFlag                                     // gate the overall roll-up
    ) {
        public Request {
            Objects.requireNonNull(vf1,  "vf1");
            Objects.requireNonNull(vf2,  "vf2");
            Objects.requireNonNull(vf3,  "vf3");
            Objects.requireNonNull(usePriceThreshold,       "usePriceThreshold");
            Objects.requireNonNull(useMaterialityThreshold, "useMaterialityThreshold");
            // passFailFlag is intentionally nullable — null means "don't compute overall".
            if (priceThreshold       != null && priceThreshold.signum()       < 0)
                throw new IllegalArgumentException("priceThreshold must be >= 0");
            if (materialityThreshold != null && materialityThreshold.signum() < 0)
                throw new IllegalArgumentException("materialityThreshold must be >= 0");
        }
    }

    /**
     * The full response. 13 fields, in spec order:
     *   3 price diffs, 3 price results, 3 mat diffs, 3 mat results, overall.
     *
     * Diff fields are BigDecimal (null when there is nothing to compare).
     * Result fields are Result (null when there is nothing to compare).
     */
    public record Response(
            BigDecimal s1s2PriceDiff,    BigDecimal s1s3PriceDiff,    BigDecimal s1s4PriceDiff,
            Result     s1s2PriceResult,  Result     s1s3PriceResult,  Result     s1s4PriceResult,
            PassFail   overallPriceValidationFlag,
            BigDecimal s1s2MatDiff,      BigDecimal s1s3MatDiff,      BigDecimal s1s4MatDiff,
            Result     s1s2MatResult,    Result     s1s3MatResult,    Result     s1s4MatResult,
            PassFail   overallMaterialityValidationFlag
    ) {}

    // ------------------------------------------------------------------
    // Engine
    // ------------------------------------------------------------------

    public static Response validate(Request req) {
        // Pull the four prices out by index. From here on we only deal with
        // indices — the source records aren't needed for the comparison math.
        BigDecimal[] prices = {
                priceOf(req.s1()), priceOf(req.s2()),
                priceOf(req.s3()), priceOf(req.s4())
        };
        Flag[] vf = { req.vf1(), req.vf2(), req.vf3() };

        // Step 1: walk in priority order. The first non-null entry is the winner.
        // -1 means "all four were null" — there is nothing to validate at all.
        int winnerIdx = firstNonNullIndex(prices);
        BigDecimal winner = (winnerIdx < 0) ? null : prices[winnerIdx];

        // Step 2: three comparisons, each evaluated under both thresholds.
        // diff[i] is the same number used for both threshold paths — only the
        // results differ because the thresholds (and their use-flags) differ.
        BigDecimal[] diff      = new BigDecimal[3];
        Result[]     priceRes  = new Result[3];
        Result[]     matRes    = new Result[3];

        for (int i = 0; i < 3; i++) {
            int otherIdx = i + 1;                // vf[0]->S2, vf[1]->S3, vf[2]->S4
            BigDecimal other = prices[otherIdx];

            // Case A: nothing to compare.
            // Either the source has no price, or this source IS the winner.
            // Leave diff/results at null (the array default).
            if (other == null || otherIdx == winnerIdx) {
                continue;
            }

            // Case B: the validation flag opted out for this source.
            // Both threshold paths report NV; no diff is meaningful here.
            if (vf[i] == Flag.N) {
                priceRes[i] = Result.NV;
                matRes[i]   = Result.NV;
                continue;
            }

            // Case C: real comparison. Compute the absolute difference once,
            // then ask each threshold path independently what it thinks.
            diff[i]     = winner.subtract(other).abs();
            priceRes[i] = check(diff[i], req.priceThreshold(),       req.usePriceThreshold());
            matRes[i]   = check(diff[i], req.materialityThreshold(), req.useMaterialityThreshold());
        }

        // Step 3: roll up into TWO independent flags — one per threshold path.
        // passFailFlag == null means "don't bother" — return null straight through
        // for both. When non-null, each overall is computed only from its own
        // three results.
        PassFail overallPrice;
        PassFail overallMat;
        if (req.passFailFlag() == null) {
            overallPrice = null;
            overallMat   = null;
        } else {
            overallPrice = rollup(priceRes);
            overallMat   = rollup(matRes);
        }

        return new Response(
                diff[0],     diff[1],     diff[2],
                priceRes[0], priceRes[1], priceRes[2],
                overallPrice,
                diff[0],     diff[1],     diff[2],   // same diff, reported under the materiality fields
                matRes[0],   matRes[1],   matRes[2],
                overallMat
        );
    }

    /**
     * Apply one threshold + its use-flag to a pre-computed diff.
     *   use-flag N           -> NV (caller turned this path off)
     *   threshold null       -> NV (no tolerance configured to compare against)
     *   diff <= threshold    -> Y
     *   otherwise            -> N
     */
    private static Result check(BigDecimal diff, BigDecimal threshold, Flag useThreshold) {
        if (useThreshold == Flag.N) return Result.NV;
        if (threshold    == null)   return Result.NV;
        return diff.compareTo(threshold) <= 0 ? Result.Y : Result.N;
    }

    /**
     * PASS1 if any result in the group is Y.
     * FAIL1 if at least one result was decided (Y or N) and none of them are Y.
     * null  if nothing was decided (everything was null/NV).
     */
    private static PassFail rollup(Result[] group) {
        boolean anyY = false, anyDecided = false;
        for (Result r : group) {
            if (r == Result.Y)      { anyY = true; anyDecided = true; }
            else if (r == Result.N) { anyDecided = true; }
        }
        if (anyY)       return PassFail.PASS1;
        if (anyDecided) return PassFail.FAIL1;
        return null;
    }

    private static BigDecimal priceOf(Source s) { return s == null ? null : s.price(); }

    private static int firstNonNullIndex(BigDecimal[] prices) {
        for (int i = 0; i < prices.length; i++) {
            if (prices[i] != null) return i;
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Demo
    // ------------------------------------------------------------------

    public static void main(String[] args) {
        BigDecimal pT = bd("0.50");   // price threshold
        BigDecimal mT = bd("1.00");   // materiality threshold (looser)

        Object[][] scenarios = {
            { "1) All non-null, S3 outside both thresholds",
              build(bd("100.00"), bd("100.20"), bd("101.20"), bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "2) S1 null, winner shifts to S2",
              build(null, bd("100.00"), bd("100.30"), bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "3) S3 null",
              build(bd("100.00"), bd("100.20"), null, bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "4) VF2 = N -> NV under both thresholds",
              build(bd("100.00"), bd("100.20"), bd("999.99"), bd("100.10"),
                    Flag.Y, Flag.N, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "5) S1, S2 null, winner = S3",
              build(null, null, bd("100.00"), bd("100.40"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "6) Only S1 non-null",
              build(bd("100.00"), null, null, null,
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "7) All null",
              build(null, null, null, null,
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "8) Price threshold OFF, materiality ON",
              build(bd("100.00"), bd("100.20"), bd("101.20"), bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.N, mT, Flag.Y, PassFail.PASS1) },

            { "9) Both thresholds OFF -> all NV, overall = null",
              build(bd("100.00"), bd("100.20"), bd("100.30"), bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.N, mT, Flag.N, PassFail.PASS1) },

            { "10) Diff fits mat but not price (split decision)",
              build(bd("100.00"), bd("100.70"), bd("100.10"), bd("100.20"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "11) Everything outside both thresholds -> FAIL1",
              build(bd("100.00"), bd("105.00"), bd("110.00"), bd("120.00"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },

            { "12) passFailFlag input null -> overall suppressed",
              build(bd("100.00"), bd("100.20"), bd("100.30"), bd("100.10"),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, null) },

            { "13) tradePrice carried through (not used in math)",
              new Request(bd("99.95"),
                    new Source("S1", bd("100.00")), new Source("S2", bd("100.20")),
                    new Source("S3", bd("100.30")), new Source("S4", bd("100.10")),
                    Flag.Y, Flag.Y, Flag.Y, pT, Flag.Y, mT, Flag.Y, PassFail.PASS1) },
        };

        for (Object[] row : scenarios) {
            String  label = (String)  row[0];
            Request req   = (Request) row[1];
            Response r    = validate(req);
            print(label, r);
        }
    }

    /** Tiny constructor so the scenario table stays readable. */
    private static Request build(
            BigDecimal p1, BigDecimal p2, BigDecimal p3, BigDecimal p4,
            Flag vf1, Flag vf2, Flag vf3,
            BigDecimal pT, Flag usePT,
            BigDecimal mT, Flag useMT,
            PassFail passFail) {
        return new Request(
                null,
                new Source("S1", p1), new Source("S2", p2),
                new Source("S3", p3), new Source("S4", p4),
                vf1, vf2, vf3,
                pT, usePT, mT, useMT, passFail
        );
    }

    private static void print(String label, Response r) {
        System.out.println(label);
        System.out.printf("   Price diffs:    [s1-s2=%s, s1-s3=%s, s1-s4=%s]%n",
                show(r.s1s2PriceDiff()), show(r.s1s3PriceDiff()), show(r.s1s4PriceDiff()));
        System.out.printf("   Price results:  [s1-s2=%s, s1-s3=%s, s1-s4=%s]%n",
                show(r.s1s2PriceResult()), show(r.s1s3PriceResult()), show(r.s1s4PriceResult()));
        System.out.printf("   Price overall:  %s%n", show(r.overallPriceValidationFlag()));
        System.out.printf("   Mat   diffs:    [s1-s2=%s, s1-s3=%s, s1-s4=%s]%n",
                show(r.s1s2MatDiff()), show(r.s1s3MatDiff()), show(r.s1s4MatDiff()));
        System.out.printf("   Mat   results:  [s1-s2=%s, s1-s3=%s, s1-s4=%s]%n",
                show(r.s1s2MatResult()), show(r.s1s3MatResult()), show(r.s1s4MatResult()));
        System.out.printf("   Mat   overall:  %s%n%n", show(r.overallMaterialityValidationFlag()));
    }

    private static String show(Object o) {
        if (o == null) return "NULL";
        if (o instanceof Enum<?> e) return e.name();
        return o.toString();
    }

    private static BigDecimal bd(String s) { return new BigDecimal(s); }
}
