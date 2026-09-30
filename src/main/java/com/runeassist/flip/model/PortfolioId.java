package com.runeassist.flip.model;

public final class PortfolioId {

    public static final int COFLIP_PORTFOLIO = 0;
    public static final int PERSONAL_PORTFOLIO = 1;
    public static final int GHOST = -1;
    public static final int DISAPPEARED_COFLIP = -2;
    public static final int DISAPPEARED_GHOST = -3;
    public static final int DISAPPEARED_PERSONAL = -4;

    private PortfolioId() {}

    public static boolean isInPortfolio(int portfolioId) {
        return portfolioId == COFLIP_PORTFOLIO || portfolioId == PERSONAL_PORTFOLIO;
    }

    public static boolean isDisappeared(int portfolioId) {
        return portfolioId == DISAPPEARED_COFLIP
                || portfolioId == DISAPPEARED_GHOST
                || portfolioId == DISAPPEARED_PERSONAL;
    }

    public static boolean isMissed(int portfolioId) {
        return portfolioId == GHOST || isDisappeared(portfolioId);
    }
}
