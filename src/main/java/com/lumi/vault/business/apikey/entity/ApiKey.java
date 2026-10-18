package com.lumi.vault.business.apikey.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

/**
 * 对外 API 密钥。明文只在创建时返回一次，库里只存 sha256 摘要。
 */
@Data
@TableName("api_key")
public class ApiKey {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String name;

    /** 明文前缀，用于在列表里辨认是哪把钥匙（如 lv_9f3aKd2m）。 */
    private String keyPrefix;

    /** 明文密钥的 sha256 十六进制摘要。 */
    private String keyHash;

    /** 逗号分隔的权限集合：read / write。 */
    private String scopes;

    private Instant expiresAt;

    private Instant lastUsedAt;

    private Instant revokedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
