package com.lumi.vault.business.character.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumi.vault.business.character.entity.Character;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CharacterMapper extends BaseMapper<Character> {
}
