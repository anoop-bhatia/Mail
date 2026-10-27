package eu.faircode.email;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SendOperationCascadeTest {
    @Test
    public void deletingOutboxMessageAtomicallyCascadesToLinkedSendOperation() throws Exception {
        String operationSql = getCurrentOperationCreateSql();
        String messageForeignKey = "FOREIGN KEY(`message`) REFERENCES `message`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE";
        assertTrue("The current Room schema must cascade operation deletion with its message",
                operationSql.contains(messageForeignKey));
        operationSql = operationSql.replace("${TABLE_NAME}", "operation");

        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
             Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("CREATE TABLE folder (id INTEGER PRIMARY KEY)");
            statement.execute("CREATE TABLE message (id INTEGER PRIMARY KEY)");
            statement.execute(operationSql);
            statement.execute("INSERT INTO folder(id) VALUES (1)");
            statement.execute("INSERT INTO message(id) VALUES (10)");
            statement.execute("INSERT INTO operation(id, folder, message, name, args, created, tries) " +
                    "VALUES (20, 1, 10, 'send', '[]', 1, 1)");

            connection.setAutoCommit(false);
            statement.executeUpdate("DELETE FROM message WHERE id=10");
            assertEquals(0, count(statement, "message"));
            assertEquals("SEND operation disappears in the same transaction", 0, count(statement, "operation"));

            connection.rollback();
            assertEquals("Rollback restores both linked rows", 1, count(statement, "message"));
            assertEquals(1, count(statement, "operation"));

            statement.executeUpdate("DELETE FROM message WHERE id=10");
            assertEquals(0, count(statement, "message"));
            assertEquals(0, count(statement, "operation"));
            connection.commit();
            assertEquals(0, count(statement, "message"));
            assertEquals(0, count(statement, "operation"));
        }
    }

    private static String getCurrentOperationCreateSql() throws Exception {
        File[] candidates = {
                new File("app/schemas/eu.faircode.email.DB"),
                new File("schemas/eu.faircode.email.DB"),
                new File("../app/schemas/eu.faircode.email.DB")
        };
        File schemaDirectory = null;
        for (File candidate : candidates)
            if (candidate.isDirectory()) {
                schemaDirectory = candidate;
                break;
            }
        if (schemaDirectory == null)
            throw new IllegalStateException("Room schema directory was not found");

        File[] schemas = schemaDirectory.listFiles((dir, name) -> name.matches("\\d+\\.json"));
        if (schemas == null || schemas.length == 0)
            throw new IllegalStateException("No exported Room schema versions were found");
        Arrays.sort(schemas, (left, right) -> Integer.compare(
                schemaVersion(right), schemaVersion(left)));

        String json = new String(Files.readAllBytes(schemas[0].toPath()), StandardCharsets.UTF_8);
        JSONArray entities = new JSONObject(json).getJSONObject("database").getJSONArray("entities");
        for (int index = 0; index < entities.length(); index++) {
            JSONObject entity = entities.getJSONObject(index);
            if ("operation".equals(entity.getString("tableName")))
                return entity.getString("createSql");
        }
        throw new IllegalStateException("Operation table was not present in the current Room schema");
    }

    private static int schemaVersion(File schema) {
        return Integer.parseInt(schema.getName().substring(0, schema.getName().length() - ".json".length()));
    }

    private static int count(Statement statement, String table) throws Exception {
        try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            if (result.next())
                return result.getInt(1);
            throw new IllegalStateException("No count result for " + table);
        }
    }
}
