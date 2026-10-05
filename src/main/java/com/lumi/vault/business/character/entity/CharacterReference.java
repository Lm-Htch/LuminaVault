package com.lumi.vault.business.character.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("character_reference")
public class CharacterReference {

    public static final String SOURCE_MANUAL = "MANUAL";
    public static final String SOURCE_FEEDBACK = "FEEDBACK";
    public static final String SOURCE_UPLOAD = "UPLOAD";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long characterId;

    private Long assetId;

    private String embedding;

    private String model;

    private String source;

    private Double weight;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
