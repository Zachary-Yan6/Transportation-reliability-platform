package com.zachary.transportation_reliability_platform.config.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.UUID;

/**
 * Converts between Java UUID and PostgreSQL uuid values.
 */
@MappedTypes(UUID.class)
@MappedJdbcTypes(value = JdbcType.OTHER, includeNullJdbcType = true)
public class PostgreSqlUuidTypeHandler extends BaseTypeHandler<UUID> {

    @Override
    public void setNonNullParameter(
            PreparedStatement statement,
            int parameterIndex,
            UUID parameter,
            JdbcType jdbcType
    ) throws SQLException {
        statement.setObject(parameterIndex, parameter, Types.OTHER);
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet,
            String columnName
    ) throws SQLException {
        return toUuid(resultSet.getObject(columnName));
    }

    @Override
    public UUID getNullableResult(
            ResultSet resultSet,
            int columnIndex
    ) throws SQLException {
        return toUuid(resultSet.getObject(columnIndex));
    }

    @Override
    public UUID getNullableResult(
            CallableStatement statement,
            int columnIndex
    ) throws SQLException {
        return toUuid(statement.getObject(columnIndex));
    }

    private UUID toUuid(Object value) {
        if (value == null) {
            return null;
        }

        if (value instanceof UUID uuid) {
            return uuid;
        }

        return UUID.fromString(value.toString());
    }
}
