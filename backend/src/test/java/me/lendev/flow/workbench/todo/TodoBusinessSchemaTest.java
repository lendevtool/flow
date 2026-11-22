package me.lendev.flow.workbench.todo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

class TodoBusinessSchemaTest {

    private static final String CONTEXT = "wb_todo_business_context";
    private static final String LINK = "wb_todo_business_link";

    @Test
    void h2SchemaPreservesMetadataAndForeignKeys() throws SQLException {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:todo_assoc_metadata;MODE=MySQL;DATABASE_TO_LOWER=TRUE")) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/h2.sql"));
            var metadata = connection.getMetaData();
            checkTable(metadata, CONTEXT, "待办业务上下文，记录用于查询和授权的所属单据及任务环节",
                    List.of("todoid", "recordmodule", "recordtype", "recordid", "taskkindcode", "createdat"));
            checkTable(metadata, LINK, "待办业务归集关系，支持从多个业务对象查询同一条待办",
                    List.of("todoid", "refmodule", "reftype", "refid", "createdat"));
            checkPrimaryKey(metadata, CONTEXT, List.of("todoid"));
            checkPrimaryKey(metadata, LINK, List.of("todoid", "refmodule", "reftype", "refid"));
            checkIndex(metadata, CONTEXT, "idx_todo_business_context_record",
                    List.of("recordmodule", "recordtype", "recordid", "todoid"));
            checkIndex(metadata, LINK, "idx_todo_business_link_ref",
                    List.of("refmodule", "reftype", "refid", "todoid"));
            checkForeignKey(metadata, CONTEXT, "fk_todo_business_context_todo", "wb_todo", "id");
            checkForeignKey(metadata, LINK, "fk_todo_business_link_context", CONTEXT, "todoid");
            assertRejectsOrphans(connection);
        }
    }

    private static void checkTable(DatabaseMetaData metadata, String table, String comment, List<String> expectedColumns)
            throws SQLException {
        try (var tables = metadata.getTables(null, "public", table, new String[]{"BASE TABLE"})) {
            assertTrue(tables.next(), table);
            assertEquals(comment, tables.getString("REMARKS"));
        }
        var columns = new ArrayList<String>();
        try (var result = metadata.getColumns(null, "public", table, null)) {
            while (result.next()) {
                String column = result.getString("COLUMN_NAME");
                columns.add(column);
                assertEquals("NO", result.getString("IS_NULLABLE"));
                String remarks = result.getString("REMARKS");
                assertNotNull(remarks, column);
                assertTrue(remarks.codePoints().anyMatch(c -> Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN),
                        column);
                if (column.equals("createdat")) {
                    assertEquals(3, result.getInt("DECIMAL_DIGITS"));
                }
            }
        }
        assertEquals(expectedColumns, columns);
    }

    private static void checkPrimaryKey(DatabaseMetaData metadata, String table, List<String> expectedColumns)
            throws SQLException {
        var columns = new String[expectedColumns.size()];
        try (var result = metadata.getPrimaryKeys(null, "public", table)) {
            while (result.next()) {
                columns[result.getInt("KEY_SEQ") - 1] = result.getString("COLUMN_NAME");
            }
        }
        assertEquals(expectedColumns, List.of(columns));
    }

    private static void checkIndex(DatabaseMetaData metadata, String table, String index, List<String> expectedColumns)
            throws SQLException {
        var columns = new String[expectedColumns.size()];
        try (var rows = metadata.getIndexInfo(null, "public", table, false, false)) {
            while (rows.next()) {
                if (index.equals(rows.getString("INDEX_NAME"))) {
                    columns[rows.getInt("ORDINAL_POSITION") - 1] = rows.getString("COLUMN_NAME");
                }
            }
        }
        assertEquals(expectedColumns, List.of(columns));
    }

    private static void checkForeignKey(DatabaseMetaData metadata, String table, String constraint, String parent,
                                        String parentColumn) throws SQLException {
        try (var keys = metadata.getImportedKeys(null, "public", table)) {
            assertTrue(keys.next(), table);
            assertEquals(constraint, keys.getString("FK_NAME"));
            assertEquals("todoid", keys.getString("FKCOLUMN_NAME"));
            assertEquals(parent, keys.getString("PKTABLE_NAME"));
            assertEquals(parentColumn, keys.getString("PKCOLUMN_NAME"));
            assertEquals(DatabaseMetaData.importedKeyCascade, keys.getInt("DELETE_RULE"));
            assertEquals(DatabaseMetaData.importedKeyRestrict, keys.getInt("UPDATE_RULE"));
            assertFalse(keys.next(), table);
        }
    }

    private static void assertRejectsOrphans(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wb_todo_business_context VALUES (1, 'fixture', 'ORDER', '1', 'REVIEW', CURRENT_TIMESTAMP)
                    """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO wb_todo_business_link VALUES (1, 'fixture', 'SUPPLIER', '1', CURRENT_TIMESTAMP)
                    """));
        }
    }
}
