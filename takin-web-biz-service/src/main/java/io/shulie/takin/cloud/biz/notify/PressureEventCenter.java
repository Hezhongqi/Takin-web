package io.shulie.takin.cloud.biz.notify;

import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import javax.annotation.Resource;

import com.alibaba.fastjson.JSONObject;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.shulie.takin.adapter.api.entrypoint.watchman.CloudWatchmanApi;
import io.shulie.takin.cloud.biz.cache.SceneTaskStatusCache;
import io.shulie.takin.cloud.biz.collector.collector.AbstractIndicators;
import io.shulie.takin.cloud.biz.service.async.CloudAsyncService;
import io.shulie.takin.cloud.biz.service.report.CloudReportService;
import io.shulie.takin.cloud.common.bean.scenemanage.UpdateStatusBean;
import io.shulie.takin.cloud.common.bean.task.TaskResult;
import io.shulie.takin.cloud.common.constants.ReportConstants;
import io.shulie.takin.cloud.common.constants.SceneTaskRedisConstants;
import io.shulie.takin.cloud.common.enums.PressureSceneEnum;
import io.shulie.takin.cloud.common.enums.PressureTaskStateEnum;
import io.shulie.takin.cloud.common.enums.scenemanage.SceneManageStatusEnum;
import io.shulie.takin.cloud.common.enums.scenemanage.SceneRunTaskStatusEnum;
import io.shulie.takin.cloud.common.utils.JsonUtil;
import io.shulie.takin.cloud.data.dao.report.ReportDao;
import io.shulie.takin.cloud.data.dao.scene.manage.SceneManageDAO;
import io.shulie.takin.cloud.data.dao.scene.task.PressureTaskDAO;
import io.shulie.takin.cloud.data.dao.scene.task.PressureTaskVarietyDAO;
import io.shulie.takin.cloud.data.model.mysql.PressureTaskEntity;
import io.shulie.takin.cloud.data.model.mysql.PressureTaskVarietyEntity;
import io.shulie.takin.cloud.data.model.mysql.SceneManageEntity;
import io.shulie.takin.cloud.data.param.report.ReportUpdateParam;
import io.shulie.takin.cloud.data.result.report.ReportResult;
import io.shulie.takin.cloud.data.util.PressureStartCache;
import io.shulie.takin.cloud.ext.api.AssetExtApi;
import io.shulie.takin.eventcenter.Event;
import io.shulie.takin.eventcenter.annotation.IntrestFor;
import io.shulie.takin.plugin.framework.core.PluginManager;
import io.shulie.takin.web.biz.checker.StartConditionChecker.CheckStatus;
import io.shulie.takin.web.biz.constant.WebRedisKeyConstant;
import io.shulie.takin.web.biz.service.scenemanage.SceneTaskService;
import io.shulie.takin.web.common.util.RedisClientUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 负责处理压测产生的事件
 */
@Slf4j
@Component
public class PressureEventCenter extends AbstractIndicators {

    @Resource
    private PressureTaskDAO pressureTaskDAO;
    @Resource
    private PressureTaskVarietyDAO pressureTaskVarietyDAO;
    @Resource
    private ReportDao reportDao;
    @Resource
    private SceneManageDAO sceneManageDAO;
    @Resource
    private SceneTaskStatusCache taskStatusCache;
    @Resource
    private CloudReportService cloudReportService;
    @Resource
    private RedisClientUtil redisClientUtil;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private PluginManager pluginManager;
    @Resource
    private SceneTaskService sceneTaskService;
    @Resource
    private CloudAsyncService cloudAsyncService;
    @Resource
    private CloudWatchmanApi cloudWatchmanApi;

    /**
     * 校验成功事件
     * 1、修改校验状态：校验成功
     * 2、修改任务状态：启动中
     *
     * @param event 事件源
     */
    @IntrestFor(event = PressureStartCache.CHECK_SUCCESS_EVENT, order = 0)
    public void callCheckSuccess(Event event) {
        ResourceContext ext = (ResourceContext) event.getExt();
        String resourceId = ext.getResourceId();
        String resourceKey = PressureStartCache.getResourceKey(resourceId);
        redisClientUtil.hmset(resourceKey, PressureStartCache.CHECK_STATUS, CheckStatus.SUCCESS.ordinal());
        pressureTaskDAO.updateStatus(ext.getTaskId(), PressureTaskStateEnum.STARTING, null);
        updateSceneMachineId(ext.getSceneId(), ext.getMachineId(), ext.getMachineType());
        cloudAsyncService.checkStartTimeout(resourceId);
    }

