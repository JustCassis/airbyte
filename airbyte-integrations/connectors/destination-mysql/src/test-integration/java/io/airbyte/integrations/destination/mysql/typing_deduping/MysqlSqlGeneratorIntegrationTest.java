/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.mysql.typing_deduping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.cdk.db.jdbc.DefaultJdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcDatabase;
import io.airbyte.cdk.db.jdbc.JdbcSourceOperations;
import io.airbyte.cdk.db.jdbc.JdbcUtils;
import io.airbyte.cdk.integrations.destination.jdbc.TableDefinition;
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcDestinationHandler;
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator;
import io.airbyte.cdk.integrations.standardtest.destination.typing_deduping.JdbcSqlGeneratorIntegrationTest;
import io.airbyte.commons.json.Jsons;
import io.airbyte.integrations.base.destination.typing_deduping.DestinationHandler;
import io.airbyte.integrations.destination.mysql.MySQLDestination;
import io.airbyte.integrations.destination.mysql.MySQLDestinationAcceptanceTest;
import io.airbyte.integrations.destination.mysql.MySQLNameTransformer;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.conf.ParamType;
import org.jooq.impl.DSL;
import org.jooq.impl.DefaultDataType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;

public class MysqlSqlGeneratorIntegrationTest extends JdbcSqlGeneratorIntegrationTest {

  private static MySQLContainer<?> testContainer;
  private static String databaseName;
  private static JdbcDatabase database;

  public static class MysqlSourceOperations extends JdbcSourceOperations {

    @Override
    public void copyToJsonField(final ResultSet resultSet, final int colIndex, final ObjectNode json) throws SQLException {
      final String columnName = resultSet.getMetaData().getColumnName(colIndex);
      final String columnTypeName = resultSet.getMetaData().getColumnTypeName(colIndex).toLowerCase();

      switch (columnTypeName) {
        // JSONB has no equivalent in JDBCType
        case "json" -> json.set(columnName, Jsons.deserializeExact(resultSet.getString(colIndex)));
        default -> super.copyToJsonField(resultSet, colIndex, json);
      }
    }

  }

  @BeforeAll
  public static void setupMysql() throws Exception {
    testContainer = new MySQLContainer<>("mysql:8.0");
    testContainer.start();
    MySQLDestinationAcceptanceTest.configureTestContainer(testContainer);

    final JsonNode config = MySQLDestinationAcceptanceTest.getConfigFromTestContainer(testContainer);

    // TODO move this into JdbcSqlGeneratorIntegrationTest?
    // This code was largely copied from RedshiftSqlGeneratorIntegrationTest
    // TODO: Existing in AbstractJdbcDestination, pull out to a util file
    databaseName = config.get(JdbcUtils.DATABASE_KEY).asText();
    // TODO: Its sad to instantiate unneeded dependency to construct database and datsources. pull it to
    // static methods.
    final MySQLDestination insertDestination = new MySQLDestination();
    final DataSource dataSource = insertDestination.getDataSource(config);
    database = new DefaultJdbcDatabase(dataSource, new MysqlSourceOperations());
  }

  @AfterAll
  public static void teardownMysql() {
    testContainer.stop();
    testContainer.close();
  }

  @Override
  protected JdbcSqlGenerator getSqlGenerator() {
    return new MysqlSqlGenerator(new MySQLNameTransformer());
  }

  @Override
  protected DestinationHandler<TableDefinition> getDestinationHandler() {
    // Mysql doesn't have an actual schema concept.
    // All of our queries pass a value into the "schemaName" parameter, which mysql treats as being
    // the database name.
    // So we pass null for the databaseName parameter here, because we don't use the 'test' database at all.
    return new JdbcDestinationHandler(null, database, SQLDialect.MYSQL);
  }

  @Test
  @Override
  public void testCreateTableIncremental() throws Exception {
    // TODO
  }

  @Override
  protected JdbcDatabase getDatabase() {
    return database;
  }

  @Override
  protected DataType<?> getStructType() {
    return new DefaultDataType<>(null, String.class, "json");
  }

  @Override
  protected SQLDialect getSqlDialect() {
    return SQLDialect.MYSQL;
  }

  @Override
  protected Field<?> toJsonValue(final String valueAsString) {
    // mysql lets you just insert json strings directly into json columns
    return DSL.val(valueAsString);
  }

  @Override
  protected void teardownNamespace(final String namespace) throws Exception {
    // mysql doesn't have a CASCADE keyword in DROP SCHEMA, so we have to override this method.
    // we're currently on jooq 3.13; jooq's dropDatabase() call was only added in 3.14
    getDatabase().execute(getDslContext().dropSchema(namespace).getSQL(ParamType.INLINED));
  }

  @Override
  public boolean supportsSafeCast() {
    return false;
  }

}