package com.runeassist.flip.ui.graph.model;

import lombok.Getter;


public class Data {

    @Getter
    public String loadingErrorMessage;

    @Getter
    public boolean fromWaitSuggestion;

    public int[] low1hTimes;

    public long[] low1hPrices;

    public int[] high1hTimes;

    public long[] high1hPrices;

    public int[] low5mTimes;

    public long[] low5mPrices;

    public int[] high5mTimes;

    public long[] high5mPrices;

    public int[] lowLatestTimes;

    public long[] lowLatestPrices;

    public int[] highLatestTimes;

    public long[] highLatestPrices;

    public int[] predictionTimes;

    public long[] predictionLowMeans;

    public long[] predictionLowIQRUpper;

    public long[] predictionLowIQRLower;

    public long[] predictionHighMeans;

    public long[] predictionHighIQRUpper;

    public long[] predictionHighIQRLower;

    public int[] volume1hLows;
    public int[] volume1hHighs;
    public int[] volume1hTimes;

    public int[] volume5mLows;
    public int[] volume5mHighs;
    public int[] volume5mTimes;
    
    public int itemId;

    public String name;

    public double dailyVolume;

    public long sellPrice;
    public long buyPrice;

    public boolean hasPriceSeries() {
        return nonempty(high1hTimes) || nonempty(low1hTimes)
                || nonempty(high5mTimes) || nonempty(low5mTimes)
                || nonempty(highLatestTimes) || nonempty(lowLatestTimes);
    }

    private static boolean nonempty(int[] a) {
        return a != null && a.length > 0;
    }


    
    public void clearPredictionData() {
        predictionHighIQRLower = null;
        predictionHighIQRUpper = null;
        predictionHighMeans = null;
        predictionLowIQRLower = null;
        predictionLowIQRUpper = null;
        predictionLowMeans = null;
        predictionTimes = null;
    }
}