    /**
     * 检验失败事件
     * 1、删除指定缓存
     * 2、释放资源
     * 3、释放流量
     * 4、删除报告
     * 5、修改场景状态
     *
     * @param event 事件源
     */
    @IntrestFor(event = PressureStartCache.CHECK_FAIL_EVENT)
    public void callCheckFailed(Event event) {
        dealCheckFail((ResourceContext) event.getExt());
    }

    /**
     * 启动失败事件
     * 1、标记异常、标记停止中、设置启动失败
     * 2、释放流量
     * 3、释放资源/停止接口
     * 4、标识报告删除 [ 未初始化activity资源 ]
     * 5、标记报告完成 [ 已初始化activity资源 ]
     * 6、标记场景为 failed 状态
     * 7、删除所有缓存
     * 8、标记停止
     *
     * @param event 事件源
     */
    @IntrestFor(event = PressureStartCache.START_FAILED)
    public void callStartFail(Event event) {
        StartFailEventSource source = (StartFailEventSource) event.getExt();
        ResourceContext context = source.getContext();
        if (context != null) {
            dealStartFail(context, source.getMessage());
        }
    }

    /**
     * 运行失败事件
     * 1、调用任务停止接口 [ 成功往下走 ]
     * 2、标记异常、标记停止中、设置运行失败
     *
     * @param event 事件源
     */
    @IntrestFor(event = PressureStartCache.RUNNING_FAILED)
    public void callRunningFail(Event event) {
        StopEventSource source = (StopEventSource) event.getExt();
        ResourceContext context = source.getContext();
        String message = source.getMessage();
        Long taskId = context.getTaskId();
        String resourceId = context.getResourceId();
        boolean exception = false;
        try {
            stopJob(resourceId, context.getJobId());
        } catch (Throwable e) {
            pressureTaskDAO.updateStatus(taskId, PressureTaskStateEnum.UNUSUAL, message + " | " + e.getMessage());
            exception = true;
        }
        boolean noInterrupt = !source.isInterrupt();
        if (noInterrupt) {
            setTryRunTaskFailInfo(context.getSceneId(), context.getReportId(), context.getTenantId(), message);
        }
        if (!exception && redisClientUtil.lockStopFlagExpire(PressureStartCache.getStopFlag(resourceId), message)) {
            if (noInterrupt) {
                pressureTaskDAO.updateStatus(taskId, PressureTaskStateEnum.UNUSUAL, message);
            }
            pressureTaskDAO.updateStatus(taskId, PressureTaskStateEnum.STOPPING, null);
            endDefaultPressureIfNecessary(context);
        }
    }

    @IntrestFor(event = PressureStartCache.PRE_STOP_EVENT)
    public void callPreStop(Event event) {
        ResourceContext context = (ResourceContext) event.getExt();
        String resourceKey = PressureStartCache.getSceneResourceKey(context.getSceneId());
        if (!redisClientUtil.hExists(resourceKey, PressureStartCache.START_FLAG)) {
            dealCheckFail(context);
            return;
        }
        String resourceId = context.getResourceId();
        String message = context.getMessage();
        Object job = redisClientUtil.hmget(PressureStartCache.getResourceKey(resourceId), PressureStartCache.JOB_ID);
        if (redisClientUtil.hasKey(PressureStartCache.getJmeterStartFirstKey(resourceId))) {
            context.setJobId(Long.valueOf(String.valueOf(job)));
            Event failEvent = new Event();
            StopEventSource source = new StopEventSource();
            source.setContext(context);
            source.setMessage(message);
            failEvent.setExt(source);
            callRunningFail(failEvent);
            return;
        }
        dealStartFail(context, message);
    }

    @IntrestFor(event = PressureStartCache.PRESSURE_END)
    public void pressureEnd(Event event) {
        ResourceContext context = (ResourceContext) event.getExt();
        Long sceneId = context.getSceneId();
        Long reportId = context.getReportId();
        clearAllCache(sceneId, context.getResourceId());
        clearOthersPressureModelCache(sceneId);
        taskStatusCache.cacheStatus(sceneId, reportId, SceneRunTaskStatusEnum.ENDED);
        redisClientUtil.del(RedisClientUtil.getLockKey(PressureStartCache.getLockFlowKey(reportId)),
                RedisClientUtil.getLockKey(PressureStartCache.getReleaseFlowKey(reportId)));
        removeReportKey(reportId);
    }

    @IntrestFor(event = PressureStartCache.UNLOCK_FLOW)
    public void unLockFlow(Event event) {
        TaskResult ext = (TaskResult) event.getExt();
        unLockFlow(ext.getTaskId(), ext.getTenantId());
    }

