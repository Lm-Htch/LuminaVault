package com.lumi.vault.business.websearch.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("asset_web_source")
public class AssetWebSource {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long assetId;

    private String provider;

    private String status;

    private Double similarity;

    private String sourceType;

    private String sourceUrl;

    private String sourceId;

    private String title;

    private String author;

    private String characters;

    private String raw;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
