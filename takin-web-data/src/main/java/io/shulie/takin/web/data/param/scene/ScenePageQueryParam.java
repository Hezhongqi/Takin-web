package io.shulie.takin.web.data.param.scene;

import java.util.Date;

import io.shulie.takin.web.ext.entity.AuthQueryParamCommonExt;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * @author zhaoyong
 */
@Data
public class ScenePageQueryParam extends AuthQueryParamCommonExt {

    private String sceneName;

    private Integer ignoreType;

    private Long deptId;

    @ApiModelProperty("过滤时间范围")
    private Date queryGmtModified;
}
