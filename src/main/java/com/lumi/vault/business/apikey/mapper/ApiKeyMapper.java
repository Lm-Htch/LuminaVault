package com.lumi.vault.business.apikey.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumi.vault.business.apikey.entity.ApiKey;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ApiKeyMapper extends BaseMapper<ApiKey> {
}
