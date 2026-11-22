package me.lendev.flow.calendar;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import me.lendev.flow.calendar.TradingCalendarModels.Issue;
import me.lendev.flow.calendar.TradingCalendarModels.Preview;
import me.lendev.flow.calendar.TradingCalendarModels.Source;
import me.lendev.flow.calendar.TradingCalendarRepository.Delta;
import me.lendev.flow.calendar.TradingCalendarRepository.Values;

/**
 * 确认令牌绑定有序的目标、原始状态和实际写入结果；相同计划产生相同令牌。
 */
record TradingCalendarPlan(Preview preview, List<Delta> deltas) {

    TradingCalendarPlan withIssues(List<Issue> issues) {
        if (issues.isEmpty()) {
            return this;
        }
        var sorted = issues.stream().sorted(Comparator.comparingInt(Issue::row)).toList();
        return new TradingCalendarPlan(new Preview(preview.added(), preview.changed(), preview.unchanged(), sorted, null), List.of());
    }

    static TradingCalendarPlan invalid(List<Issue> issues) {
        return new TradingCalendarPlan(new Preview(0, 0, 0, issues, null), List.of());
    }

    static TradingCalendarPlan of(Source operation, List<Delta> deltas) {
        MessageDigest digest = sha256();
        put(digest, operation.name());
        int added = 0;
        int changed = 0;
        for (Delta delta : deltas) {
            put(digest, Long.toString(delta.market().id()));
            put(digest, delta.market().market().code());
            put(digest, delta.date().toString());
            if (delta.before() == null) {
                added++;
                put(digest, (String) null);
            } else {
                if (delta.modified()) {
                    changed++;
                }
                put(digest, delta.before().updatedAt().toString());
                put(digest, delta.before().values());
            }
            put(digest, delta.after());
        }
        String token = HexFormat.of().formatHex(digest.digest());
        return new TradingCalendarPlan(new Preview(added, changed, deltas.size() - added - changed,
                List.of(), token), List.copyOf(deltas));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static void put(MessageDigest digest, Values values) {
        put(digest, values.trading() ? "1" : "0");
        put(digest, values.holiday());
        put(digest, values.source().name());
        put(digest, values.remark());
    }

    private static void put(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(new byte[]{-1, -1, -1, -1});
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
            digest.update(bytes);
        }
    }
}
