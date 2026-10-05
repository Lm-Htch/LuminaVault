package com.lumi.vault.business.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("asset_feedback")
public class AssetFeedback {

    public static final String ACTION_SELECT = "SELECT";
    public static final String ACTION_REJECT = "REJECT";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private Long assetId;

    private String queryText;

    private String action;

    private Double weight;

    private String embedding;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
