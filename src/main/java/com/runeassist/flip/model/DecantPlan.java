package com.runeassist.flip.model;

import lombok.Getter;
import lombok.Setter;
import java.util.ArrayList;
import java.util.List;

/** Server-owned advisory plan. Prices and ranking are never calculated by the client. */
@Getter @Setter
public class DecantPlan {
    private int version;
    private int buyItemId, sellItemId, buyDose, sellDose, buyQty, sellQty;
    private long buyAt, sellAt, createdAt;
    private String buyName, sellName;
    private List<Integer> familyIds = new ArrayList<>();

    public boolean isValid() {
        return version == 1 && buyItemId > 0 && sellItemId > 0 && buyItemId != sellItemId
            && buyDose >= 1 && buyDose < 4 && sellDose == 4 && buyQty > 0 && buyQty <= 100000
            && sellQty > 0 && (long) buyQty * buyDose == (long) sellQty * sellDose
            && buyAt > 0 && sellAt > 0 && familyIds != null && familyIds.size() <= 4
            && familyIds.contains(buyItemId) && familyIds.contains(sellItemId);
    }
}
