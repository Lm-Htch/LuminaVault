package com.lumi.vault.business.asset.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("app_setting")
public class AppSetting {

    @TableId(type = IdType.INPUT)
    private String settingKey;

    private String settingValue;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Instant updatedAt;
}
