package com.lumi.vault.business.graph.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.Instant;
import java.util.List;

@Mapper
public interface AssetSimilarityEdgeMapper {

    record NeighborRow(long neighborId, double score) {
    }

    @Select("select count(*) from asset_similarity_edge")
    long count();

    @Select("""
            select case when e.a_id = #{assetId} then e.b_id else e.a_id end as neighbor_id, e.score
            from asset_similarity_edge e
            join asset a on a.id = case when e.a_id = #{assetId} then e.b_id else e.a_id end
            where (e.a_id = #{assetId} or e.b_id = #{assetId})
              and e.score >= #{minScore}
              and a.deleted = false
            order by e.score desc
            limit #{limit}
            """)
    List<NeighborRow> neighborsOf(@Param("assetId") long assetId,
                                  @Param("minScore") double minScore,
                                  @Param("limit") int limit);

    @Select("""
            <script>
            select a_id, b_id, score from asset_similarity_edge
            where score >= #{minScore}
              and a_id in <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
              and b_id in <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<PairRow> strongPairs(@Param("ids") List<Long> ids, @Param("minScore") double minScore);

    record PairRow(long aId, long bId, double score) {
    }

    @Delete("delete from asset_similarity_edge where a_id = #{assetId} or b_id = #{assetId}")
    int deleteForAsset(@Param("assetId") long assetId);

    @Insert("""
            insert into asset_similarity_edge (a_id, b_id, score, channels, updated_at)
            values (#{aId}, #{bId}, #{score}, #{channels}, #{updatedAt})
            """)
    int insert(@Param("aId") long aId,
               @Param("bId") long bId,
               @Param("score") double score,
               @Param("channels") String channels,
               @Param("updatedAt") Instant updatedAt);
}
