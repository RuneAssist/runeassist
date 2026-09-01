package com.osrsmcp.graph;

/**
 * Price-history payload for one item — the field layout is adapted from Flipping Copilot's
 * graph {@code Data} model (BSD 2-Clause, Copyright (c) 2024 Cillian Brewitt,
 * https://github.com/cbrewitt/flipping-copilot; see THIRD_PARTY_LICENSES.md). Unlike the
 * original, this is populated straight from the RuneAssist server's JSON (Gson matches these
 * field names), so there is no protobuf/delta decoding, and the ML prediction series are
 * absent (we don't serve a forecast). Times are epoch seconds; a series the server omits
 * simply stays null.
 */
public class Data
{
    public int itemId;
    public String name;
    public double dailyVolume;
    public long sellPrice;
    public long buyPrice;

    // 6 months of 1h data
    public int[]  low1hTimes;
    public long[] low1hPrices;
    public int[]  high1hTimes;
    public long[] high1hPrices;

    // 1 month of 5m data
    public int[]  low5mTimes;
    public long[] low5mPrices;
    public int[]  high5mTimes;
    public long[] high5mPrices;

    // several days of latest data
    public int[]  lowLatestTimes;
    public long[] lowLatestPrices;
    public int[]  highLatestTimes;
    public long[] highLatestPrices;

    // volumes (UTC hour / 5-min bins)
    public int[] volume1hTimes;
    public int[] volume1hLows;
    public int[] volume1hHighs;
    public int[] volume5mTimes;
    public int[] volume5mLows;
    public int[] volume5mHighs;
}
