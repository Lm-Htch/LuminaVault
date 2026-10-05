package com.lumi.vault.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class MybatisPlusMetaObjectHandler implements MetaObjectHandler {

    private static final String CREATED_AT = "createdAt";
    private static final String UPDATED_AT = "updatedAt";

    @Override
    public void insertFill(MetaObject metaObject) {
        Instant now = now();
        strictInsertFill(metaObject, CREATED_AT, Instant.class, now);
        strictInsertFill(metaObject, UPDATED_AT, Instant.class, now);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        setFieldValByName(UPDATED_AT, now(), metaObject);
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
