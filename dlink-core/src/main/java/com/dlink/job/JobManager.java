package com.dlink.job;

import com.dlink.api.FlinkAPI;
import com.dlink.assertion.Asserts;
import com.dlink.config.Dialect;
import com.dlink.constant.FlinkSQLConstant;
import com.dlink.executor.EnvironmentSetting;
import com.dlink.executor.Executor;
import com.dlink.executor.ExecutorSetting;
import com.dlink.explainer.Explainer;
import com.dlink.gateway.Gateway;
import com.dlink.gateway.GatewayType;
import com.dlink.gateway.config.ActionType;
import com.dlink.gateway.config.FlinkConfig;
import com.dlink.gateway.config.GatewayConfig;
import com.dlink.gateway.result.GatewayResult;
import com.dlink.gateway.result.SavePointResult;
import com.dlink.gateway.result.TestResult;
import com.dlink.interceptor.FlinkInterceptor;
import com.dlink.model.SystemConfiguration;
import com.dlink.parser.SqlType;
import com.dlink.result.*;
import com.dlink.session.ExecutorEntity;
import com.dlink.session.SessionConfig;
import com.dlink.session.SessionInfo;
import com.dlink.session.SessionPool;
import com.dlink.trans.Operations;
import com.dlink.utils.SqlUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.flink.configuration.CoreOptions;
import org.apache.flink.configuration.DeploymentOptions;
import org.apache.flink.configuration.PipelineOptions;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.SavepointConfigOptions;
import org.apache.flink.streaming.api.environment.ExecutionCheckpointingOptions;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.yarn.configuration.YarnConfigOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JobManager
 *
 * @author wenmo
 * @since 2021/5/25 15:27
 **/
public class JobManager {

    private static final Logger logger = LoggerFactory.getLogger(JobManager.class);

    private JobHandler handler;
    private Integer maxRowNum = 100;
    private EnvironmentSetting environmentSetting;
    private ExecutorSetting executorSetting;
    private JobConfig config;
    private Executor executor;
    private boolean useGateway = false;
    private boolean isPlanMode = false;
    private boolean useStatementSet = false;
    private boolean useRestAPI = false;
    private String sqlSeparator = FlinkSQLConstant.SEPARATOR;
    private GatewayType runMode = GatewayType.LOCAL;

    public JobManager() {
    }

    public void setUseGateway(boolean useGateway) {
        this.useGateway = useGateway;
    }

    public boolean isUseGateway() {
        return useGateway;
    }

    public void setPlanMode(boolean planMode) {
        isPlanMode = planMode;
    }

    public boolean isPlanMode() {
        return isPlanMode;
    }

    public boolean isUseStatementSet() {
        return useStatementSet;
    }

    public void setUseStatementSet(boolean useStatementSet) {
        this.useStatementSet = useStatementSet;
    }

    public boolean isUseRestAPI() {
        return useRestAPI;
    }

    public void setUseRestAPI(boolean useRestAPI) {
        this.useRestAPI = useRestAPI;
    }

    public String getSqlSeparator() {
        return sqlSeparator;
    }

    public void setSqlSeparator(String sqlSeparator) {
        this.sqlSeparator = sqlSeparator;
    }

    public JobManager(JobConfig config) {
        this.config = config;
    }

    public static JobManager build() {
        JobManager manager = new JobManager();
        manager.init();
        return manager;
    }

    public static JobManager build(JobConfig config) {
        initGatewayConfig(config);
        JobManager manager = new JobManager(config);
        manager.init();
        return manager;
    }

    public static JobManager buildPlanMode(JobConfig config) {
        JobManager manager = new JobManager(config);
        manager.setPlanMode(true);
        manager.init();
        return manager;
    }

    private static void initGatewayConfig(JobConfig config) {
        if (useGateway(config.getType())) {
            Asserts.checkNull(config.getGatewayConfig(), "GatewayConfig 不能为空");
            config.getGatewayConfig().setType(GatewayType.get(config.getType()));
            config.getGatewayConfig().setTaskId(config.getTaskId());
            config.getGatewayConfig().getFlinkConfig().setJobName(config.getJobName());
            config.getGatewayConfig().getFlinkConfig().setSavePoint(config.getSavePointPath());
            config.setUseRemote(false);
        }
    }

    public static boolean useGateway(String type) {
        return (GatewayType.YARN_PER_JOB.equalsValue(type) || GatewayType.YARN_APPLICATION.equalsValue(type)
                || GatewayType.KUBERNETES_APPLICATION.equalsValue(type));
    }

    private Executor createExecutor() {
        initEnvironmentSetting();
        if (!runMode.equals(GatewayType.LOCAL)&& !useGateway && config.isUseRemote()) {
            executor = Executor.buildRemoteExecutor(environmentSetting, config.getExecutorSetting());
            return executor;
        } else {
            executor = Executor.buildLocalExecutor(config.getExecutorSetting());
            return executor;
        }
    }

