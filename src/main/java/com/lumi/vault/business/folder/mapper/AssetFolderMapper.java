package com.lumi.vault.business.folder.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lumi.vault.business.folder.entity.AssetFolder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface AssetFolderMapper extends BaseMapper<AssetFolder> {

    record FolderCount(Long folderId, long total) {
    }

    record AssetFolderName(Long assetId, String name) {
    }

    @Select("""
            select af.folder_id as folderId, count(*) as total
            from asset_folder af
            join asset a on a.id = af.asset_id
            where a.deleted = false
            group by af.folder_id
            """)
    List<FolderCount> counts();

    @Select("""
            select af.asset_id as assetId, f.name as name
            from asset_folder af
            join folder f on f.id = af.folder_id
            """)
    List<AssetFolderName> names();

    @Select("select folder_id from asset_folder where asset_id = #{assetId}")
    List<Long> folderIdsOf(@Param("assetId") long assetId);
}
