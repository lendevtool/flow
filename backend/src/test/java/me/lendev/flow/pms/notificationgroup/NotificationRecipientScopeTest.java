package me.lendev.flow.pms.notificationgroup;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import me.lendev.flow.pms.notificationgroup.NotificationGroupStore.RecipientScope;
import org.junit.jupiter.api.Test;

class NotificationRecipientScopeTest {
    @Test
    void h2ArrayScopeKeepsStableValuesWhenInputAndOutputArraysChange() {
        Long[] users = {1L};
        Long[] departments = {2L};
        var scope = new RecipientScope(true, users, departments, null, null);
        var same = RecipientScope.of(true, List.of(1L), Set.of(2L));

        users[0] = 100L;
        departments[0] = 200L;
        scope.userIds()[0] = 300L;
        scope.departmentIds()[0] = 400L;

        assertArrayEquals(new Long[]{1L}, scope.userIds());
        assertArrayEquals(new Long[]{2L}, scope.departmentIds());
        assertEquals(same, scope);
        assertEquals(same.hashCode(), scope.hashCode());
        assertNotEquals(RecipientScope.of(true, List.of(3L), Set.of(2L)), scope);
        assertTrue(scope.toString().contains("userIds=[1], departmentIds=[2]"));
    }
}
