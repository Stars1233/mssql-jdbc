package com.microsoft.sqlserver.jdbc.unit.lobs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.NClob;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.platform.runner.JUnitPlatform;
import org.junit.runner.RunWith;

import com.microsoft.sqlserver.jdbc.RandomUtil;
import com.microsoft.sqlserver.jdbc.TestUtils;
import com.microsoft.sqlserver.testframework.AbstractSQLGenerator;
import com.microsoft.sqlserver.testframework.AbstractTest;
import com.microsoft.sqlserver.testframework.Constants;


@RunWith(JUnitPlatform.class)
@Tag(Constants.xAzureSQLDW)
public class LobsStreamingTest extends AbstractTest {

    private static String tableName = null;

    @BeforeAll
    public static void setupTests() throws Exception {
        setConnection();
    }

    @BeforeEach
    public void init() throws SQLException {
        tableName = RandomUtil.getIdentifier("streamingTest");
    }

    private String getRandomString(int length, String validCharacters) {
        StringBuilder salt = new StringBuilder();
        while (salt.length() < length) {
            int index = (int) (Constants.RANDOM.nextFloat() * validCharacters.length());
            salt.append(validCharacters.charAt(index));
        }
        String saltStr = salt.toString();
        return saltStr;
    }

    // closing the scanner closes the InputStream, and the driver needs the stream to fill LoBs
    private String getStringFromInputStream(InputStream is, Scanner s) {
        return s.hasNext() ? s.next() : "";
    }

    private String getStringFromReader(Reader r, long l) throws IOException {
        // read the Reader contents into a buffer and return the complete string
        final StringBuilder stringBuilder = new StringBuilder((int) l);
        char[] buffer = new char[(int) l];
        int amountRead = -1;
        while ((amountRead = r.read(buffer, 0, (int) l)) != -1) {
            stringBuilder.append(buffer, 0, amountRead);
        }
        return stringBuilder.toString();
    }

    private void createLobTable(Statement stmt, String table, Constants.LOB l) throws SQLException {
        String columnType = (l == Constants.LOB.CLOB) ? "varchar(max)" : "nvarchar(max)";
        stmt.execute("CREATE TABLE " + AbstractSQLGenerator.escapeIdentifier(table) + " (id int, lobValue " + columnType
                + ")");
    }

    private ArrayList<String> createRandomStringArray(Constants.LOB l) {
        String characterPool = (l == Constants.LOB.CLOB) ? Constants.ASCII_CHARACTERS : Constants.UNICODE_CHARACTERS;
        ArrayList<String> string_array = new ArrayList<>();
        IntStream.range(0, Constants.LOB_ARRAY_SIZE).forEach(i -> string_array.add(getRandomString(
                Constants.RANDOM.nextInt(Constants.LOB_LENGTH_MIN, Constants.LOB_LENGTH_MAX), characterPool)));
        return string_array;
    }

    private void insertData(Connection conn, String table, ArrayList<String> lobs) throws SQLException {
        try (PreparedStatement pstmt = conn.prepareStatement("INSERT INTO [" + table + "] VALUES(?,?)")) {
            for (int i = 0; i < lobs.size(); i++) {
                Clob c = conn.createClob();
                c.setString(1, lobs.get(i));
                pstmt.setInt(1, i);
                pstmt.setClob(2, c);
                pstmt.addBatch();
            }
            pstmt.executeBatch();
        }
    }

