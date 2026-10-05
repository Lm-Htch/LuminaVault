package com.lumi.vault.business.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumi.vault.business.auth.entity.User;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User> {
}
