package com.lumi.vault.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

public record PageResult<T>(List<T> records, long total, long page, long size, long pages) {

    public static <S, T> PageResult<T> of(IPage<S> source, Function<S, T> mapper) {
        return new PageResult<>(
                source.getRecords().stream().map(mapper).toList(),
                source.getTotal(),
                source.getCurrent(),
                source.getSize(),
                source.getPages());
    }
}