    @Test
    @DisplayName("testLengthAfterStream")
    public void testLengthAfterStream() throws SQLException, IOException {
        try (Connection conn = getConnection();) {
            try (Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);
                ArrayList<String> lob_data = createRandomStringArray(Constants.LOB.CLOB);

                createLobTable(stmt, tableName, Constants.LOB.CLOB);
                insertData(conn, tableName, lob_data);

                try (ResultSet rs = stmt.executeQuery("SELECT * FROM [" + tableName + "] ORDER BY id ASC")) {
                    while (rs.next()) {
                        Clob c = rs.getClob(2);
                        try (Reader r = c.getCharacterStream()) {
                            long clobLength = c.length();
                            String received = getStringFromReader(r, clobLength);// streaming string
                            c.free();
                            assertEquals(lob_data.get(rs.getInt(1)), received);// compare streamed string to initial
                                                                               // string
                        }
                    }
                }
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    TestUtils.dropTableIfExists(tableName, stmt);
                }
            }
        }
    }

    @Test
    @DisplayName("testClobsVarcharASCII")
    public void testClobsVarcharASCII() throws SQLException, IOException {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);

                ArrayList<String> lob_data = createRandomStringArray(Constants.LOB.CLOB);
                ArrayList<String> receivedDataFromServer = new ArrayList<>();

                createLobTable(stmt, tableName, Constants.LOB.CLOB);
                insertData(conn, tableName, lob_data);

                try (ResultSet rs = stmt.executeQuery("SELECT * FROM [" + tableName + "] ORDER BY id ASC")) {
                    while (rs.next()) {
                        int index = rs.getInt(1);
                        Clob c = rs.getClob(2);
                        assertEquals(c.length(), lob_data.get(index).length());
                        try (InputStream is = c.getAsciiStream(); Scanner s = new Scanner(is, "US-ASCII")) {
                            // streaming string
                            String received = getStringFromInputStream(is, s.useDelimiter("\\A"));
                            // compare streamed string to initial string
                            assertEquals(lob_data.get(index), received);
                            c.free();
                            receivedDataFromServer.add(received);
                        }
                    }
                    for (int i = 0; i < lob_data.size(); i++) {
                        // compare satic string to streamed string
                        assertEquals(receivedDataFromServer.get(i), lob_data.get(i));
                    }
                }
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    TestUtils.dropTableIfExists(tableName, stmt);
                }
            }
        }

    }

    @Test
    @DisplayName("testNClobsNVarcharASCII")
    public void testNClobsVarcharASCII() throws SQLException, IOException {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);
                // Testing AsciiStream, use Clob string set or characters will be converted to '?'
                ArrayList<String> lob_data = createRandomStringArray(Constants.LOB.CLOB);

                createLobTable(stmt, tableName, Constants.LOB.NCLOB);
                insertData(conn, tableName, lob_data);

                try (ResultSet rs = stmt.executeQuery("SELECT * FROM [" + tableName + "] ORDER BY id ASC")) {
                    while (rs.next()) {
                        int index = rs.getInt(1);
                        NClob c = rs.getNClob(2);
                        assertEquals(c.length(), lob_data.get(index).length());
                        try (InputStream is = c.getAsciiStream(); Scanner s = new Scanner(is, "US-ASCII")) {
                            // nClob AsciiStream is never streamed
                            String received = getStringFromInputStream(is, s.useDelimiter("\\A"));
                            c.free();
                            assertEquals(lob_data.get(index), received);// compare string to initial string
                        }
                    }
                }
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    TestUtils.dropTableIfExists(tableName, stmt);
                }
            }
        }
    }

    @Test
    @DisplayName("testClobsVarcharCHARA")
    public void testClobsVarcharCHARA() throws SQLException, IOException {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);

                ArrayList<String> lob_data = createRandomStringArray(Constants.LOB.CLOB);
                ArrayList<String> receivedDataFromServer = new ArrayList<>();

                createLobTable(stmt, tableName, Constants.LOB.CLOB);
                insertData(conn, tableName, lob_data);

                try (ResultSet rs = stmt.executeQuery("SELECT * FROM [" + tableName + "] ORDER BY id ASC")) {
                    while (rs.next()) {
                        int index = rs.getInt(1);
                        Clob c = rs.getClob(2);
                        assertEquals(c.length(), lob_data.get(index).length());
                        try (Reader reader = c.getCharacterStream()) {
                            String received = getStringFromReader(reader, c.length());// streaming string
                            receivedDataFromServer.add(received);
                            assertEquals(lob_data.get(index), received);// compare streamed string to initial string
                            c.free();
                        }
                    }
                }
                for (int i = 0; i < lob_data.size(); i++) {
                    assertEquals(receivedDataFromServer.get(i), lob_data.get(i));// compare static string to streamed
                                                                                 // string
                }
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    TestUtils.dropTableIfExists(tableName, stmt);
                }
            }
        }
    }

    @Test
    @DisplayName("testNClobsVarcharCHARA")
    public void testNClobsVarcharCHARA() throws SQLException, IOException {
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);

                ArrayList<String> lob_data = createRandomStringArray(Constants.LOB.NCLOB);
                ArrayList<String> receivedDataFromServer = new ArrayList<>();

                createLobTable(stmt, tableName, Constants.LOB.NCLOB);
                insertData(conn, tableName, lob_data);

                try (ResultSet rs = stmt.executeQuery("SELECT * FROM [" + tableName + "] ORDER BY id ASC")) {
                    while (rs.next()) {
                        int index = rs.getInt(1);
                        NClob c = rs.getNClob(2);
                        assertEquals(c.length(), lob_data.get(index).length());
                        try (Reader reader = c.getCharacterStream()) {
                            String received = getStringFromReader(reader, c.length());// streaming string
                            receivedDataFromServer.add(received);
                            assertEquals(lob_data.get(index), received);// compare streamed string to initial string
                            c.free();
                        }
                    }
                }
                for (int i = 0; i < lob_data.size(); i++) {
                    assertEquals(receivedDataFromServer.get(i), lob_data.get(i));// compare static string to streamed
                                                                                 // string
                }
            } finally {
                try (Statement stmt = conn.createStatement()) {
                    TestUtils.dropTableIfExists(tableName, stmt);
                }
            }
        }
    }

    @Nested
    public class TestPLP {
        private String tableName;

        @AfterEach
        public void cleanUp() {
            try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
                TestUtils.dropTableIfExists(tableName, stmt);
            } catch (SQLException ex) {
                fail(ex.getMessage());
            }
        }

        @Test
        public void testGetAsciiStreamOnXml() {
            tableName = TestUtils.escapeSingleQuotes(AbstractSQLGenerator.escapeIdentifier(RandomUtil.getIdentifier("TestXmlTable")));
            try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE " + tableName + " (col1 XML NULL)");
                stmt.executeUpdate("INSERT INTO " + tableName + " (col1) VALUES ('<root><child>Hello</child></root>')");
                stmt.executeUpdate("INSERT INTO " + tableName + " (col1) VALUES (NULL)");

                try (ResultSet rs = stmt.executeQuery("SELECT col1 FROM " + tableName)) {
                    while (rs.next()) {
                        try {
                            InputStream asciiStream = rs.getAsciiStream(1);
                            // If no exception is thrown, assert the value is null
                            assertNull(asciiStream, "Expected null for NULL value, but got a non-null InputStream");
                        } catch (SQLException e) {
                            // Ensure that only expected exceptions occur
                            assertTrue(e.getMessage().contains("The conversion from xml to AsciiStream is unsupported."),
                                    "Unexpected SQLException message: " + e.getMessage());
                        }
                    }
                }
            } catch (SQLException e) {
                fail("Database setup or execution failed: " + e.getMessage());
            }
        }

        @Test
        public void testGetBinaryStreamOnVarchar() {
            tableName = TestUtils.escapeSingleQuotes(AbstractSQLGenerator.escapeIdentifier(RandomUtil.getIdentifier("TestPLPTable")));
            try (Connection conn = getConnection();
                Statement stmt = conn.createStatement()) {
                stmt.executeUpdate("CREATE TABLE " + tableName + " (col1 VARCHAR(50) NULL)");
                stmt.executeUpdate("INSERT INTO " + tableName + " (col1) VALUES ('TestValue')");
                stmt.executeUpdate("INSERT INTO " + tableName + " (col1) VALUES (NULL)");

                try (ResultSet rs = stmt.executeQuery("SELECT col1 FROM " + tableName)) {
                    while (rs.next()) {
                        try {
                            InputStream binaryStream = rs.getBinaryStream(1);
                            // If no exception is thrown, assert the value is null
                            assertNull(binaryStream, "Expected null for NULL value, but got a non-null InputStream");
                        } catch (SQLException e) {
                            // Ensure that only expected exceptions occur
                            assertTrue(e.getMessage().contains("The conversion from varchar to BinaryStream is unsupported."),
                                    "Unexpected SQLException message: " + e.getMessage());
                        }
                    }
                }
            } catch (SQLException e) {
                fail("Database setup or execution failed: " + e.getMessage());
            }
        }
    }
}