    private void dealStartFail(ResourceContext context, String message) {
        String stopFlag = PressureStartCache.getStopFlag(context.getResourceId());
        if (redisClientUtil.lockStopFlagExpire(stopFlag, message)) {
            Long taskId = context.getTaskId();
            Long reportId = context.getReportId();
            setTryRunTaskFailInfo(context.getSceneId(), reportId, context.getTenantId(), message);
            pressureTaskDAO.updateStatus(taskId, PressureTaskStateEnum.UNUSUAL, message);
            pressureTaskDAO.updateStatus(taskId, PressureTaskStateEnum.STOPPING, null);
            unLockFlow(reportId, context.getTenantId());
            try {
                stopJob(context.getResourceId(), context.getJobId());
            } catch (Exception ignore) {
            }
            updateSceneFailed(context, SceneManageStatusEnum.STOP);
            if (!redisClientUtil.hasKey(PressureStartCache.getReportCachedKey(reportId))) {
                sceneTaskService.cacheReportKey(reportId, -1L);
            }
            notifyFinish(context);
            endDefaultPressureIfNecessary(context);
        }
    }

    private void dealCheckFail(ResourceContext context) {
        String resourceId = context.getResourceId();
        String message = context.getMessage();
        if (StringUtils.isBlank(resourceId) ||
                (redisClientUtil.lockStopFlagExpire(PressureStartCache.getStopFlag(context.getResourceId()), message)
                        && redisClientUtil.lockExpire(PressureStartCache.getResourceCheckFailKey(resourceId),
                        String.valueOf(System.currentTimeMillis()), 1, TimeUnit.MINUTES))) {
            Long reportId = context.getReportId();
            unLockFlow(reportId, context.getTenantId());
            releaseResource(resourceId);
            deleteReport(reportId, message);
            updateSceneFailed(context, SceneManageStatusEnum.FAILED);
            updateSceneMachineId(context.getSceneId(), context.getMachineId(), context.getMachineType());
            checkFailed(context, message);
            pressureTaskDAO.updateStatus(context.getTaskId(), PressureTaskStateEnum.INACTIVE, null);
        }
    }

    /**
     * 启动失败时更新报告信息
     *
     * @param reportId 报告Id
     * @param message  错误信息
     */
    private void deleteReport(Long reportId, String message) {
        ReportResult report = reportDao.selectById(reportId);
        if (Objects.nonNull(report)) {
            report.setIsDeleted(1);
            report.setGmtUpdate(new Date());
            fillFeatures(report, message);
            ReportUpdateParam param = BeanUtil.copyProperties(report, ReportUpdateParam.class);
            reportDao.updateReport(param);
        }
    }

    private void checkFailed(ResourceContext context, String message) {
        String resourceId = context.getResourceId();
        Long taskId = context.getTaskId();
        Long sceneId = context.getSceneId();
        if (StringUtils.isNotBlank(resourceId)) {
            redisClientUtil.del(PressureStartCache.getResourcePodSuccessKey(resourceId),
                    PressureStartCache.getPodStartFirstKey(resourceId), PressureStartCache.getPodHeartbeatKey(resourceId));
            String resourceKey = PressureStartCache.getResourceKey(resourceId);
            Map<String, Object> param = new HashMap<>(4);
            if (!context.isFileFailed()) {
                param.put(PressureStartCache.ERROR_MESSAGE, message);
            }
            param.put(PressureStartCache.CHECK_STATUS, CheckStatus.FAIL.ordinal());
            redisClientUtil.hmset(resourceKey, param);
            redisClientUtil.expire(resourceKey, 60);
        }
        Long reportId = context.getReportId();
        setTryRunTaskFailInfo(sceneId, reportId, context.getTenantId(), message);
        removeReportKey(reportId);
        PressureTaskEntity entity = new PressureTaskEntity();
        entity.setId(taskId);
        entity.setStatus(PressureTaskStateEnum.RESOURCE_LOCK_FAILED.ordinal());
        entity.setExceptionMsg(message);
        entity.setIsDeleted(1);
        entity.setGmtUpdate(new Date());
        pressureTaskDAO.updateById(entity);
        pressureTaskVarietyDAO.save(
                PressureTaskVarietyEntity.of(taskId, PressureTaskStateEnum.RESOURCE_LOCK_FAILED, message));
        if (redisClientUtil.unlock(PressureStartCache.getSceneResourceLockingKey(sceneId), context.getUniqueKey())) {
            redisClientUtil.delete(PressureStartCache.getSceneResourceKey(sceneId));
        }
    }

