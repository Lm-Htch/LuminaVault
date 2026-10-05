package com.lumi.vault.business.vector.mapper;

import com.lumi.vault.business.vector.dto.CharacterVectorMatch;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface VectorSearchMapper {

    @Select("""
            select character_id, max(1 - (embedding_vec <=> #{vector}::vector)) as similarity
            from character_reference
            where embedding_vec is not null
            group by character_id
            order by similarity desc
            limit #{limit}
            """)
    List<CharacterVectorMatch> topCharacters(@Param("vector") String vector, @Param("limit") int limit);

    @Update("update asset_embedding set image_embedding_vec = #{vector}::vector where asset_id = #{assetId}")
    int updateImageVector(@Param("assetId") Long assetId, @Param("vector") String vector);

    @Update("update character_reference set embedding_vec = #{vector}::vector where id = #{id}")
    int updateReferenceVector(@Param("id") Long id, @Param("vector") String vector);
}
