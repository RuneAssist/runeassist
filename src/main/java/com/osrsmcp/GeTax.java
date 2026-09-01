package com.osrsmcp;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Grand Exchange sell tax: 2% of the sale price, capped at 5M (i.e. flat 5M once the price
 * reaches 250M), and NOT charged at all on a set of tax-exempt items (bonds, farming
 * saplings, chisel, gloves of silence, etc.). Getting the exempt list right matters — taxing
 * those items understates their flip margin.
 *
 * <p>The exemption list, the 250M threshold and the floor rounding are ported from Flipping
 * Copilot's {@code util/ProfitCalculator} (BSD 2-Clause, Copyright (c) 2024 Cillian Brewitt,
 * https://github.com/cbrewitt/flipping-copilot). See THIRD_PARTY_LICENSES.md.
 */
final class GeTax
{
    private static final double GE_TAX = 0.02;
    private static final long   GE_TAX_CAP = 5_000_000L;
    private static final long   MAX_PRICE_FOR_GE_TAX = 250_000_000L; // 250M * 2% = 5M cap

    private static final Set<Integer> EXEMPT = new HashSet<>(Arrays.asList(
        8011, 365, 2309, 882, 806, 1891, 8010, 1755, 28824, 2140, 2142, 8009, 5325, 1785, 2347,
        347, 884, 807, 28790, 379, 8008, 355, 2327, 558, 1733, 13190, 233, 351, 5341, 2552, 329,
        8794, 5329, 5343, 1735, 315, 952, 886, 808, 8013, 361, 8007, 5331));

    private GeTax() {}

    /** Tax charged when selling {@code itemId} at {@code price} (per item). */
    static long taxAmount(int itemId, long price)
    {
        if (price <= 0 || EXEMPT.contains(itemId)) return 0;
        if (price >= MAX_PRICE_FOR_GE_TAX) return GE_TAX_CAP;
        return (long) Math.floor(price * GE_TAX);
    }

    /** What you actually receive per item after tax. */
    static long postTaxPrice(int itemId, long price) { return price - taxAmount(itemId, price); }
}
