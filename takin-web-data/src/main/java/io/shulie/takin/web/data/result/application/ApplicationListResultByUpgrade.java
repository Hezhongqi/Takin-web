package io.shulie.takin.web.data.result.application;

import java.util.Date;

import lombok.Data;

@Data
public class ApplicationListResultByUpgrade {

    /**
     * 应用id
     */
    private Long applicationId;

    private String applicationName;

    private String userName;

    private Date updateTime;

    private Integer nodeNum;

    private Long deptId;
}
