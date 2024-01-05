/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.postgres.typing_deduping;

import static io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT;
import static io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_LOADED_AT;
import static io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_AB_META;
import static io.airbyte.cdk.integrations.base.JavaBaseConstants.COLUMN_NAME_DATA;
import static org.jooq.impl.DSL.array;
import static org.jooq.impl.DSL.case_;
import static org.jooq.impl.DSL.cast;
import static org.jooq.impl.DSL.field;
import static org.jooq.impl.DSL.function;
import static org.jooq.impl.DSL.name;
import static org.jooq.impl.DSL.quotedName;
import static org.jooq.impl.DSL.rowNumber;
import static org.jooq.impl.DSL.val;

import com.google.common.collect.ImmutableMap;
import io.airbyte.cdk.integrations.base.JavaBaseConstants;
import io.airbyte.cdk.integrations.destination.NamingConventionTransformer;
import io.airbyte.cdk.integrations.destination.jdbc.TableDefinition;
import io.airbyte.cdk.integrations.destination.jdbc.typing_deduping.JdbcSqlGenerator;
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteProtocolType;
import io.airbyte.integrations.base.destination.typing_deduping.AirbyteType;
import io.airbyte.integrations.base.destination.typing_deduping.Array;
import io.airbyte.integrations.base.destination.typing_deduping.ColumnId;
import io.airbyte.integrations.base.destination.typing_deduping.Sql;
import io.airbyte.integrations.base.destination.typing_deduping.StreamConfig;
import io.airbyte.integrations.base.destination.typing_deduping.Struct;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.jooq.Condition;
import org.jooq.DataType;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DefaultDataType;
import org.jooq.impl.SQLDataType;

public class PostgresSqlGenerator extends JdbcSqlGenerator {

  public static final DataType<?> JSONB_TYPE = new DefaultDataType<>(null, Object.class, "jsonb");

  private static final Map<String, String> POSTGRES_TYPE_NAME_TO_JDBC_TYPE = ImmutableMap.of(
      "numeric", "decimal",
      "int8", "bigint",
      "bool", "boolean",
      "timestamptz", "timestamp with time zone",
      "timetz", "time with time zone");

  public PostgresSqlGenerator(final NamingConventionTransformer namingTransformer) {
    super(namingTransformer);
  }

  @Override
  public Sql setup() {
    return Sql.separately(
        // safe_cast implementation copied from https://dba.stackexchange.com/a/203986
        // and wrapped in the idempotent function creation logic from https://stackoverflow.com/a/69547602

        // This is pretty hacky. It's to handle the fact that postgres disallows concurrent
        // `CREATE OR REPLACE FUNCTION airbyte_safe_cast` calls.
        // It's possible for two syncs to start running at the same time, and we can't coordinate
        // between destination-postgres instances, so we need to make sure that they can both successfully
        // run this function creation logic without failing.
        """
        do $$
        begin
            CREATE FUNCTION airbyte_safe_cast(_in text, INOUT _out ANYELEMENT)
              LANGUAGE plpgsql AS
            $func$
            BEGIN
                EXECUTE format('SELECT %L::%s', $1, pg_typeof(_out))
                INTO  _out;
            EXCEPTION WHEN others THEN
                -- do nothing: _out already carries default
            END
            $func$;
          exception
            when duplicate_function then
            null;
        end; $$
        """);
  }

  @Override
  protected DataType<?> getStructType() {
    return JSONB_TYPE;
  }

  @Override
  protected DataType<?> getArrayType() {
    return JSONB_TYPE;
  }

  @Override
  protected DataType<?> getWidestType() {
    return JSONB_TYPE;
  }

  @Override
  protected SQLDialect getDialect() {
    return SQLDialect.POSTGRES;
  }

