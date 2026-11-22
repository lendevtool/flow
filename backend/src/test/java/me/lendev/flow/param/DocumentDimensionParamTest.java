package me.lendev.flow.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.dao.SaTokenDao;
import cn.dev33.satoken.dao.SaTokenDaoDefaultImpl;
import cn.dev33.satoken.stp.StpUtil;

import java.util.List;
import java.util.Map;

import me.lendev.flow.common.exception.BadRequestException;
import me.lendev.flow.document.DocumentDimensions;
import me.lendev.flow.param.request.ParamGroupRequest;
import me.lendev.flow.param.request.ParamItemRequest;
import me.lendev.flow.support.JdbcFixtureConfiguration;
import me.lendev.flow.support.SaTokenTestExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Import(JdbcFixtureConfiguration.class)
@ExtendWith(SaTokenTestExtension.class)
@ActiveProfiles({"h2", "test"})
@Transactional
class DocumentDimensionParamTest {

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ParamService params;
    @Autowired
    private DocumentDimensions dimensions;
    @Autowired
    private SaTokenDao configuredSessions;

    @BeforeEach
    void logIn() {
        SaManager.setSaTokenDao(new SaTokenDaoDefaultImpl());
        StpUtil.login(1L);
    }

    @AfterEach
    void restoreSessionStore() {
        SaManager.setSaTokenDao(configuredSessions);
    }

    @Test
    void fixedSlotsAreSeededDisabledAndCannotBeRemoved() {
        var slots = dimensions.options().dimensions();
        assertEquals(List.of(1, 2, 3, 4, 5, 6), slots.stream().map(DocumentDimensions.Dimension::slot).toList());
        for (var slot : slots) {
            assertEquals("TEXT", slot.inputType());
            assertFalse(slot.enabled());
        }

        long groupId = groupId("DOC_DIMENSION");
        long slotId = slotId(groupId);
        assertEquals("error.document.dimension.fixed",
                assertThrows(BadRequestException.class, () -> params.deleteItem(groupId, slotId)).getMessage());
        long productGroupId = groupId("DOC_PRODUCT");
        assertEquals("error.document.dimension.fixed",
                assertThrows(BadRequestException.class, () -> params.deleteGroup(productGroupId)).getMessage());
    }

    @Test
    void usedSlotKeepsItsTypeAndDictionaryAndItsOptionCanBeDisabled() {
        long groupId = groupId("DOC_DIMENSION");
        long slotId = slotId(groupId);
        var dictionary = new ParamGroupRequest();
        dictionary.setCode("DOC_REGIONS_TEST");
        dictionary.setName("Regions");
        long dictionaryId = params.createGroup(dictionary).getId();
        var option = item("SH", "Shanghai");
        long optionId = params.createItem(dictionaryId, option).getId();

        var slot = item("DIMENSION_1", "Region");
        slot.setValue1("SELECT");
        assertEquals("error.document.dimension.dictionary",
                assertThrows(BadRequestException.class, () -> params.updateItem(groupId, slotId, slot)).getMessage());
        slot.setValue2("DOC_REGIONS_TEST");
        params.updateItem(groupId, slotId, slot);
        String normalizedId = Long.toString(optionId);
        var values = dimensions.validate(null, null, Map.of(1, " 0" + normalizedId + " "), null, null, Map.of());
        assertEquals(Map.of(1, normalizedId), values);
        jdbc.update("""
                INSERT INTO doc_document (name, dimension1Value, createdBy, updatedBy, deletedAt)
                VALUES ('Historical document', ?, 1, 1, CURRENT_TIMESTAMP)
                """, normalizedId);

        slot.setValue1("TEXT");
        assertEquals("error.document.dimension.binding-in-use",
                assertThrows(BadRequestException.class, () -> params.updateItem(groupId, slotId, slot)).getMessage());
        slot.setValue1("SELECT");
        slot.setValue2("DOC_PRODUCT");
        assertEquals("error.document.dimension.binding-in-use",
                assertThrows(BadRequestException.class, () -> params.updateItem(groupId, slotId, slot)).getMessage());
        assertEquals("error.document.dimension.dictionary-in-use",
                assertThrows(BadRequestException.class, () -> params.deleteGroup(dictionaryId)).getMessage());

        option.setEnable(false);
        params.updateItem(dictionaryId, optionId, option);
        assertFalse(dimensions.options().dimensions().getFirst().options().getFirst().enabled());
        assertEquals(values, dimensions.validate(null, null, values, null, null, values));
        Map<Integer, String> previous = Map.of();
        assertEquals("error.document.dimension.option", assertThrows(BadRequestException.class,
                () -> dimensions.validate(null, null, values, null, null, previous)).getMessage());
        assertEquals("error.document.dimension.option-in-use",
                assertThrows(BadRequestException.class, () -> params.deleteItem(dictionaryId, optionId)).getMessage());
    }

    private static ParamItemRequest item(String code, String name) {
        var item = new ParamItemRequest();
        item.setCode(code);
        item.setName(name);
        return item;
    }

    private long groupId(String code) {
        return jdbc.queryForObject("SELECT id FROM sys_param_group WHERE code = ?", Long.class, code);
    }

    private long slotId(long groupId) {
        return jdbc.queryForObject("SELECT id FROM sys_param_item WHERE groupId = ? AND code = 'DIMENSION_1'",
                Long.class, groupId);
    }
}