    private Executor createExecutorWithSession() {
        if (config.isUseSession()) {
            ExecutorEntity executorEntity = SessionPool.get(config.getSession());
            if (Asserts.isNotNull(executorEntity)) {
                executor = executorEntity.getExecutor();
                config.setSessionConfig(executorEntity.getSessionConfig());
                initEnvironmentSetting();
                executor.update(executorSetting);
            } else {
                createExecutor();
                SessionPool.push(new ExecutorEntity(config.getSession(), executor));
            }
        } else {
            createExecutor();
        }
        return executor;
    }

    private void initEnvironmentSetting() {
        if (Asserts.isNotNullString(config.getAddress())) {
            environmentSetting = EnvironmentSetting.build(config.getAddress());
        }
    }

    private void initExecutorSetting() {
        executorSetting = config.getExecutorSetting();
    }

    public boolean init() {
        if(!isPlanMode) {
            runMode = GatewayType.get(config.getType());
            useGateway = useGateway(config.getType());
            handler = JobHandler.build();
        }
        useStatementSet = config.isUseStatementSet();
        useRestAPI = SystemConfiguration.getInstances().isUseRestAPI();
        sqlSeparator = SystemConfiguration.getInstances().getSqlSeparator();
        initExecutorSetting();
        createExecutorWithSession();
        return false;
    }

    private boolean ready() {
        return handler.init();
    }

    private boolean success() {
        return handler.success();
    }

    private boolean failed() {
        return handler.failed();
    }

    public boolean close() {
        JobContextHolder.clear();
        return false;
    }

    public JobResult executeSql(String statement) {
        Job job = Job.init(runMode, config, executorSetting, executor, statement, useGateway);
        if (!useGateway) {
            job.setJobManagerAddress(environmentSetting.getAddress());
        }
        JobContextHolder.setJob(job);
        ready();
        String currentSql = "";
//        JobParam jobParam = pretreatStatements(SqlUtil.getStatements(statement));
        JobParam jobParam = Explainer.build(executor,useStatementSet,sqlSeparator).pretreatStatements(SqlUtil.getStatements(statement,sqlSeparator));
        try {
            for (StatementParam item : jobParam.getDdl()) {
                currentSql = item.getValue();
                executor.executeSql(item.getValue());
            }
            if (jobParam.getTrans().size() > 0) {
                if (useStatementSet && useGateway) {
                    List<String> inserts = new ArrayList<>();
                    for (StatementParam item : jobParam.getTrans()) {
                        inserts.add(item.getValue());
                    }
                    currentSql = String.join(sqlSeparator, inserts);
                    JobGraph jobGraph = executor.getJobGraphFromInserts(inserts);
                    GatewayResult gatewayResult = null;
                    if (GatewayType.YARN_APPLICATION.equals(runMode)||GatewayType.KUBERNETES_APPLICATION.equals(runMode)) {
                        config.addGatewayConfig(executor.getSetConfig());
                        gatewayResult = Gateway.build(config.getGatewayConfig()).submitJar();
                    } else {
                        config.addGatewayConfig(executor.getSetConfig());
                        gatewayResult = Gateway.build(config.getGatewayConfig()).submitJobGraph(jobGraph);
                    }
                    job.setResult(InsertResult.success(gatewayResult.getAppId()));
                    job.setJobId(gatewayResult.getAppId());
                    job.setJobManagerAddress(formatAddress(gatewayResult.getWebURL()));
                } else if (useStatementSet && !useGateway) {
                    List<String> inserts = new ArrayList<>();
                    for (StatementParam item : jobParam.getTrans()) {
                        if (item.getType().equals(SqlType.INSERT)) {
                            inserts.add(item.getValue());
                        }
                    }
                    if (inserts.size() > 0) {
                        currentSql = String.join(sqlSeparator, inserts);
                        TableResult tableResult = executor.executeStatementSet(inserts);
                        if (tableResult.getJobClient().isPresent()) {
                            job.setJobId(tableResult.getJobClient().get().getJobID().toHexString());
                        }
                        if (config.isUseResult()) {
                            IResult result = ResultBuilder.build(SqlType.INSERT, maxRowNum, "", true).getResult(tableResult);
                            job.setResult(result);
                        }
                    }
                } else if (!useStatementSet && useGateway) {
                    List<String> inserts = new ArrayList<>();
                    for (StatementParam item : jobParam.getTrans()) {
                        inserts.add(item.getValue());
                        break;
                    }
                    currentSql = String.join(sqlSeparator, inserts);
                    JobGraph jobGraph = executor.getJobGraphFromInserts(inserts);
                    GatewayResult gatewayResult = null;
                    if (GatewayType.YARN_APPLICATION.equals(runMode)||GatewayType.KUBERNETES_APPLICATION.equals(runMode)) {
                        config.addGatewayConfig(executor.getSetConfig());
                        gatewayResult = Gateway.build(config.getGatewayConfig()).submitJar();
                    } else {
                        config.addGatewayConfig(executor.getSetConfig());
                        gatewayResult = Gateway.build(config.getGatewayConfig()).submitJobGraph(jobGraph);
                    }
                    job.setResult(InsertResult.success(gatewayResult.getAppId()));
                    job.setJobId(gatewayResult.getAppId());
                    job.setJobManagerAddress(formatAddress(gatewayResult.getWebURL()));
                } else {
                    for (StatementParam item : jobParam.getTrans()) {
                        currentSql = item.getValue();
                        if (!FlinkInterceptor.build(executor, item.getValue())) {
                            TableResult tableResult = executor.executeSql(item.getValue());
                            if (tableResult.getJobClient().isPresent()) {
                                job.setJobId(tableResult.getJobClient().get().getJobID().toHexString());
                            }
                            if (config.isUseResult()) {
                                IResult result = ResultBuilder.build(item.getType(), maxRowNum, "", true).getResult(tableResult);
                                job.setResult(result);
                            }
                        }
                    }
                }
            }
            job.setEndTime(LocalDateTime.now());
            job.setStatus(Job.JobStatus.SUCCESS);
            success();
        } catch (Exception e) {
            e.printStackTrace();
            StackTraceElement[] trace = e.getStackTrace();
            StringBuffer resMsg = new StringBuffer("");
            for (StackTraceElement s : trace) {
                resMsg.append(" \n " + s + "  ");
            }
            LocalDateTime now = LocalDateTime.now();
            job.setEndTime(now);
            job.setStatus(Job.JobStatus.FAILED);
            String error = now.toString() + ":" + "Exception in executing FlinkSQL:\n" + currentSql + " \nError message: " + e.getMessage() + " \n >>> PrintStackTrace <<<" + resMsg.toString();
            job.setError(error);
            failed();
            close();
        }
        close();
        return job.getJobResult();
    }