    /**
     * 删除所有缓存
     *
     * @param sceneId    场景Id
     * @param resourceId 资源Id
     */
    private void clearAllCache(Long sceneId, String resourceId) {
        redisClientUtil.del(PressureStartCache.clearCacheKey(resourceId, sceneId).toArray(new String[0]));
    }

    /**
     * 删除调试等类型压测的缓存
     *
     * @param sceneId 场景Id
     */
    private void clearOthersPressureModelCache(Long sceneId) {
        String tryRunKey = PressureStartCache.getTryRunKey(sceneId);
        if (redisClientUtil.hasKey(tryRunKey)) {
            redisClientUtil.del(tryRunKey);
        }
        String flowDebugKey = PressureStartCache.getFlowDebugKey(sceneId);
        if (redisClientUtil.hasKey(flowDebugKey)) {
            redisClientUtil.del(flowDebugKey);
        }
        String inspectKey = PressureStartCache.getInspectKey(sceneId);
        if (redisClientUtil.hasKey(inspectKey)) {
            redisClientUtil.del(inspectKey);
        }
    }

    /**
     * 设置任务状态运行状态为失败
     *
     * @param sceneId  场景Id
     * @param reportId 报告Id
     * @param tenantId 租户Id
     * @param errorMsg 错误信息
     */
    protected void setTryRunTaskFailInfo(Long sceneId, Long reportId, Long tenantId, String errorMsg) {
        log.info("压测启动失败--sceneId:【{}】,reportId:【{}】,tenantId:【{}】,errorMsg:【{}】",
                sceneId, reportId, tenantId, errorMsg);
        String tryRunTaskKey = String.format(SceneTaskRedisConstants.SCENE_TASK_RUN_KEY + "%s_%s", sceneId, reportId);
        stringRedisTemplate.opsForHash().put(tryRunTaskKey,
                SceneTaskRedisConstants.SCENE_RUN_TASK_STATUS_KEY, SceneRunTaskStatusEnum.FAILED.getText());
        if (StringUtils.isNotBlank(errorMsg)) {
            stringRedisTemplate.opsForHash().put(tryRunTaskKey, SceneTaskRedisConstants.SCENE_RUN_TASK_ERROR, errorMsg);
        }
    }

    /**
     * 释放流量
     *
     * @param reportId 报告Id
     * @param tenantId 租户Id
     */
    public void unLockFlow(Long reportId, Long tenantId) {
        if (Objects.isNull(reportId) || !shouldUnlockFlow(reportId)) {
            return;
        }
        ReportResult report = reportDao.selectById(reportId);
        if (report != null) {
            String amountLockId;
            JSONObject json = JsonUtil.parse(report.getFeatures());
            if (null == json) {
                json = new JSONObject();
            }
            amountLockId = json.getString("lockId");
            //释放流量
            AssetExtApi assetExtApi = pluginManager.getExtension(AssetExtApi.class);
            if (assetExtApi != null) {
                boolean unLock;
                try {
                    if (StringUtils.isNotBlank(amountLockId)) {
                        unLock = assetExtApi.unlock(tenantId, amountLockId);
                    } else {
                        unLock = assetExtApi.unlock(report.getTenantId(), String.valueOf(reportId));
                    }
                    if (!unLock) {
                        log.error("释放流量失败！");
                    }
                } catch (Exception e) {
                    log.error("释放流量失败", e);
                }
            }
        }
    }

    /**
     * 判断是否需要释放流量
     *
     * @param reportId 报告Id
     * @return true-释放
     */
    private boolean shouldUnlockFlow(Long reportId) {
        String lockFlowKey = RedisClientUtil.getLockKey(PressureStartCache.getLockFlowKey(reportId));
        boolean locked = redisClientUtil.hasKey(lockFlowKey);
        if (locked && redisClientUtil.lockExpire(PressureStartCache.getReleaseFlowKey(reportId),
                String.valueOf(System.currentTimeMillis()), 5, TimeUnit.MINUTES)) {
            redisClientUtil.del(lockFlowKey);
            return true;
        }
        return false;
    }

    /**
     * 标记场景为失败状态
     *
     * @param context 资源
     */
    private void updateSceneFailed(ResourceContext context, SceneManageStatusEnum statusEnum) {
        String resourceId = context.getResourceId();
        // 没有资源Id时，触发的失败是并发启动的问题或者前置校验问题
        if (StringUtils.isBlank(resourceId)) {
            Long reportId = context.getReportId();
            String sceneResourceKey = PressureStartCache.getSceneResourceKey(context.getSceneId());
            Object sceneReport = redisClientUtil.hmget(sceneResourceKey, PressureStartCache.REPORT_ID);
            if (Objects.nonNull(sceneReport) && !Objects.equals(reportId, sceneReport)) {
                return;
            }
        }
        sceneManageDAO.getBaseMapper().update(null,
                Wrappers.lambdaUpdate(SceneManageEntity.class)
                        .set(SceneManageEntity::getStatus, statusEnum.getValue())
                        .eq(SceneManageEntity::getId, context.getSceneId()));
    }

