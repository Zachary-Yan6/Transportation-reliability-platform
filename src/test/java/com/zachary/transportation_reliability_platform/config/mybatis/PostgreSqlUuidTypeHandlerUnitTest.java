package com.zachary.transportation_reliability_platform.config.mybatis;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Types;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Verifies PostgreSQL UUID values are handled as UUIDs, strings, and SQL nulls. */
class PostgreSqlUuidTypeHandlerUnitTest {

    @Test
    void convertsEverySupportedJdbcSourceAndPreservesNulls() throws Exception {
        PostgreSqlUuidTypeHandler handler = new PostgreSqlUuidTypeHandler();
        UUID uuid = UUID.fromString("0a72c0e9-fae6-4c04-be5b-551150425622");
        PreparedStatement prepared = mock(PreparedStatement.class);
        ResultSet resultSet = mock(ResultSet.class);
        CallableStatement callable = mock(CallableStatement.class);
        when(resultSet.getObject("uuid")).thenReturn(uuid);
        when(resultSet.getObject(2)).thenReturn(uuid.toString());
        when(callable.getObject(3)).thenReturn(null);

        handler.setNonNullParameter(prepared, 1, uuid, JdbcType.OTHER);

        assertThat(handler.getNullableResult(resultSet, "uuid")).isEqualTo(uuid);
        assertThat(handler.getNullableResult(resultSet, 2)).isEqualTo(uuid);
        assertThat(handler.getNullableResult(callable, 3)).isNull();
        verify(prepared).setObject(1, uuid, Types.OTHER);
    }
}