    private String formatAddress(String webURL) {
        if (Asserts.isNotNullString(webURL)) {
            return webURL.replaceAll("http://", "");
        } else {
            return "";
        }
    }

    public IResult executeDDL(String statement) {
        String[] statements = SqlUtil.getStatements(statement,sqlSeparator);
        try {
            for (String item : statements) {
                String newStatement = executor.pretreatStatement(item);
                if (newStatement.trim().isEmpty()) {
                    continue;
                }
                SqlType operationType = Operations.getOperationType(newStatement);
                if (SqlType.INSERT == operationType || SqlType.SELECT == operationType) {
                    continue;
                }
                LocalDateTime startTime = LocalDateTime.now();
                TableResult tableResult = executor.executeSql(newStatement);
                IResult result = ResultBuilder.build(operationType, maxRowNum, "", false).getResult(tableResult);
                result.setStartTime(startTime);
                return result;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new ErrorResult();
    }

    public static SelectResult getJobData(String jobId) {
        return ResultPool.get(jobId);
    }

    public static SessionInfo createSession(String session, SessionConfig sessionConfig, String createUser) {
        if (SessionPool.exist(session)) {
            return SessionPool.getInfo(session);
        }
        Executor sessionExecutor = null;
        if (sessionConfig.isUseRemote()) {
            sessionExecutor = Executor.buildRemoteExecutor(EnvironmentSetting.build(sessionConfig.getAddress()), ExecutorSetting.DEFAULT);
        } else {
            sessionExecutor = Executor.buildLocalExecutor(sessionConfig.getExecutorSetting());
        }
        ExecutorEntity executorEntity = new ExecutorEntity(session, sessionConfig, createUser, LocalDateTime.now(), sessionExecutor);
        SessionPool.push(executorEntity);
        return SessionInfo.build(executorEntity);
    }

    public static List<SessionInfo> listSession(String createUser) {
        return SessionPool.filter(createUser);
    }

    public ExplainResult explainSql(String statement) {
        return Explainer.build(executor,useStatementSet,sqlSeparator).explainSql(statement);
    }

    public ObjectNode getStreamGraph(String statement) {
        return Explainer.build(executor,useStatementSet,sqlSeparator).getStreamGraph(statement);
    }

    public String getJobPlanJson(String statement) {
        return Explainer.build(executor,useStatementSet,sqlSeparator).getJobPlanInfo(statement).getJsonPlan();
    }

    public boolean cancel(String jobId) {
        if (useGateway && !useRestAPI) {
            config.getGatewayConfig().setFlinkConfig(FlinkConfig.build(jobId, ActionType.CANCEL.getValue(),
                    null, null));
            Gateway.build(config.getGatewayConfig()).savepointJob();
            return true;
        } else {
            try{
                return FlinkAPI.build(config.getAddress()).stop(jobId);
            }catch (Exception e){
                logger.info("停止作业时集群不存在");
            }
            return false;
        }
    }

    public SavePointResult savepoint(String jobId,String savePointType,String savePoint) {
        if (useGateway && !useRestAPI) {
            config.getGatewayConfig().setFlinkConfig(FlinkConfig.build(jobId, ActionType.SAVEPOINT.getValue(),
                    savePointType, null));
            return Gateway.build(config.getGatewayConfig()).savepointJob(savePoint);
        } else {
            return FlinkAPI.build(config.getAddress()).savepoints(jobId,savePointType);
        }
    }

    public JobResult executeJar() {
        Job job = Job.init(runMode, config, executorSetting, executor, null, useGateway);
        JobContextHolder.setJob(job);
        ready();
        try {
            GatewayResult gatewayResult = Gateway.build(config.getGatewayConfig()).submitJar();
            job.setResult(InsertResult.success(gatewayResult.getAppId()));
            job.setJobId(gatewayResult.getAppId());
            job.setJobManagerAddress(formatAddress(gatewayResult.getWebURL()));
            job.setEndTime(LocalDateTime.now());
            job.setStatus(Job.JobStatus.SUCCESS);
            success();
        } catch (Exception e) {
            e.printStackTrace();
            StackTraceElement[] trace = e.getStackTrace();
            StringBuffer resMsg = new StringBuffer("");
            for (StackTraceElement s : trace) {
                resMsg.append(" \n " + s + "  ");
            }
            LocalDateTime now = LocalDateTime.now();
            job.setEndTime(now);
            job.setStatus(Job.JobStatus.FAILED);
            String error = now.toString() + ":" + "Exception in executing Jar：\n" + config.getGatewayConfig().getAppConfig().getUserJarPath() + " \nError message: " + e.getMessage() + " \n >>> PrintStackTrace <<<" + resMsg.toString();
            job.setError(error);
            failed();
            close();
        }
        close();
        return job.getJobResult();
    }

    public static TestResult testGateway(GatewayConfig gatewayConfig){
        return Gateway.build(gatewayConfig).test();
    }

    public String exportSql(String sql){
        String statement = executor.pretreatStatement(sql);
        StringBuilder sb = new StringBuilder();
        if(Asserts.isNotNullString(config.getJobName())){
            sb.append("set " + PipelineOptions.NAME.key() + " = " + config.getJobName() + ";\r\n");
        }
        if(Asserts.isNotNull(config.getParallelism())){
            sb.append("set " + CoreOptions.DEFAULT_PARALLELISM.key() + " = " + config.getParallelism() + ";\r\n");
        }
        if(Asserts.isNotNull(config.getCheckpoint())){
            sb.append("set " + ExecutionCheckpointingOptions.CHECKPOINTING_INTERVAL.key() + " = " + config.getCheckpoint() + ";\r\n");
        }
        if(Asserts.isNotNullString(config.getSavePointPath())){
            sb.append("set " + SavepointConfigOptions.SAVEPOINT_PATH + " = " + config.getSavePointPath() + ";\r\n");
        }
        if(Asserts.isNotNull(config.getGatewayConfig())&&Asserts.isNotNull(config.getGatewayConfig().getFlinkConfig().getConfiguration())) {
            for (Map.Entry<String, String> entry : config.getGatewayConfig().getFlinkConfig().getConfiguration().entrySet()) {
                sb.append("set " + entry.getKey() + " = " + entry.getValue() + ";\r\n");
            }
        }

        switch (GatewayType.get(config.getType())){
            case YARN_PER_JOB:
            case YARN_APPLICATION:
                sb.append("set " + DeploymentOptions.TARGET.key() + " = " + GatewayType.get(config.getType()).getLongValue() + ";\r\n");
                if(Asserts.isNotNull(config.getGatewayConfig())) {
                    sb.append("set " + YarnConfigOptions.PROVIDED_LIB_DIRS.key() + " = " + Collections.singletonList(config.getGatewayConfig().getClusterConfig().getFlinkLibPath()) + ";\r\n");
                }
                if(Asserts.isNotNull(config.getGatewayConfig())&&Asserts.isNotNullString(config.getGatewayConfig().getFlinkConfig().getJobName())) {
                    sb.append("set " + YarnConfigOptions.APPLICATION_NAME.key() + " = " + config.getGatewayConfig().getFlinkConfig().getJobName() + ";\r\n");
                }
        }
        sb.append(statement);
        return sb.toString();
    }
}