  @Override
  protected List<Field<?>> extractRawDataFields(final LinkedHashMap<ColumnId, AirbyteType> columns, final boolean useExpensiveSaferCasting) {
    return columns
        .entrySet()
        .stream()
        .map(column -> castedField(
            extractColumnAsJson(column.getKey()),
            column.getValue(),
            column.getKey().name(),
            useExpensiveSaferCasting))
        .collect(Collectors.toList());
  }

  @Override
  protected Field<?> castedField(
                                 final Field<?> field,
                                 final AirbyteType type,
                                 final String alias,
                                 final boolean useExpensiveSaferCasting) {
    return castedField(field, type, useExpensiveSaferCasting).as(quotedName(alias));
  }

  protected Field<?> castedField(
                                 final Field<?> field,
                                 final AirbyteType type,
                                 final boolean useExpensiveSaferCasting) {
    if (type instanceof Struct) {
      // If this field is a struct, verify that the raw data is an object.
      return cast(
          case_()
              .when(field.isNull().or(jsonTypeof(field).ne("object")), val((Object) null))
              .else_(field),
          JSONB_TYPE);
    } else if (type instanceof Array) {
      // Do the same for arrays.
      return cast(
          case_()
              .when(field.isNull().or(jsonTypeof(field).ne("array")), val((Object) null))
              .else_(field),
          JSONB_TYPE);
    } else if (type == AirbyteProtocolType.UNKNOWN) {
      return cast(field, JSONB_TYPE);
    } else if (type == AirbyteProtocolType.STRING) {
      // we need to render the jsonb to a normal string. For strings, this is the difference between
      // "\"foo\"" and "foo".
      // postgres provides the #>> operator, which takes a json path and returns that extraction as a
      // string.
      // '{}' is an empty json path (it's an empty array literal), so it just stringifies the json value.
      return field("{0} #>> '{}'", String.class, field);
    } else {
      final DataType<?> dialectType = toDialectType(type);
      if (useExpensiveSaferCasting) {
        return function("airbyte_safe_cast", dialectType, cast(field, SQLDataType.VARCHAR), cast(val((Object) null), dialectType));
      } else {
        return cast(field, dialectType);
      }
    }
  }

  // TODO this isn't actually used right now... can we refactor this out?
  // (redshift is doing something interesting with this method, so leaving it for now)
  @Override
  protected Field<?> castedField(final Field<?> field, final AirbyteProtocolType type, final boolean useExpensiveSaferCasting) {
    return cast(field, toDialectType(type));
  }

  @Override
  protected Field<?> buildAirbyteMetaColumn(final LinkedHashMap<ColumnId, AirbyteType> columns) {
    final Field<?>[] dataFieldErrors = columns
        .entrySet()
        .stream()
        .map(column -> toCastingErrorCaseStmt(column.getKey(), column.getValue()))
        .toArray(Field<?>[]::new);
    return function(
        "JSONB_BUILD_OBJECT",
        JSONB_TYPE,
        val("errors"),
        function("ARRAY_REMOVE", JSONB_TYPE, array(dataFieldErrors), val((String) null))).as(COLUMN_NAME_AB_META);
  }

  private Field<String> toCastingErrorCaseStmt(final ColumnId column, final AirbyteType type) {
    final Field<?> extract = extractColumnAsJson(column);
    if (type instanceof Struct) {
      // If this field is a struct, verify that the raw data is an object or null.
      return case_()
          .when(
              extract.isNotNull()
                  .and(jsonTypeof(extract).notIn("object", "null")),
              val("Problem with `" + column.originalName() + "`"))
          .else_(val((String) null));
    } else if (type instanceof Array) {
      // Do the same for arrays.
      return case_()
          .when(
              extract.isNotNull()
                  .and(jsonTypeof(extract).notIn("array", "null")),
              val("Problem with `" + column.originalName() + "`"))
          .else_(val((String) null));
    } else if (type == AirbyteProtocolType.UNKNOWN || type == AirbyteProtocolType.STRING) {
      // Unknown types require no casting, so there's never an error.
      // Similarly, everything can cast to string without error.
      return val((String) null);
    } else {
      // For other type: If the raw data is not NULL or 'null', but the casted data is NULL,
      // then we have a typing error.
      return case_()
          .when(
              extract.isNotNull()
                  .and(jsonTypeof(extract).ne("null"))
                  .and(castedField(extractColumnAsJson(column), type, true).isNull()),
              val("Problem with `" + column.originalName() + "`"))
          .else_(val((String) null));
    }
  }

