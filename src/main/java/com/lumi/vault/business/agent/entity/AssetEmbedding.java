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

    /**
     * 动漫域：WD14（Danbooru 标签体系）适用，标签主源与相似建边都用 WD14 特征。
     */
    public static final String DOMAIN_ANIME = "anime";

    /**
     * 通用域：真人 / 风景 / 截图这类 WD14 不擅长的图。标签主源改用多模态模型，
     * 相似建边只走 CLIP 通道，避免动漫标签把噪声邻居混进来。
     */
    public static final String DOMAIN_GENERAL = "general";

    @TableId(type = IdType.INPUT)
    private Long assetId;

    private String domain;

    private String description;

    private String tags;

    private String characterTags;

    private String ocrText;

    private String perception;

    private String embedding;

    private String imageEmbedding;

    private String imageModel;

    private String clipEmbedding;

    private String model;

    private String status;

    private String error;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
