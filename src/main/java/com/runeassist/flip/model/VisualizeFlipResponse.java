package com.runeassist.flip.model;

import com.runeassist.flip.ui.graph.model.Data;
import com.runeassist.flip.util.ProtoUtils;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;
import lombok.*;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Getter
@AllArgsConstructor
@NoArgsConstructor
@ToString
public class VisualizeFlipResponse {

    public int[] buyTimes;
    public int[] buyVolumes;
    public long[] buyPrices;
    public int[] sellTimes;
    public int[] sellVolumes;
    public long[] sellPrices;
    public Data graphData;
    // set, with everything else empty, when the item has no usable price data
    public String message;

    /**
     * Overlay local buy/sell lots on an Ares (or other) price series. When the ledger
     * has no per-lot rows, FlipV2 aggregates become a single buy and/or sell marker.
     */
    public static VisualizeFlipResponse fromLocalLots(Data graph, FlipV2 flip, List<AckedTransaction> txs) {
        VisualizeFlipResponse r = new VisualizeFlipResponse();
        r.graphData = graph;
        List<Integer> buyTimes = new ArrayList<>();
        List<Integer> buyVolumes = new ArrayList<>();
        List<Long> buyPrices = new ArrayList<>();
        List<Integer> sellTimes = new ArrayList<>();
        List<Integer> sellVolumes = new ArrayList<>();
        List<Long> sellPrices = new ArrayList<>();
        if (txs != null) {
            for (AckedTransaction t : txs) {
                if (t == null || t.getQuantity() == 0) {
                    continue;
                }
                int qty = Math.abs(t.getQuantity());
                long price = t.getPrice();
                if (price <= 0 && qty > 0) {
                    price = Math.abs(t.getAmountSpent()) / qty;
                }
                if (t.getQuantity() > 0) {
                    buyTimes.add(t.getTime());
                    buyVolumes.add(qty);
                    buyPrices.add(price);
                } else {
                    sellTimes.add(t.getTime());
                    sellVolumes.add(qty);
                    sellPrices.add(price);
                }
            }
        }
        if (buyTimes.isEmpty() && flip != null && flip.getOpenedQuantity() > 0) {
            buyTimes.add(flip.getOpenedTime());
            buyVolumes.add(flip.getOpenedQuantity());
            buyPrices.add(flip.getAvgBuyPrice());
        }
        if (sellTimes.isEmpty() && flip != null && flip.getClosedQuantity() > 0) {
            int sellAt = flip.getClosedTime() > 0 ? flip.getClosedTime() : flip.getUpdatedTime();
            sellTimes.add(sellAt);
            sellVolumes.add(flip.getClosedQuantity());
            sellPrices.add(flip.getAvgSellPrice());
        }
        r.buyTimes = toIntArray(buyTimes);
        r.buyVolumes = toIntArray(buyVolumes);
        r.buyPrices = toLongArray(buyPrices);
        r.sellTimes = toIntArray(sellTimes);
        r.sellVolumes = toIntArray(sellVolumes);
        r.sellPrices = toLongArray(sellPrices);
        return r;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] a = new int[values.size()];
        for (int i = 0; i < values.size(); i++) {
            a[i] = values.get(i);
        }
        return a;
    }

    private static long[] toLongArray(List<Long> values) {
        long[] a = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            a[i] = values.get(i);
        }
        return a;
    }

    public static VisualizeFlipResponse decodeProto(byte[] bytes) throws IOException {
        VisualizeFlipResponse r = new VisualizeFlipResponse();
        if (bytes == null || bytes.length == 0) {
            return r;
        }
        CodedInputStream input = CodedInputStream.newInstance(bytes);
        while (!input.isAtEnd()) {
            int tag = input.readTag();
            if (tag == 0) {
                break;
            }
            switch (WireFormat.getTagFieldNumber(tag)) {
                case 1:
                    r.graphData = Data.decodeProto(input.readByteArray());
                    break;
                case 2:
                    r.buyTimes = ProtoUtils.readPackedInt32Array(input);
                    break;
                case 3:
                    r.buyVolumes = ProtoUtils.readPackedInt32Array(input);
                    break;
                case 4:
                    r.buyPrices = ProtoUtils.readPackedInt64Array(input);
                    break;
                case 5:
                    r.sellTimes = ProtoUtils.readPackedInt32Array(input);
                    break;
                case 6:
                    r.sellVolumes = ProtoUtils.readPackedInt32Array(input);
                    break;
                case 7:
                    r.sellPrices = ProtoUtils.readPackedInt64Array(input);
                    break;
                case 8:
                    r.message = input.readString();
                    break;
                default:
                    input.skipField(tag);
            }
        }
        return r;
    }
}
