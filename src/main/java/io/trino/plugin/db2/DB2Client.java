/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.db2;

import com.google.inject.Inject;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.LongReadFunction;
import io.trino.plugin.jdbc.ObjectReadFunction;
import io.trino.plugin.jdbc.ObjectWriteFunction;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.type.*;


import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.List;
import java.util.Optional;

import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.StandardColumnMappings.*;
import static io.trino.plugin.jdbc.TypeHandlingJdbcSessionProperties.getUnsupportedTypeHandling;
import static io.trino.plugin.jdbc.UnsupportedTypeHandling.CONVERT_TO_VARCHAR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DecimalType.createDecimalType;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TinyintType.TINYINT;
import static io.trino.spi.type.VarbinaryType.VARBINARY;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static java.lang.Math.max;
import static java.lang.String.format;
import static java.util.Locale.ENGLISH;
import static java.util.stream.Collectors.joining;
import static org.weakref.jmx.$internal.guava.base.Preconditions.checkArgument;
import static org.weakref.jmx.$internal.guava.base.Verify.verify;

public class DB2Client extends BaseJdbcClient {
    private final int varcharMaxLength;
    private static final int DB2_MAX_SUPPORTED_TIMESTAMP_PRECISION = 12;
    // java.util.LocalDateTime supports up to nanosecond precision
    private static final int MAX_LOCAL_DATE_TIME_PRECISION = 9;
    private static final String VARCHAR_FORMAT = "VARCHAR(%d)";

    @Inject
    public DB2Client(BaseJdbcConfig config, DB2Config db2config, ConnectionFactory connectionFactory, QueryBuilder queryBuilder, TypeManager typeManager, IdentifierMapping identifierMapping, RemoteQueryModifier remoteQueryModifier) {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, remoteQueryModifier, true);
        this.varcharMaxLength = db2config.getVarcharMaxLength();

