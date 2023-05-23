package io.shulie.takin.web.amdb.bean.result.application;

import java.io.Serializable;

import lombok.Data;

/**
 * @Author: 南风
 * @Date: 2021/9/16 10:02 上午
 */
@Data
public class ApplicationBizTableDTO implements Serializable {


    private Long id;

    private String appName;

    private String bizDatabase;

    private String tableName;

    private String tableUser;

    /**
     * 有写入操作
     */
    private Integer canWrite;

    /**
     * 有读取操作
     */
    private Integer canRead;

}