    private void fillFeatures(ReportResult report, String message) {
        JSONObject json = JsonUtil.parse(report.getFeatures());
        if (null == json) {
            json = new JSONObject();
        }
        json.put(ReportConstants.FEATURES_ERROR_MSG, message);
        report.setFeatures(json.toJSONString());
    }

    public static class AmdbCalibrationException extends RuntimeException {

        public AmdbCalibrationException(String message) {
            super(message);
        }
    }

    public static class CloudCalibrationException extends RuntimeException {
        public CloudCalibrationException(String message) {
            super(message);
        }
    }

    private void endDefaultPressureIfNecessary(ResourceContext context) {
        // 巡检压测，停止场景
        if (Objects.equals(context.getPressureType(), PressureSceneEnum.INSPECTION_MODE.getCode())) {
            Long reportId = context.getReportId();
            Long sceneId = context.getSceneId();
            Long tenantId = context.getTenantId();
            cloudReportService.updateReportFeatures(reportId, ReportConstants.FINISH_STATUS, null, null);
            cloudSceneManageService.updateSceneLifeCycle(UpdateStatusBean.build(sceneId, reportId, tenantId)
                    .checkEnum(SceneManageStatusEnum.getAll()).updateEnum(SceneManageStatusEnum.WAIT).build());
            pressureTaskDAO.updateStatus(context.getTaskId(), PressureTaskStateEnum.INACTIVE, null);
            Event event = new Event();
            event.setExt(context);
            pressureEnd(event);
        }
    }

    private void removeReportKey(Long reportId) {
        final String reportKey = WebRedisKeyConstant.getReportKey(reportId);
        redisTemplate.opsForList().remove(WebRedisKeyConstant.getTaskList(), 0, reportKey);
        redisTemplate.opsForValue().getOperations().delete(reportKey);
    }

    // 资源锁定成功
    @IntrestFor(event = PressureStartCache.RESOURCE_LOCK_SUCCESS_EVENT)
    public void resourceLockSuccess(Event event) {
        ResourceContext ext = (ResourceContext) event.getExt();
        String resourceId = ext.getResourceId();
        String resourceKey = PressureStartCache.getResourceKey(resourceId);
        redisClientUtil.hmset(resourceKey, PressureStartCache.RESOURCE_LOCK_STATUS, CheckStatus.SUCCESS.ordinal());
        // 增加成功事件数
        String attach = (String)redisClientUtil.hmget(resourceKey, PressureStartCache.FILE_ATTACH_ID);
        incrementSuccessEvent(attach, "resource-lock");
    }

    // 文件下载成功
    @IntrestFor(event = PressureStartCache.FILE_DOWNLOAD_SUCCESS_EVENT)
    public void fileDownloadSuccess(Event event) {
        String attach = (String) event.getExt();
        incrementSuccessEvent(attach, "file-download");
    }

    // 文件校验成功
    @IntrestFor(event = PressureStartCache.FILE_VERIFY_SUCCESS_EVENT)
    public void fileVerifySuccess(Event event) {
        String attach = (String) event.getExt();
        incrementSuccessEvent(attach, "file-verify");
    }

    // 计数并触发校验成功事件
    private void incrementSuccessEvent(String fileAttach, String type) {
        String conditionKey = PressureStartCache.getAllConditionKey(fileAttach);
        Long successEventCount = redisClientUtil.addSetValueAndReturnCount(conditionKey, type);
        if (successEventCount == 3 && redisClientUtil.lockExpire(conditionKey, String.valueOf(System.currentTimeMillis()), 1, TimeUnit.DAYS)) {
            String pressureFileKey = PressureStartCache.getPressureFileKey(fileAttach);
            String resourceId = (String)redisClientUtil.hmget(pressureFileKey, PressureStartCache.RESOURCE_ID);
            Event event = new Event();
            event.setEventName(PressureStartCache.CHECK_SUCCESS_EVENT);
            event.setExt(getResourceContext(resourceId));
            eventCenterTemplate.doEvents(event);
            redisClientUtil.del(conditionKey, pressureFileKey);
        }
    }
}