        // http://stackoverflow.com/questions/16910791/getting-error-code-4220-with-null-sql-state
        System.setProperty("db2.jcc.charsetDecoderEncoder", "3");
    }

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle) {
        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }

        switch (typeHandle.jdbcType()) {
            case Types.BIT:
            case Types.BOOLEAN:
                return Optional.of(booleanColumnMapping());

            case Types.TINYINT:
                return Optional.of(tinyintColumnMapping());

            case Types.SMALLINT:
                return Optional.of(smallintColumnMapping());

            case Types.INTEGER:
                return Optional.of(integerColumnMapping());

            case Types.BIGINT:
                return Optional.of(bigintColumnMapping());

            case Types.REAL:
                return Optional.of(realColumnMapping());

            case Types.FLOAT:
            case Types.DOUBLE:
                return Optional.of(doubleColumnMapping());

            case Types.NUMERIC:
            case Types.DECIMAL:
                int decimalDigits = typeHandle.requiredDecimalDigits();
                // Map decimal(p, -s) (negative scale) to decimal(p+s, 0).
                int precision = typeHandle.requiredColumnSize() + max(-decimalDigits, 0);
                if (precision > Decimals.MAX_PRECISION) {
                    break;
                }
                return Optional.of(decimalColumnMapping(createDecimalType(precision, max(decimalDigits, 0))));

            case Types.CHAR:
            case Types.NCHAR:
                return Optional.of(defaultCharColumnMapping(typeHandle.requiredColumnSize(), false));

            case Types.VARCHAR:
                int columnSize = typeHandle.requiredColumnSize();
                if (columnSize == -1) {
                    return Optional.of(varcharColumnMapping(createUnboundedVarcharType(), true));
                }
                return Optional.of(defaultVarcharColumnMapping(columnSize, true));

            case Types.NVARCHAR:
            case Types.LONGVARCHAR:
            case Types.LONGNVARCHAR:
                return Optional.of(defaultVarcharColumnMapping(typeHandle.requiredColumnSize(), false));

            case Types.BINARY:
            case Types.VARBINARY:
            case Types.LONGVARBINARY:
                return Optional.of(varbinaryColumnMapping());

            case Types.DATE:
                return Optional.of(dateColumnMappingUsingLocalDate());

            case Types.TIME:
                return Optional.of(timeColumnMapping(TimeType.TIME_MILLIS));

            case Types.TIMESTAMP:
                TimestampType timestampType = typeHandle.decimalDigits().map(TimestampType::createTimestampType).orElse(TIMESTAMP_MILLIS);
                return Optional.of(timestampColumnMapping(timestampType));
        }

        if (getUnsupportedTypeHandling(session) == CONVERT_TO_VARCHAR) {
            return mapToUnboundedVarchar(typeHandle);
        }
        return Optional.empty();
    }

    public static ColumnMapping timestampColumnMapping(TimestampType timestampType) {
        if (timestampType.getPrecision() <= TimestampType.MAX_SHORT_PRECISION) {
            return ColumnMapping.longMapping(timestampType, timestampReadFunction(timestampType), timestampWriteFunction(timestampType));
        }
        checkArgument(timestampType.getPrecision() <= MAX_LOCAL_DATE_TIME_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return ColumnMapping.objectMapping(timestampType, longtimestampReadFunction(timestampType), longTimestampWriteFunction(timestampType));
    }

    public static LongReadFunction timestampReadFunction(TimestampType timestampType) {
        checkArgument(timestampType.getPrecision() <= TimestampType.MAX_SHORT_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return (resultSet, columnIndex) -> toTrinoTimestamp(timestampType, resultSet.getTimestamp(columnIndex).toLocalDateTime());
    }

    /**
     * Customized timestampReadFunction to convert timestamp type to LocalDateTime type.
     * Notice that it's because Db2 JDBC driver doesn't support {@link java.sql.ResultSet#getObject(int, Class)}. (index, Class<T>)
     * <p>
     * 自定义 timestampReadFunction，将时间戳类型转换为 LocalDateTime 类型。
     * 请注意，这是因为 Db2 JDBC 驱动程序不支持{@link java.sql.ResultSet#getObject(int, Class)} )}
     *
     * @param timestampType
     * @return
     */
    private static ObjectReadFunction longtimestampReadFunction(TimestampType timestampType) {
        checkArgument(timestampType.getPrecision() <= MAX_LOCAL_DATE_TIME_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return ObjectReadFunction.of(LongTimestamp.class, (resultSet, columnIndex) -> toLongTrinoTimestamp(timestampType, resultSet.getTimestamp(columnIndex).toLocalDateTime()));
    }

    /**
     * Customized timestampWriteFunction.
     * Notice that it's because Db2 JDBC driver doesn't support {@link java.sql.PreparedStatement#setObject(int, Object)}. (index, LocalDateTime)
     * <p>
     * 自定义timestampWriteFunction。
     * 请注意，这是因为 Db2 JDBC 驱动程序不支持{@link java.sql.PreparedStatement#setObject(int, Object)}
     *
     * @param timestampType
     * @return
     */
    private static ObjectWriteFunction longTimestampWriteFunction(TimestampType timestampType) {
        checkArgument(timestampType.getPrecision() > TimestampType.MAX_SHORT_PRECISION, "Precision is out of range: %s", timestampType.getPrecision());
        return ObjectWriteFunction.of(LongTimestamp.class, (statement, index, value) -> statement.setTimestamp(index, Timestamp.valueOf(fromLongTrinoTimestamp(value, timestampType.getPrecision()))));
    }

    /**
     * To map data types when generating SQL.
     */
    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type) {
        if (type instanceof VarcharType varcharType) {
            String dataType;

            if (varcharType.isUnbounded()) {
                dataType = format(VARCHAR_FORMAT, this.varcharMaxLength);
            } else if (varcharType.getBoundedLength() > this.varcharMaxLength) {
                dataType = format("CLOB(%d)", varcharType.getBoundedLength());
            } else if (varcharType.getBoundedLength() < this.varcharMaxLength) {
                dataType = format(VARCHAR_FORMAT, varcharType.getBoundedLength());
            } else {
                dataType = format(VARCHAR_FORMAT, this.varcharMaxLength);
            }

            return WriteMapping.sliceMapping(dataType, varcharWriteFunction());
        }

        if (type instanceof TimestampType timestampType) {
            verify(timestampType.getPrecision() <= DB2_MAX_SUPPORTED_TIMESTAMP_PRECISION);
            return WriteMapping.longMapping(format("TIMESTAMP(%s)", timestampType.getPrecision()), timestampWriteFunction(timestampType));
        }

        return this.legacyToWriteMapping(session, type);
    }

    protected WriteMapping legacyToWriteMapping(ConnectorSession session, Type type) {
        if (type instanceof VarcharType varcharType) {
            String dataType;
            if (varcharType.isUnbounded()) {
                dataType = "varchar";
            } else {
                dataType = "varchar(" + varcharType.getBoundedLength() + ")";
            }
            return WriteMapping.sliceMapping(dataType, varcharWriteFunction());
        }
        if (type instanceof CharType) {
            return WriteMapping.sliceMapping("char(" + ((CharType) type).getLength() + ")", charWriteFunction());
        }
        if (type instanceof DecimalType decimalType) {
            String dataType = format("decimal(%s, %s)", decimalType.getPrecision(), decimalType.getScale());
            if (decimalType.isShort()) {
                return WriteMapping.longMapping(dataType, shortDecimalWriteFunction(decimalType));
            }
            return WriteMapping.objectMapping(dataType, longDecimalWriteFunction(decimalType));
        }

        if (type == BOOLEAN) {
            return WriteMapping.booleanMapping("boolean", booleanWriteFunction());
        }
        if (type == TINYINT) {
            return WriteMapping.longMapping("tinyint", tinyintWriteFunction());
        }
        if (type == SMALLINT) {
            return WriteMapping.longMapping("smallint", smallintWriteFunction());
        }
        if (type == INTEGER) {
            return WriteMapping.longMapping("integer", integerWriteFunction());
        }
        if (type == BIGINT) {
            return WriteMapping.longMapping("bigint", bigintWriteFunction());
        }
        if (type == REAL) {
            return WriteMapping.longMapping("real", realWriteFunction());
        }
        if (type == DOUBLE) {
            return WriteMapping.doubleMapping("double precision", doubleWriteFunction());
        }
        if (type == VARBINARY) {
            return WriteMapping.sliceMapping("varbinary", varbinaryWriteFunction());
        }
        if (type == DATE) {
            WriteMapping.longMapping("date", dateWriteFunctionUsingLocalDate());
        }
        throw new TrinoException(NOT_SUPPORTED, "Unsupported column type: " + type.getDisplayName());
    }

    @Override
    protected void renameTable(ConnectorSession session, String catalogName, String schemaName, String tableName, SchemaTableName newTable) {
        try (Connection connection = connectionFactory.openConnection(session)) {
            String newTableName = newTable.getTableName();
            if (connection.getMetaData().storesUpperCaseIdentifiers()) {
                newTableName = newTableName.toUpperCase(ENGLISH);
            }
            // Specifies the new name for the table without a schema name
            String sql = format("RENAME TABLE %s TO %s", quoted(catalogName, schemaName, tableName), quoted(newTableName));
            execute(session, connection, sql);
        } catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    @Override
    protected void copyTableSchema(ConnectorSession session, Connection connection, String catalogName, String schemaName, String tableName, String newTableName, List<String> columnNames) {
        String sql = format("CREATE TABLE %s AS (SELECT %s FROM %s) WITH NO DATA", quoted(catalogName, schemaName, newTableName), columnNames.stream().map(this::quoted).collect(joining(", ")), quoted(catalogName, schemaName, tableName));
        try {
            execute(session, connection, sql);
        } catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }
}