  @Override
  protected Condition cdcDeletedAtNotNullCondition() {
    return field(name(COLUMN_NAME_AB_LOADED_AT)).isNotNull()
        .and(jsonTypeof(extractColumnAsJson(cdcDeletedAtColumn)).ne("null"));
  }

  @Override
  protected Field<Integer> getRowNumber(final List<ColumnId> primaryKeys, final Optional<ColumnId> cursor) {
    // literally identical to redshift's getRowNumber implementation, changes here probably should
    // be reflected there
    final List<Field<?>> primaryKeyFields =
        primaryKeys != null ? primaryKeys.stream().map(columnId -> field(quotedName(columnId.name()))).collect(Collectors.toList())
            : new ArrayList<>();
    final List<Field<?>> orderedFields = new ArrayList<>();
    // We can still use Jooq's field to get the quoted name with raw sql templating.
    // jooq's .desc returns SortField<?> instead of Field<?> and NULLS LAST doesn't work with it
    cursor.ifPresent(columnId -> orderedFields.add(field("{0} desc NULLS LAST", field(quotedName(columnId.name())))));
    orderedFields.add(field("{0} desc", quotedName(COLUMN_NAME_AB_EXTRACTED_AT)));
    return rowNumber()
        .over()
        .partitionBy(primaryKeyFields)
        .orderBy(orderedFields).as(ROW_NUMBER_COLUMN_NAME);
  }

  @Override
  public boolean existingSchemaMatchesStreamConfig(final StreamConfig stream, final TableDefinition existingTable) {
    // Check that the columns match, with special handling for the metadata columns.
    // This is mostly identical to the redshift implementation, but swaps super to jsonb
    final LinkedHashMap<String, String> intendedColumns = stream.columns().entrySet().stream()
        .collect(LinkedHashMap::new,
            (map, column) -> map.put(column.getKey().name(), toDialectType(column.getValue()).getTypeName()),
            LinkedHashMap::putAll);
    final LinkedHashMap<String, String> actualColumns = existingTable.columns().entrySet().stream()
        .filter(column -> JavaBaseConstants.V2_FINAL_TABLE_METADATA_COLUMNS.stream()
            .noneMatch(airbyteColumnName -> airbyteColumnName.equals(column.getKey())))
        .collect(LinkedHashMap::new,
            (map, column) -> map.put(column.getKey(), jdbcTypeNameFromPostgresTypeName(column.getValue().type())),
            LinkedHashMap::putAll);

    final boolean sameColumns = actualColumns.equals(intendedColumns)
        && "varchar".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_AB_RAW_ID).type())
        && "timestamptz".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_AB_EXTRACTED_AT).type())
        && "jsonb".equals(existingTable.columns().get(JavaBaseConstants.COLUMN_NAME_AB_META).type());

    return sameColumns;
  }

  /**
   * Extract a raw field, leaving it as jsonb
   */
  private Field<?> extractColumnAsJson(final ColumnId column) {
    return field("{0} -> {1}", name(COLUMN_NAME_DATA), val(column.originalName()));
  }

  private Field<String> jsonTypeof(final Field<?> field) {
    return function("JSONB_TYPEOF", SQLDataType.VARCHAR, field);
  }

  private static String jdbcTypeNameFromPostgresTypeName(final String redshiftType) {
    return POSTGRES_TYPE_NAME_TO_JDBC_TYPE.getOrDefault(redshiftType, redshiftType);
  }

}