package com.lumi.vault.business.agent.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.Instant;

@Data
@TableName("search_favorite")
public class SearchFavorite {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    private String queryText;

    private String assetIds;

    @TableField(fill = FieldFill.INSERT)
    private Instant createdAt;
}
