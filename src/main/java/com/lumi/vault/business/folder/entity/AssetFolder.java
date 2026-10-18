package com.lumi.vault.business.folder.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("asset_folder")
public class AssetFolder {

    @TableId(value = "asset_id", type = IdType.INPUT)
    private Long assetId;

    private Long folderId;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
