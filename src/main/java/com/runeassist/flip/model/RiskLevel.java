package com.runeassist.flip.model;

public enum RiskLevel
{
    HIGH,
    MEDIUM,
    LOW;

    public String toApiValue()
    {
        return name().toLowerCase();
    }
}
