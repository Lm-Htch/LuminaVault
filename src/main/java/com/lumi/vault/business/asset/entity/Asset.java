package com.lumi.vault.business.asset.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName(value = "asset", autoResultMap = true)
public class Asset {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String storageKey;

    private String thumbnailKey;

    private String originalFilename;

    private String sortKey;

    private String author;

    private String contentType;

    private Long sizeBytes;

    private Integer width;

    private Integer height;

    private String sha256;

    private String description;

    private String colors;

    @TableField(typeHandler = AssetMetadataTypeHandler.class)
    private AssetMetadata metadata;

    private Long characterId;

    private Double characterConfidence;

    private Boolean deleted;

    private Instant deletedAt;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
