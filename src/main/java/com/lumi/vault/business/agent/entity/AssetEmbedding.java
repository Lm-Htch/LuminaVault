package com.lumi.vault.business.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("asset_embedding")
public class AssetEmbedding {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_SKIPPED = "SKIPPED";

    @TableId(type = IdType.INPUT)
    private Long assetId;

    private String description;

    private String tags;

    private String characterTags;

    private String embedding;

    private String imageEmbedding;

    private String imageModel;

    private String model;

    private String status;

    private String error;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
