package com.lumi.vault.business.asset.entity;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

@MappedTypes(AssetMetadata.class)
public class AssetMetadataTypeHandler extends BaseTypeHandler<AssetMetadata> {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Override
    public void setNonNullParameter(PreparedStatement statement, int index, AssetMetadata parameter, JdbcType jdbcType)
            throws SQLException {
        statement.setString(index, MAPPER.writeValueAsString(parameter));
    }

    @Override
    public AssetMetadata getNullableResult(ResultSet resultSet, String columnName) throws SQLException {
        return parse(resultSet.getString(columnName));
    }

    @Override
    public AssetMetadata getNullableResult(ResultSet resultSet, int columnIndex) throws SQLException {
        return parse(resultSet.getString(columnIndex));
    }

    @Override
    public AssetMetadata getNullableResult(CallableStatement statement, int columnIndex) throws SQLException {
        return parse(statement.getString(columnIndex));
    }

    private AssetMetadata parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, AssetMetadata.class);
        } catch (Exception e) {
            return null;
        }
    }
}
