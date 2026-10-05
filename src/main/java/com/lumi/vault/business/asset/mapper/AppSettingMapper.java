package com.lumi.vault.business.asset.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumi.vault.business.asset.entity.AppSetting;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppSettingMapper extends BaseMapper<AppSetting> {
}
