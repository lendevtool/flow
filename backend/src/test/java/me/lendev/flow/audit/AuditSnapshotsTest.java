package me.lendev.flow.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.Map;

import me.lendev.flow.audit.model.AuditObject;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AuditSnapshotsTest {

    private final ObjectMapper json = new ObjectMapper();
    private final AuditSnapshots snapshots = new AuditSnapshots(json);
    private final AuditObject object = new AuditObject("PARAM_ITEM", "2", "home", "首页", "PARAM_GROUP", "1");

    @Test
    void hiddenValuesProduceRealChangesWithoutValuesOrValueHashes() {
        var before = snapshots.capture(object, Map.of("value1", AuditField.hidden("令牌", "secret-before")));
        var after = snapshots.capture(object, Map.of("value1", AuditField.hidden("令牌", "secret-after")));
        var change = snapshots.updated(before, after);
        assertEquals(1, change.changes().size());
        String payload = json.writeValueAsString(change);
        assertFalse(payload.contains("secret-before"));
        assertFalse(payload.contains("secret-after"));
        assertFalse(payload.toLowerCase().contains("hash"));
        assertTrue(payload.contains("redacted"));
        assertFalse(before.toString().contains("secret-before"));
    }

    @Test
    void captureFreezesNestedJsonBeforeMutation() {
        var raw = json.createObjectNode().put("color", "blue");
        var before = snapshots.capture(object, Map.of("extra", AuditField.visible("扩展", raw)));
        raw.put("color", "red");
        var after = snapshots.capture(object, Map.of("extra", AuditField.visible("扩展", raw)));
        var change = snapshots.updated(before, after);
        assertEquals("blue", change.changes().getFirst().before().value().path("color").asString());
        assertEquals("red", change.changes().getFirst().after().value().path("color").asString());
    }

    @Test
    void preservesMissingNullEmptyZeroAndFalse() {
        var before = snapshots.capture(object, Map.of(
                "missing", AuditField.missing("新增", true),
                "empty", AuditField.visible("空串", ""),
                "zero", AuditField.visible("零", 0),
                "flag", AuditField.visible("标志", false)));
        Map<String, AuditField> fields = new LinkedHashMap<>();
        fields.put("missing", AuditField.visible("新增", null));
        fields.put("empty", AuditField.visible("空串", null));
        fields.put("zero", AuditField.visible("零", false));
        fields.put("flag", AuditField.visible("标志", true));
        var change = snapshots.updated(before, snapshots.capture(object, fields));
        assertEquals(4, change.changes().size());
        var added = change.changes().stream().filter(c -> c.path().equals("missing")).findFirst().orElseThrow();
        assertFalse(added.before().present());
        assertTrue(added.after().present());
        assertTrue(added.after().value().isNull());
        assertEquals("ADD", added.changeType());
    }

    @Test
    void policyUsesStricterSideAndNoChangeStaysNoChange() {
        var hidden = snapshots.capture(object, Map.of("value1", AuditField.hidden("值1", "old-secret")));
        var visible = snapshots.capture(object, Map.of("value1", AuditField.visible("值1", "new-secret")));
        var change = snapshots.updated(hidden, visible);
        assertTrue(change.changes().getFirst().before().redacted());
        assertTrue(change.changes().getFirst().after().redacted());
        assertFalse(json.writeValueAsString(change).contains("old-secret"));
        assertFalse(json.writeValueAsString(change).contains("new-secret"));
        assertFalse(snapshots.updated(hidden, hidden).changed());
        assertTrue(snapshots.updated(hidden, hidden).changes().isEmpty());
    }

    @Test
    void creationDeletionAndReferenceHaveCorrectAbsentSides() {
        var capture = snapshots.capture(object, Map.of("sort", AuditField.visible("序号", 0)));
        assertNull(snapshots.created(capture).beforeData());
        assertNotNull(snapshots.created(capture).afterData());
        assertTrue(snapshots.created(capture).changed());
        assertNull(snapshots.deleted(capture).afterData());
        assertNotNull(snapshots.deleted(capture).beforeData());
        var reference = snapshots.reference(object);
        assertEquals("REFERENCE", reference.entryType());
        assertNull(reference.beforeData());
        assertNull(reference.afterData());
        assertFalse(reference.changed());
        assertTrue(reference.changes().isEmpty());
    }

    @Test
    void rejectsDifferentObjectsAndDoesNotRetainMutableMetadata() {
        var fields = new LinkedHashMap<>(Map.of("name", AuditField.visible("名称", "before")));
        var before = snapshots.capture(object, fields);
        fields.clear();
        assertEquals(1, snapshots.created(before).changes().size());
        var other = new AuditObject("PARAM_ITEM", "3", "home", "首页", "PARAM_GROUP", "1");
        var after = snapshots.capture(other, fields);
        assertThrows(IllegalArgumentException.class, () -> snapshots.updated(before, after));
    }
}
