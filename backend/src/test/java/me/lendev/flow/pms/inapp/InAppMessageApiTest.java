package me.lendev.flow.pms.inapp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import me.lendev.flow.pms.inapp.InAppMessageService.Write;
import me.lendev.flow.support.ApiTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

class InAppMessageApiTest extends ApiTestBase {

    @Autowired
    InAppMessageService messages;

    @Test
    void signedInUserCanListPersonalMessages() throws Exception {
        String token = loginAsAdmin();
        assertTrue(data(getJson("/in-app-messages", token)).path("items").isArray());
    }

    @Test
    void cursorAndReadAreScopedToOwnerAndRepeatedPublishKeepsTheFirstMessage() throws Exception {
        String token = loginAsAdmin();
        long baseline = data(getJson("/in-app-messages/unread-count", token)).path("count").asLong();
        long oldest = messages.publish(message(1, "test:old", "较早消息"));
        long newest = messages.publish(message(1, "test:new", "原消息"));
        long other = messages.publish(message(9001, "test:other", "其他用户的消息"));

        var first = data(getJson("/in-app-messages", token, "pageSize", 1, "unread", true));
        assertEquals(newest, first.path("items").get(0).path("id").asLong());
        assertEquals(newest, first.path("nextBeforeId").asLong());
        var next = data(getJson("/in-app-messages", token, "pageSize", 1, "beforeId", newest));
        assertEquals(oldest, next.path("items").get(0).path("id").asLong());
        assertEquals(baseline + 2, data(getJson("/in-app-messages/unread-count", token)).path("count").asLong());
        body(read(token, other), 404);
        body(read(token, Long.MAX_VALUE), 404);

        code(read(token, newest), 0);
        var original = data(getJson("/in-app-messages", token, "pageSize", 1, "unread", false)).path("items").get(0);
        assertTrue(original.path("readAt").isString());
        assertEquals(newest, messages.publish(message(1, "test:new", "重试标题")));
        code(read(token, newest), 0);
        var kept = data(getJson("/in-app-messages", token, "pageSize", 1, "unread", false)).path("items").get(0);
        assertEquals("原消息", kept.path("title").asString());
        assertEquals(original.path("readAt"), kept.path("readAt"));
        assertEquals(baseline + 1, data(getJson("/in-app-messages/unread-count", token)).path("count").asLong());
    }

    @Test
    void unavailableTargetsLeaveThePersonalMessageReadable() throws Exception {
        String token = loginAsAdmin();
        for (String targetType : new String[]{"WORKBENCH_TODO", "WORKFLOW_TASK", "WORKFLOW_INSTANCE", "UNKNOWN"}) {
            messages.publish(new Write(1, "TEST", "test:target:" + targetType, "TEST_NOTICE",
                    "测试目标", null, targetType, Long.MAX_VALUE));
        }
        var personal = data(getJson("/in-app-messages", token, "pageSize", 4)).path("items");
        assertEquals(4, personal.size());
        assertTrue(personal.valueStream().allMatch(message -> message.path("targetUrl").isNull()));
    }

    private static Write message(int recipient, String key, String title) {
        return new Write(recipient, "TEST", key, "TEST_NOTICE", title, null, null, null);
    }

    private ResultActions read(String token, long id) throws Exception {
        return mockMvc.perform(put("/in-app-messages/{id}/read", id).header("Authorization", bearer(token)));
    }
}
