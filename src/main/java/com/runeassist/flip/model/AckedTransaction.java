package com.runeassist.flip.model;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
@EqualsAndHashCode
@ToString
@Slf4j
public class AckedTransaction {

    public static final int RAW_SIZE = 64;

    private UUID id;
    private UUID clientFlipId;
    private int accountId;
    private int time;
    private int itemId;
    private int quantity;
    private long price;
    private long amountSpent;

                    }
