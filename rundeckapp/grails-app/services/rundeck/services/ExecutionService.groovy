package rundeck.services

import com.dtolabs.rundeck.core.common.INodeEntry
import com.dtolabs.rundeck.core.execution.service.NodeExecutorResultImpl
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepException
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepExecutionItem
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepExecutionService
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepExecutor
import com.dtolabs.rundeck.core.execution.workflow.steps.node.NodeStepResult
import com.dtolabs.rundeck.core.utils.OptsUtil
import com.dtolabs.rundeck.app.support.BaseNodeFilters
import com.dtolabs.rundeck.app.support.QueueQuery
import com.dtolabs.rundeck.core.Constants
import com.dtolabs.rundeck.core.logging.ContextLogWriter
import com.dtolabs.rundeck.core.logging.LogLevel
import com.dtolabs.rundeck.core.common.Framework
import com.dtolabs.rundeck.core.common.INodeSet
import com.dtolabs.rundeck.core.common.NodesSelector
import com.dtolabs.rundeck.core.common.SelectorUtils
import com.dtolabs.rundeck.core.dispatcher.DataContextUtils
import com.dtolabs.rundeck.core.execution.ExecutionListener
import com.dtolabs.rundeck.core.execution.StepExecutionItem
import com.dtolabs.rundeck.core.execution.WorkflowExecutionServiceThread
import com.dtolabs.rundeck.core.execution.workflow.*
import com.dtolabs.rundeck.core.execution.workflow.steps.*
import com.dtolabs.rundeck.core.utils.NodeSet
import com.dtolabs.rundeck.core.utils.ThreadBoundOutputStream
import com.dtolabs.rundeck.execution.ExecutionItemFactory
import com.dtolabs.rundeck.execution.JobExecutionItem
import com.dtolabs.rundeck.execution.JobReferenceFailureReason
import com.dtolabs.rundeck.server.authorization.AuthConstants
import grails.util.GrailsWebUtil
import org.codehaus.groovy.grails.web.context.ServletContextHolder
import org.hibernate.StaleObjectStateException
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.MessageSource
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.support.WebApplicationContextUtils
import org.springframework.web.servlet.support.RequestContextUtils as RCU
import rundeck.*
import rundeck.controllers.ExecutionController
import rundeck.services.logging.ExecutionLogWriter

import javax.security.auth.Subject
import javax.servlet.http.HttpSession
import java.text.MessageFormat
import java.text.SimpleDateFormat
import java.util.regex.Pattern

import static org.apache.tools.ant.util.StringUtils.*

/**
 * Coordinates Command executions via Ant Project objects
 */
class ExecutionService implements ApplicationContextAware, StepExecutor, NodeStepExecutor{

    static transactional = true
    def FrameworkService frameworkService
    def notificationService
    def ScheduledExecutionService scheduledExecutionService
    def ReportService reportService
    def LoggingService loggingService

    def ThreadBoundOutputStream sysThreadBoundOut
    def ThreadBoundOutputStream sysThreadBoundErr
    def String defaultLogLevel

    def ApplicationContext applicationContext

    // implement ApplicationContextAware interface

    def listLastExecutionsPerProject(Framework framework, int max=5){
        def projects = frameworkService.projects(framework).collect{ it.name }

        def c = Execution.createCriteria()
        def lastexecs=[:]
        projects.each { proj ->
            def results = c.list {
                eq("project",proj)
                maxResults(max)
                order("dateCompleted","desc")
            }
            lastexecs[proj]=results
        }
        return lastexecs
    }
    /**
     * Return the last execution time for any execution in the query's project
     * @param query
     * @return
     */
    def Execution lastExecution(String project){
        def execs = Execution.findAllByProjectAndDateCompletedIsNotNull(project,[max:1,sort:'dateCompleted',order:'desc'])
        return execs?execs[0]:null
    }

    /**
     * query queue, returns map [:]:
     *
     * query: query object
     * _filters: map of used filter names-&gt; properties
     * jobs: map of ID to ScheduledExecution for matching jobs
     * nowrunning: list of Executions
     * total: total executions
     */
    def queryQueue(QueueQuery query){
        def eqfilters = [
                proj: 'project',
        ]
        def txtfilters = [
            obj:'name',
            type:'type',
            cmd:'command',
            user:'user',
        ]
        def schedTxtFilters= [
            job:'jobName',
        ]
        def schedPathFilters=[
            groupPath: 'groupPath'
        ]
        def schedExactFilters= [
            groupPathExact:'groupPath',
            jobId:'uuid'
        ]
        def schedFilterKeys= (schedExactFilters.keySet() + schedTxtFilters.keySet() + schedPathFilters.keySet())

        def filters = [ :]
        filters.putAll(txtfilters)
        filters.putAll(eqfilters)

        def Date endAfterDate = new Date(System.currentTimeMillis()-1000*60*60)
        if(query && query.doendafterFilter){
            endAfterDate = query.endafterFilter
        }
        def Date nowDate = new Date()

        def allProjectsQuery=query.projFilter=='*';

        def crit = Execution.createCriteria()
        def runlist = crit.list{
            if(query?.max){
                maxResults(query?.max.toInteger())
            }else{
//                maxResults(grailsApplication.config.reportservice.pagination.default?grailsApplication.config.reportservice.pagination.default.toInteger():20)
                maxResults(20)
            }
            if(query?.offset){
                firstResult(query.offset.toInteger())
            }

             if(query ){
                txtfilters.each{ key,val ->
                    if(query["${key}Filter"]){
                        ilike(val,'%'+query["${key}Filter"]+'%')
                    }
                }
                if(!allProjectsQuery){
                    eqfilters.each{ key,val ->
                        if(query["${key}Filter"]){
                            eq(val,query["${key}Filter"])
                        }
                    }
                }

                 //running status filter.
                 if(query.runningFilter){
                     if('running'==query.runningFilter){
                        isNull("dateCompleted")
                     }else {
                         and{
                            eq('status',"completed"==query.runningFilter?'true':'false')
                            eq('cancelled',"killed"==query.runningFilter?'true':'false')
                            isNotNull('dateCompleted')
                         }
                     }
                 }
                 def schedfilts=[:]
                 schedFilterKeys.each {
                     if( query["${it}Filter"]){
                         schedfilts[it]= query["${it}Filter"]
                     }
                 }
                 if(schedfilts.groupPath=='*'){
                     schedfilts.remove('groupPath')
                 }
                 if(schedfilts){
                     scheduledExecution {

                         schedExactFilters.each{key,v->
                             if (query["${key}Filter"]) {
                                 eq(v, query["${key}Filter"] )
                             }
                         }
                         schedTxtFilters.each{key,v->
                             if (query["${key}Filter"]) {
                                 ilike(v, '%'+query["${key}Filter"] + '%')
                             }
                         }
                         schedPathFilters.each{key,v->
                             if (query["${key}Filter"]) {
                                 ilike(v, query["${key}Filter"] + '%')
                             }
                         }
                     }
                 }

//                if(query.dostartafterFilter && query.dostartbeforeFilter && query.startbeforeFilter && query.startafterFilter){
//                    between('dateStarted',query.startafterFilter,query.startbeforeFilter)
//                }
//                else if(query.dostartbeforeFilter && query.startbeforeFilter ){
//                    le('dateStarted',query.startbeforeFilter)
//                }else if (query.dostartafterFilter && query.startafterFilter ){
//                    ge('dateStarted',query.startafterFilter)
//                }
                
//                if(query.doendafterFilter && query.doendbeforeFilter && query.endafterFilter && query.endbeforeFilter){
//                    between('dateCompleted',query.endafterFilter,query.endbeforeFilter)
//                }
//                else if(query.doendbeforeFilter && query.endbeforeFilter ){
//                    le('dateCompleted',query.endbeforeFilter)
//                }
//                if(query.doendafterFilter && query.endafterFilter ){

//                or{
//                    between("dateCompleted", endAfterDate,nowDate)
                    isNull("dateCompleted")
//                }
//                }
            }else{
//                and {
//                    or{
//                        between("dateCompleted", endAfterDate,nowDate)
                        isNull("dateCompleted")
//                    }
//                }
            }

            if(query && query.sortBy && filters[query.sortBy]){
                order(filters[query.sortBy],query.sortOrder=='ascending'?'asc':'desc')
            }else{
                order("dateStarted","desc")
            }

        };
        def currunning=[]
        runlist.each{
            currunning<<it
        }

        def jobs =[:]
        currunning.each{
            if(it.scheduledExecution && !jobs[it.scheduledExecution.id.toString()]){
                jobs[it.scheduledExecution.id.toString()] = ScheduledExecution.get(it.scheduledExecution.id)
            }
        }


        def total = Execution.createCriteria().count{

             if(query ){
                txtfilters.each{ key,val ->
                    if(query["${key}Filter"]){
                        ilike(val,'%'+query["${key}Filter"]+'%')
                    }
                }

                eqfilters.each{ key,val ->
                    if(query["${key}Filter"]){
                        eq(val,query["${key}Filter"])
                    }
                }

                 //running status filter.
                 if(query.runningFilter){
                     if('running'==query.runningFilter){
                        isNull("dateCompleted")
                     }else {
                         and{
                            eq('status',"completed"==query.runningFilter?'true':'false')
                            eq('cancelled',"killed"==query.runningFilter?'true':'false')
                            isNotNull('dateCompleted')
                         }
                     }
                 }

                 //original Job name filter
                 if(query.jobFilter){
                    scheduledExecution{
                        ilike('jobName','%'+query.jobFilter+'%')
                    }
                 }

                isNull("dateCompleted")
            }else{
                isNull("dateCompleted")
            }
        };

        return [query:query, _filters:filters,
            jobs: jobs, nowrunning:currunning,
            total: total]
    }

    def public  finishQueueQuery = { query,params,model->

       if(!params.max){
           params.max=20
       }
       if(!params.offset){
           params.offset=0
       }

       def paginateParams=[:]
       if(query){
           model._filters.each{ key,val ->
               if(params["${key}Filter"]){
                   paginateParams["${key}Filter"]=params["${key}Filter"]
               }
           }
       }
       def displayParams = [:]
       displayParams.putAll(paginateParams)

       params.each{key,val ->
           if(key ==~ /^(start|end)(do|set|before|after)Filter.*/){
               paginateParams[key]=val
           }
       }
       if(query){
           if(query.recentFilter && query.recentFilter!='-'){
               displayParams.put("recentFilter",query.recentFilter)
               paginateParams.put("recentFilter",query.recentFilter)
               query.dostartafterFilter=false
               query.dostartbeforeFilter=false
               query.doendafterFilter=false
               query.doendbeforeFilter=false
           }else{
               if(query.dostartafterFilter && query.startafterFilter){
                   displayParams.put("startafterFilter",query.startafterFilter)
                   paginateParams.put("dostartafterFilter","true")
               }
               if(query.dostartbeforeFilter && query.startbeforeFilter){
                   displayParams.put("startbeforeFilter",query.startbeforeFilter)
                   paginateParams.put("dostartbeforeFilter","true")
               }
               if(query.doendafterFilter && query.endafterFilter){
                   displayParams.put("endafterFilter",query.endafterFilter)
                   paginateParams.put("doendafterFilter","true")
               }
               if(query.doendbeforeFilter && query.endbeforeFilter){
                   displayParams.put("endbeforeFilter",query.endbeforeFilter)
                   paginateParams.put("doendbeforeFilter","true")
               }
           }
       }
       def remkeys=[]
       if(!query || !query.dostartafterFilter){
           paginateParams.each{key,val ->
               if(key ==~ /^startafterFilter.*/){
                   remkeys.add(key)
               }
           }
       }
       if(!query || !query.dostartbeforeFilter){
           paginateParams.each{key,val ->
               if(key ==~ /^startbeforeFilter.*/){
                   remkeys.add(key)
               }
           }
       }
       if(!query || !query.doendafterFilter){
           paginateParams.each{key,val ->
               if(key ==~ /^endafterFilter.*/){
                   remkeys.add(key)
               }
           }
       }
       if(!query || !query.doendbeforeFilter){
           paginateParams.each{key,val ->
               if(key ==~ /^endbeforeFilter.*/){
                   remkeys.add(key)
               }
           }
       }
       remkeys.each{
           paginateParams.remove(it)
       }

       def tmod=[max: query?.max?query.max:20,
           offset:query?.offset?query.offset:0,
           paginateParams:paginateParams,
           displayParams:displayParams]
       model.putAll(tmod)
       return model
   }



    /**
    * Return a dataset: [nowrunning: (list of Execution), jobs: [map of id->ScheduledExecution for scheduled jobs],
     *  total: total number of running executions, max: input max] 
     */
    def listNowRunning(Framework framework, int max=10){
        //find currently running executions

        def Date lastHour = new Date(System.currentTimeMillis()-1000*60*60)
        def Date nowDate = new Date()

        def crit = Execution.createCriteria()
        def runlist = crit.list{
            maxResults(max)
            isNull("dateCompleted")
            order("dateStarted","desc")
        };
        def currunning=[]
        runlist.each{
            currunning<<it
        }

        def jobs =[:]
        currunning.each{
            if(it.scheduledExecution && !jobs[it.scheduledExecution.id.toString()]){
                jobs[it.scheduledExecution.id.toString()] = ScheduledExecution.get(it.scheduledExecution.id)
            }
        }


        def total = Execution.createCriteria().count{
            isNull("dateCompleted")
        };

        return [jobs: jobs, nowrunning:currunning, total: total, max: max]
    }

    /**
     * Set the result status to FAIL for any Executions that are not complete
     * @param serverUUID if not null, only match executions assigned to the given serverUUID
     */
    def cleanupRunningJobs(String serverUUID=null) {
        def found = Execution.findAllByDateCompletedAndServerNodeUUID(null, serverUUID)
        found.each { Execution e ->
            saveExecutionState(e.scheduledExecution?.id, e.id, [status: String.valueOf(false), dateCompleted: new Date(), cancelled: true], null)
            log.error("Stale Execution cleaned up: [${e.id}]")
        }
    }


    public logExecution(uri,project,user,issuccess,execId,Date startDate=null, jobExecId=null, jobName=null, jobSummary=null,iscancelled=false, nodesummary=null, abortedby=null){

        def reportMap=[:]
        def internalLog = org.apache.log4j.Logger.getLogger("ExecutionService")
        if(null==project || null==user  ){
            //invalid
            internalLog.error("could not send execution report: some required values were null: (project:${project},user:${user})")
            return
        }

        if(execId){
            reportMap.jcExecId=execId
        }
        if(startDate){
            reportMap.dateStarted=startDate
        }
        if(jobExecId){
            reportMap.jcJobId=jobExecId
        }
        if(jobName){
            reportMap.reportId=jobName
        }else{
            reportMap.reportId='adhoc'
        }
        reportMap.ctxProject=project

        if(iscancelled && abortedby){
            reportMap.abortedByUser=abortedby
        }else if(iscancelled){
            reportMap.abortedByUser=user
        }
        reportMap.author=user
        reportMap.title= jobSummary?jobSummary:"RunDeck Job Execution"
        reportMap.status= issuccess ? "succeed":iscancelled?"cancel":"fail"
        reportMap.node= null!=nodesummary?nodesummary: frameworkService.getFrameworkNodeName()

        reportMap.message=(issuccess?'Job completed successfully':iscancelled?('Job killed by: '+(abortedby?:user)):'Job failed')
        reportMap.dateCompleted=new Date()
        def result=reportService.reportExecutionResult(reportMap)
        if(result.error){
            log.error("Failed to create report: "+result.report.errors.allErrors.collect{it.toString()}).join("; ")
        }
    }

    def static HashMap<String, String> exportContextForExecution(Execution execution) {
        def jobcontext = new HashMap<String, String>()
        if (execution.scheduledExecution) {
            jobcontext.name = execution.scheduledExecution.jobName
            jobcontext.group = execution.scheduledExecution.groupPath
            jobcontext.id = execution.scheduledExecution.extid
        }
        jobcontext.execid = execution.id.toString()
        jobcontext.serverUrl = generateServerURL()
        jobcontext.url = generateExecutionURL(execution)
        jobcontext.serverUUID = execution.serverNodeUUID
        jobcontext.username = execution.user
        jobcontext['user.name'] = execution.user
        jobcontext.project = execution.project
        jobcontext.loglevel = ExecutionService.textLogLevels[execution.loglevel] ?: execution.loglevel
        jobcontext
    }

    static String generateExecutionURL(Execution execution) {
        RequestHelper.doWithMockRequest {
            new ExecutionController().createExecutionUrl(execution.id)
        }
    }
    static String generateServerURL() {
        RequestHelper.doWithMockRequest {
            new ExecutionController().createServerUrl()
        }
    }

    /**
     * starts an execution in a separate thread, returning a map of [thread:Thread, loghandler:LogHandler]
     */
    def Map executeAsyncBegin(Framework framework, Execution execution, ScheduledExecution scheduledExecution=null, Map extraParams = null, Map extraParamsExposed = null){
        //TODO: method can be transactional readonly
        execution.refresh()
        def ExecutionLogWriter loghandler= loggingService.openLogWriter(execution,
                                                                          logLevelForString(execution.loglevel),
                                                                          [user:execution.user,
                                                                                  node: framework.getFrameworkNodeName()])
        execution.outputfilepath= loghandler.filepath?.getAbsolutePath()
        execution.save(flush:true)

        try{
            def jobcontext=exportContextForExecution(execution)
            loghandler.openStream()

            WorkflowExecutionItem item = createExecutionItemForExecutionContext(execution, framework, execution.user)

            NodeRecorder recorder = new NodeRecorder();//TODO: use workflow-aware listener for nodes

            //create listener to handle log messages and Ant build events
            WorkflowExecutionListenerImpl executionListener = new WorkflowExecutionListenerImpl(recorder, new ContextLogWriter(loghandler),false,null);
            StepExecutionContext executioncontext = createContext(execution, null,framework, execution.user, jobcontext, executionListener, null,extraParams, extraParamsExposed)

            //ExecutionService handles Job reference steps
            final cis = StepExecutionService.getInstanceForFramework(framework);
            cis.registerInstance(JobExecutionItem.STEP_EXECUTION_TYPE, this)
            //ExecutionService handles Job reference node steps
            final nis = NodeStepExecutionService.getInstanceForFramework(framework);
            nis.registerInstance(JobExecutionItem.STEP_EXECUTION_TYPE, this)

            if (scheduledExecution) {
                //send onstart notification
                def result = notificationService.triggerJobNotification('start', scheduledExecution.id,
                        [execution: execution, context:executioncontext])
            }
            //install custom outputstreams for System.out and System.err for this thread and any child threads
            //output will be sent to loghandler instead.
            sysThreadBoundOut.installThreadStream(loggingService.createLogOutputStream(loghandler, LogLevel.NORMAL, executionListener));
            sysThreadBoundErr.installThreadStream(loggingService.createLogOutputStream(loghandler, LogLevel.ERROR, executionListener));
            //create service object for the framework and listener
            Thread thread = new WorkflowExecutionServiceThread(framework.getWorkflowExecutionService(),item, executioncontext)
            thread.start()
            return [thread:thread, loghandler:loghandler, noderecorder:recorder, execution: execution, scheduledExecution:scheduledExecution]
        }catch(Exception e) {
            log.error('Failed to start execution', e)
            loghandler.logError('Failed to start execution: ' + e.getClass().getName() + ": " + e.message)
            sysThreadBoundOut.removeThreadStream()
            sysThreadBoundErr.removeThreadStream()
            loghandler.close()
            return null
        }
    }
    private LogLevel logLevelForString(String level){
        def deflevel = applicationContext?.getServletContext()?.getAttribute("LOGLEVEL_DEFAULT")
        return LogLevel.looseValueOf(level?:deflevel,LogLevel.NORMAL)
    }

    def static textLogLevels = ['ERR': 'ERROR']
    def static mappedLogLevels = ['ERROR', 'WARN', 'INFO', 'VERBOSE', 'DEBUG']
    /**
     * Map the log level to an integer used internally
     * @param level
     * @return
     */
    private int logLevelIntValue(String level){
        LogLevel loglevel = logLevelForString(level)
        List levels= LogLevel.values() as List
        return levels.indexOf(loglevel)
    }
    /**
     * create the path to the execution output file based on the Execution object.
     *
     * Uses the execution ID if present as the filename, otherwise generates a unique
     * filename based on the type of execution.
     *
     * Sets the directory based on associated ScheduledExecution,
     * and if that does not exist then based on execution type and context.
     */
    def String createOutputFilepathForExecution(Execution execution, Framework framework){
        String name=execution.id.toString()+".txt"
        if(execution.scheduledExecution){
            return new File(maybeCreateJobLogDir(execution.scheduledExecution,framework),name).getAbsolutePath()
        }else{
            return new File(maybeCreateAdhocLogDir(execution,framework),name).getAbsolutePath()
        }
    }

    /**
     * Return an appropriate StepExecutionItem object for the stored Execution
     */
    public WorkflowExecutionItem createExecutionItemForExecutionContext(ExecutionContext execution, Framework framework, String user=null) {
        WorkflowExecutionItem item
        if (execution.workflow) {
            item = createExecutionItemForWorkflowContext(execution, framework,user)
        } else {
            throw new RuntimeException("unsupported job type")
        }
        return item
    }


    /**
     * Return an StepExecutionItem instance for the given workflow Execution, suitable for the ExecutionService layer
     */
    public WorkflowExecutionItem createExecutionItemForWorkflowContext(ExecutionContext execMap, Framework framework, String userName=null) {
        if (!execMap.workflow.commands || execMap.workflow.commands.size() < 1) {
            throw new Exception("Workflow is empty")
        }
        if (!userName) {
            userName = execMap.user
        }

        //create thread object with an execution item, and start it
        final WorkflowExecutionItemImpl item = new WorkflowExecutionItemImpl(
            new WorkflowImpl(execMap.workflow.commands.collect {itemForWFCmdItem(it,it.errorHandler?itemForWFCmdItem(it.errorHandler):null)}, execMap.workflow.threadcount, execMap.workflow.keepgoing,execMap.workflow.strategy?execMap.workflow.strategy: "node-first"))
        return item
    }

    public StepExecutionItem itemForWFCmdItem(final WorkflowStep step,final StepExecutionItem handler=null) throws FileNotFoundException {
        if(step instanceof CommandExec || step.instanceOf(CommandExec)){
            CommandExec cmd=step.asType(CommandExec)
            if (null != cmd.getAdhocRemoteString()) {

                final List<String> strings = OptsUtil.burst(cmd.getAdhocRemoteString());
                final String[] args = strings.toArray(new String[strings.size()]);

                return ExecutionItemFactory.createExecCommand(args, handler, !!cmd.keepgoingOnSuccess);

            } else if (null != cmd.getAdhocLocalString()) {
                final String script = cmd.getAdhocLocalString();
                final String[] args;
                if (null != cmd.getArgString()) {
                    final List<String> strings = OptsUtil.burst(cmd.getArgString());
                    args = strings.toArray(new String[strings.size()]);
                } else {
                    args = new String[0];
                }
                return ExecutionItemFactory.createScriptFileItem(cmd.getScriptInterpreter(),
                        !!cmd.interpreterArgsQuoted,
                        script, args, handler, !!cmd.keepgoingOnSuccess);

            } else if (null != cmd.getAdhocFilepath()) {
                final String filepath = cmd.getAdhocFilepath();
                final String[] args;
                if (null != cmd.getArgString()) {
                    final List<String> strings = OptsUtil.burst(cmd.getArgString());
                    args = strings.toArray(new String[strings.size()]);
                } else {
                    args = new String[0];
                }
                if(filepath ==~ /^(?i:https?|file):.*$/) {
                    return ExecutionItemFactory.createScriptURLItem(cmd.getScriptInterpreter(),
                            !!cmd.interpreterArgsQuoted, filepath, args, handler, !!cmd.keepgoingOnSuccess)
                }else {
                    return ExecutionItemFactory.createScriptFileItem(cmd.getScriptInterpreter(),
                            !!cmd.interpreterArgsQuoted, new File(filepath), args, handler, !!cmd.keepgoingOnSuccess);

                }
            }
        }else if (step instanceof JobExec || step.instanceOf(JobExec)) {
            final JobExec jobcmditem = step as JobExec;

            final String[] args;
            if (null != jobcmditem.getArgString()) {
                final List<String> strings = OptsUtil.burst(jobcmditem.getArgString());
                args = strings.toArray(new String[strings.size()]);
            } else {
                args = new String[0];
            }

            return ExecutionItemFactory.createJobRef(jobcmditem.getJobIdentifier(), args, !!jobcmditem.nodeStep, handler, !!jobcmditem.keepgoingOnSuccess)
        }else if(step instanceof PluginStep || step.instanceOf(PluginStep)){
            final PluginStep stepitem = step as PluginStep
            if(stepitem.nodeStep){
                return ExecutionItemFactory.createPluginNodeStepItem(stepitem.type, stepitem.configuration, !!stepitem.keepgoingOnSuccess, handler)
            }else{
                return ExecutionItemFactory.createPluginStepItem(stepitem.type,stepitem.configuration, !!stepitem.keepgoingOnSuccess,handler)
            }
        } else {
            throw new IllegalArgumentException("Workflow step type was not expected: "+step);
        }
    }

    /**
     * Return an StepExecutionItem instance for the given workflow Execution, suitable for the ExecutionService layer
     */
    public StepExecutionContext createContext(ExecutionContext execMap, StepExecutionContext origContext, Map<String, String> jobcontext, String[] inputargs = null, Map extraParams = null, Map extraParamsExposed = null) {
        createContext(execMap,origContext,origContext.framework,origContext.user,jobcontext,origContext.executionListener,inputargs,extraParams,extraParamsExposed)
    }
    /**
     * Return an StepExecutionItem instance for the given workflow Execution, suitable for the ExecutionService layer
     */
    public StepExecutionContext createContext(ExecutionContext execMap, StepExecutionContext origContext, Framework framework, String userName = null, Map<String, String> jobcontext, ExecutionListener listener, String[] inputargs=null, Map extraParams=null, Map extraParamsExposed=null) {
        if (!userName) {
            userName=execMap.user
        }
        //convert argString into Map<String,String>
        def String[] args = execMap.argString? OptsUtil.burst(execMap.argString):inputargs
        def Map<String, String> optsmap = execMap.argString ? frameworkService.parseOptsFromString(execMap.argString) : null!=args? frameworkService.parseOptsFromArray(args):[:]
        if(extraParamsExposed){
            optsmap.putAll(extraParamsExposed)
        }

        def Map<String,Map<String,String>> datacontext = new HashMap<String,Map<String,String>>()
        datacontext.put("option",optsmap)
        if(extraParamsExposed){
            datacontext.put("secureOption",extraParamsExposed.clone())
        }
        datacontext.put("job",jobcontext?jobcontext:new HashMap<String,String>())

        NodesSelector nodeselector
        int threadCount=1
        boolean keepgoing=false

        if (execMap.doNodedispatch) {
            //set nodeset for the context if doNodedispatch parameter is true
            NodeSet nodeset = filtersAsNodeSet(execMap)
            nodeselector=nodeset
            // enhnacement to allow ${option.xyz} in tags and names
            if (nodeset != null) {
                final nodesetProperties = ['name', 'tags', 'hostname', 'osfamily', 'osname', 'osversion', 'osarch']
                nodesetProperties.each {key ->
                    if (nodeset.include && nodeset.include[key]) {
                        nodeset.include[key] = DataContextUtils.replaceDataReferences(nodeset.include[key], datacontext)
                    }
                    if (nodeset.exclude && nodeset.exclude[key]) {
                        nodeset.exclude[key] = DataContextUtils.replaceDataReferences(nodeset.exclude[key], datacontext)
                    }
                }
                threadCount=nodeset.threadCount
                keepgoing=nodeset.keepgoing
                //TODO: apply to attributes once nodefilters support attributes
            }
        } else if(framework){
            //blank?
            nodeselector = SelectorUtils.singleNode(framework.frameworkNodeName)
        }else{
            nodeselector = null
        }

        def INodeSet nodeSet=frameworkService.filterNodeSet(framework,nodeselector,execMap.project)

        def Map<String, Map<String, String>> privatecontext = new HashMap<String, Map<String, String>>()
        if (null != extraParams) {
            privatecontext.put("option", extraParams)
        }

        //create execution context
        def builder = com.dtolabs.rundeck.core.execution.ExecutionContextImpl.builder(origContext)
            .frameworkProject(execMap.project)
            .user(userName)
            .nodeSelector(nodeselector)
            .nodes(nodeSet)
            .loglevel(logLevelIntValue(execMap.loglevel))
            .dataContext(datacontext)
            .privateDataContext(privatecontext)
            .executionListener(listener)
            .framework(framework)
            .threadCount(threadCount)
            .keepgoing(keepgoing)
            .nodeRankAttribute(execMap.nodeRankAttribute)
            .nodeRankOrderAscending(null == execMap.nodeRankOrderAscending || execMap.nodeRankOrderAscending)
        if(origContext){
            //start a sub context
            builder.pushContextStep(1)
        }
        return builder.build()
    }

    def abortExecution(ScheduledExecution se, Execution e, String user, final def framework, String killAsUser=null
    ){
        def eid=e.id
        def dateCompleted = e.dateCompleted
        e.discard()
        def ident = scheduledExecutionService.getJobIdent(se,e)
        def statusStr
        def abortstate
        def jobstate
        def failedreason
        def userIdent=killAsUser?:user
        if (!frameworkService.authorizeProjectExecutionAll(framework,e,[AuthConstants.ACTION_KILL])){
            jobstate = ExecutionController.getExecutionState(e)
            abortstate= ExecutionController.ABORT_FAILED
            failedreason="unauthorized"
            statusStr= jobstate
        }else if(killAsUser && !frameworkService.authorizeProjectExecutionAll(framework, e, [AuthConstants.ACTION_KILLAS])) {
            jobstate = ExecutionController.getExecutionState(e)
            abortstate = ExecutionController.ABORT_FAILED
            failedreason = "unauthorized"
            statusStr = jobstate
        }else if (scheduledExecutionService.existsJob(ident.jobname, ident.groupname)){
            boolean success=false
            int repeat=3;
            while(!success && repeat>0){
                try{
                    Execution.withNewSession {
                        Execution e2 = Execution.get(eid)
                        if (!e2.abortedby) {
                            e2.abortedby = userIdent
                            e2.save(flush: true)
                            success=true
                        }
                    }
                } catch (org.springframework.dao.OptimisticLockingFailureException ex) {
                    log.error("Could not abort ${eid}, the execution was modified")
                } catch (StaleObjectStateException ex) {
                    log.error("Could not abort ${eid}, the execution was modified")
                }
                if(!success){
                    Thread.sleep(200)
                    repeat--
                }
            }

            def didcancel=false
            if(success){
                didcancel=scheduledExecutionService.interruptJob(ident.jobname, ident.groupname)
            }
            abortstate=didcancel?ExecutionController.ABORT_PENDING:ExecutionController.ABORT_FAILED
            failedreason=didcancel?'':'Unable to interrupt the running job'
            jobstate=ExecutionController.EXECUTION_RUNNING
        }else if(null==dateCompleted){
            saveExecutionState(
                se?se.id:null,
                eid,
                    [
                    status:String.valueOf(false),
                    dateCompleted:new Date(),
                    cancelled:true,
                    abortedby: userIdent
                    ]
                )
            abortstate=ExecutionController.ABORT_ABORTED
            jobstate=ExecutionController.EXECUTION_ABORTED
        }else{
            jobstate=ExecutionController.getExecutionState(e)
            statusStr='previously '+jobstate
            abortstate=ExecutionController.ABORT_FAILED
            failedreason =  'Job is not running'
        }
        return [abortstate:abortstate,jobstate:jobstate,statusStr:statusStr, failedreason: failedreason]
    }

    /**
     * Return a map of include filters as used by the NodeSet type
     */
    public static Map includeFiltersAsNodeSetMap( econtext) {
        def nodeIncludeMap = [:]
        if (econtext.nodeInclude || econtext.nodeExclude
                               || econtext.nodeIncludeName || econtext.nodeExcludeName
                               || econtext.nodeIncludeTags || econtext.nodeExcludeTags
                               || econtext.nodeIncludeOsName || econtext.nodeExcludeOsName
                               || econtext.nodeIncludeOsFamily || econtext.nodeExcludeOsFamily
                               || econtext.nodeIncludeOsArch || econtext.nodeExcludeOsArch
                               || econtext.nodeIncludeOsVersion || econtext.nodeExcludeOsVersion
            ) {

            nodeIncludeMap[NodeSet.HOSTNAME] = econtext.nodeInclude
            nodeIncludeMap[NodeSet.NAME] = econtext.nodeIncludeName
            nodeIncludeMap[NodeSet.TAGS] = econtext.nodeIncludeTags
            nodeIncludeMap[NodeSet.OS_NAME] = econtext.nodeIncludeOsName
            nodeIncludeMap[NodeSet.OS_FAMILY] = econtext.nodeIncludeOsFamily
            nodeIncludeMap[NodeSet.OS_ARCH] = econtext.nodeIncludeOsArch
            nodeIncludeMap[NodeSet.OS_VERSION] = econtext.nodeIncludeOsVersion
        }
        return nodeIncludeMap
    }

    /**
     * Return a map of exclude filters as used by the NodeSet type
     */
    public static Map excludeFiltersAsNodeSetMap( econtext) {

        def nodeExcludeMap = [:]
        if (econtext.nodeInclude || econtext.nodeExclude
                               || econtext.nodeIncludeName || econtext.nodeExcludeName
                               || econtext.nodeIncludeTags || econtext.nodeExcludeTags
                               || econtext.nodeIncludeOsName || econtext.nodeExcludeOsName
                               || econtext.nodeIncludeOsFamily || econtext.nodeExcludeOsFamily
                               || econtext.nodeIncludeOsArch || econtext.nodeExcludeOsArch
                               || econtext.nodeIncludeOsVersion || econtext.nodeExcludeOsVersion
            ) {
            nodeExcludeMap[NodeSet.HOSTNAME] = econtext.nodeExclude
            nodeExcludeMap[NodeSet.NAME] = econtext.nodeExcludeName
            nodeExcludeMap[NodeSet.TAGS] = econtext.nodeExcludeTags
            nodeExcludeMap[NodeSet.OS_NAME] = econtext.nodeExcludeOsName
            nodeExcludeMap[NodeSet.OS_FAMILY] = econtext.nodeExcludeOsFamily
            nodeExcludeMap[NodeSet.OS_ARCH] = econtext.nodeExcludeOsArch
            nodeExcludeMap[NodeSet.OS_VERSION] = econtext.nodeExcludeOsVersion

        }
        return nodeExcludeMap
    }

    /**
     * Return a NodeSet using the filters in the execution context
     */
    public static NodeSet filtersAsNodeSet(BaseNodeFilters econtext) {
        final NodeSet nodeset = new NodeSet();
        nodeset.createExclude(excludeFiltersAsNodeSetMap(econtext)).setDominant(econtext.nodeExcludePrecedence ? true : false);
        nodeset.createInclude(includeFiltersAsNodeSetMap(econtext)).setDominant(!econtext.nodeExcludePrecedence ? true : false);
        return nodeset
    }
    /**
     * Return a NodeSet using the filters in the execution context
     */
    public static NodeSet filtersAsNodeSet(ExecutionContext econtext) {
        final NodeSet nodeset = new NodeSet();
        nodeset.createExclude(excludeFiltersAsNodeSetMap(econtext)).setDominant(econtext.nodeExcludePrecedence ? true : false);
        nodeset.createInclude(includeFiltersAsNodeSetMap(econtext)).setDominant(!econtext.nodeExcludePrecedence ? true : false);
        nodeset.setKeepgoing(econtext.nodeKeepgoing?true:false)
        nodeset.setThreadCount(econtext.nodeThreadcount?econtext.nodeThreadcount:1)
        return nodeset
    }

    /**
     * Return a NodeSet using the filters in the execution context
     */
    public static NodeSet filtersAsNodeSet(Map econtext) {
        final NodeSet nodeset = new NodeSet();
        nodeset.createExclude(excludeFiltersAsNodeSetMap(econtext)).setDominant(econtext.nodeExcludePrecedence?true:false);
        nodeset.createInclude(includeFiltersAsNodeSetMap(econtext)).setDominant(!econtext.nodeExcludePrecedence?true:false);
        nodeset.setKeepgoing(econtext.nodeKeepgoing?true:false)
        nodeset.setThreadCount(econtext.nodeThreadcount?econtext.nodeThreadcount:1)
        return nodeset
    }


   def Execution createExecution(Map params, Framework framework) {
        def Execution execution
        if (params.project && params.workflow) {
            execution = new Execution(project:params.project,
                                      user:params.user,loglevel:params.loglevel,
                                    doNodedispatch:params.doNodedispatch?"true" == params.doNodedispatch.toString():false,
                                    nodeInclude:params.nodeInclude,
                                    nodeExclude:params.nodeExclude,
                                    nodeIncludeName:params.nodeIncludeName,
                                    nodeExcludeName:params.nodeExcludeName,
                                    nodeIncludeTags:params.nodeIncludeTags,
                                    nodeExcludeTags:params.nodeExcludeTags,
                                    nodeIncludeOsName:params.nodeIncludeOsName,
                                    nodeExcludeOsName:params.nodeExcludeOsName,
                                    nodeIncludeOsFamily:params.nodeIncludeOsFamily,
                                    nodeExcludeOsFamily:params.nodeExcludeOsFamily,
                                    nodeIncludeOsArch:params.nodeIncludeOsArch,
                                    nodeExcludeOsArch:params.nodeExcludeOsArch,
                                    nodeIncludeOsVersion:params.nodeIncludeOsVersion,
                                    nodeExcludeOsVersion:params.nodeExcludeOsVersion,
                                    nodeExcludePrecedence:params.nodeExcludePrecedence,
                                    nodeThreadcount:params.nodeThreadcount,
                                    nodeKeepgoing:params.nodeKeepgoing,
                                    nodeRankOrderAscending:params.nodeRankOrderAscending,
                                    nodeRankAttribute:params.nodeRankAttribute,
                                    workflow:params.workflow,
                                    argString:params.argString,
                                    serverNodeUUID: frameworkService.getServerUUID()
            )


            
            //parse options
            if(!execution.loglevel){
                execution.loglevel=defaultLogLevel
            }
                
        } else {
            throw new IllegalArgumentException("insufficient params to create a new Execution instance: " + params)
        }
        return execution
    }

    /**
     * creates an execution with the parameters, and evaluates dynamic buildstamp
     */
    def Execution createExecutionAndPrep(Map params, Framework framework, String user) throws ExecutionServiceException{
        def props =[:]
        props.putAll(params)
        if(!props.user){
            props.user=user
        }
        def Execution execution = createExecution(props, framework)
        execution.dateStarted = new Date()

        if(execution.argString =~ /\$\{DATE:(.*)\}/){

            def newstr = execution.argString
            try{
                newstr = execution.argString.replaceAll(/\$\{DATE:(.*)\}/,{ all,tstamp ->
                    new SimpleDateFormat(tstamp).format(execution.dateStarted)
                })
            }catch(IllegalArgumentException e){
                log.warn(e)
            }


            execution.argString=newstr
        }

        if(execution.workflow){
            if(!execution.workflow.save(flush:true)){
                def err=execution.workflow.errors.allErrors.collect { it.toString() }.join(", ")
                log.error("unable to save workflow: ${err}")
                throw new ExecutionServiceException("unable to save workflow: "+err)
            }
        }

        if(!execution.save(flush:true)){
            execution.errors.allErrors.each { log.warn(it.defaultMessage) }
            log.error("unable to save execution")
            throw new ExecutionServiceException("unable to save execution")
        }
        return execution
    }


    public Map executeScheduledExecution(ScheduledExecution scheduledExecution, Framework framework, Subject subject, params) {
        if (!frameworkService.authorizeProjectJobAll(framework, scheduledExecution, [AuthConstants.ACTION_RUN],
            scheduledExecution.project)) {
//            unauthorized("Execute Job ${scheduledExecution.extid}")
            return [success: false, error: 'unauthorized', message: "Unauthorized: Execute Job ${scheduledExecution.extid}"]
        }

        def extra = params.extra

        try {
            def Execution e = createExecution(scheduledExecution, framework, params.user, extra)
            def extraMap = selectSecureOptionInput(scheduledExecution, extra)
            def extraParamsExposed = selectSecureOptionInput(scheduledExecution, extra, true)
            def eid = scheduledExecutionService.scheduleTempJob(scheduledExecution, params.user, subject, e, extraMap, extraParamsExposed) ;

            return [executionId: eid, name: scheduledExecution.jobName, execution: e]
        } catch (ExecutionServiceValidationException exc) {
            return [error: 'invalid', message: exc.getMessage(), options: exc.getOptions(), errors: exc.getErrors()]
        } catch (ExecutionServiceException exc) {
            def msg = exc.getMessage()
            log.error("exception: " + exc)
            return [error: 'failed', message: msg, options:extra.option]
        }
    }
    private Execution int_createExecution(ScheduledExecution se,framework,user,extra){
        def props = [:]

        se = ScheduledExecution.get(se.id)
        props.putAll(se.properties)
        if (user) {
            props.user = user
        }
        if (extra && 'true' == extra['_replaceNodeFilters']) {
            //remove all existing node filters to replace with input filters
            props = props.findAll {!(it.key =~ /^node(Include|Exclude).*$/)}
        }
        if (extra) {
            props.putAll(extra)
        }

        //evaluate embedded Job options for validation
        HashMap optparams = validateJobInputOptions(props, se)
        optparams = removeSecureOptionEntries(se, optparams)

        props.argString = generateJobArgline(se, optparams)

        Workflow workflow = new Workflow(se.workflow)
        //create duplicate workflow
        props.workflow = workflow

        Execution execution = createExecution(props, framework)
        execution.dateStarted = new Date()

        if (execution.argString =~ /\$\{DATE:(.*)\}/) {

            def newstr = execution.argString
            try {
                newstr = execution.argString.replaceAll(/\$\{DATE:(.*)\}/, { all, tstamp ->
                    new SimpleDateFormat(tstamp).format(execution.dateStarted)
                })
            } catch (IllegalArgumentException e) {
                log.warn(e)
            }


            execution.argString = newstr
        }
        execution.scheduledExecution=se
        if (workflow && !workflow.save(flush:true)) {
            execution.workflow.errors.allErrors.each { log.error(it.toString()) }
            log.error("unable to save execution workflow")
            throw new ExecutionServiceException("unable to create execution workflow")
        }
        if (!execution.save(flush:true)) {
            execution.errors.allErrors.each { log.warn(it.toString()) }
            log.error("unable to save execution")
            throw new ExecutionServiceException("unable to create execution")
        }
        return execution
    }
    def Execution createExecution(ScheduledExecution se, Framework framework, String user, Map extra = [:]) throws ExecutionServiceException {
        if (!se.multipleExecutions) {
            synchronized (this) {
                //find any currently running executions for this job, and if so, throw exception
                def found = Execution.executeQuery('from Execution where dateCompleted is null and scheduledExecution=:se and dateStarted is not null', [se: se])
//                    System.err.println("multiexec check ${se.version}: ${found}")
                if (found) {
                    throw new ExecutionServiceException('Job "' + se.jobName + '" [' + se.extid + '] is currently being executed (execution [' + found.id + '])')
                }
                return int_createExecution(se,framework,user,extra)
            }
        }else{
            return int_createExecution(se,framework,user,extra)
        }
    }

    /**
     * Parse input "option.NAME" values, or a single "argString" value. Add default missing defaults for required
     * options. Validate the values for the Job options and throw exception if validation fails. Return a map of name
     * to value for the parsed options.
     * @param props
     * @param scheduledExec
     * @return
     */
    private HashMap validateJobInputOptions(Map props, ScheduledExecution scheduledExec) {
        def optparams = filterOptParams(props)
        if (!optparams && props.argString) {
            optparams = frameworkService.parseOptsFromString(props.argString)
        }
        optparams = addOptionDefaults(scheduledExec, optparams)
        validateOptionValues(scheduledExec, optparams)
        return optparams
    }

    /**
     * evaluate the options and return a map of the values of any secure options, using defaults for required options if
     * they are not present, and selecting between exposed/hidden secure values
     */
    def Map selectSecureOptionInput(ScheduledExecution scheduledExecution, Map params, Boolean exposed=false) throws ExecutionServiceException {
        def results=[:]
        def optparams
        if (params?.argString) {
            optparams = frameworkService.parseOptsFromString(params.argString)
        }else if(params?.optparams){
            optparams=params.optparams
        }else{
            optparams = ExecutionService.filterOptParams(params)
        }
        final options = scheduledExecution.options
        if (options) {
            options.each {Option opt ->
                if (opt.secureInput && optparams[opt.name] && (exposed && opt.secureExposed || !exposed && !opt.secureExposed)) {
                    results[opt.name]= optparams[opt.name]
                }else if (opt.secureInput && opt.defaultValue && opt.required && (exposed && opt.secureExposed || !exposed && !opt.secureExposed)) {
                    results[opt.name] = opt.defaultValue
                }
            }
        }
        return results
    }
    /**
     * Return a map containing all params that are not secure option parameters
     */
    def Map removeSecureOptionEntries(ScheduledExecution scheduledExecution, Map params) throws ExecutionServiceException {
        def results=new HashMap(params)
        final options = scheduledExecution.options
        if (options) {
            options.each {Option opt ->
                if (opt.secureInput) {
                    results.remove(opt.name)
                }
            }
        }
        return results
    }

    /**
     * evaluate the options in the input argString, and if any Options defined for the Job have required=true, have a
     * defaultValue, and have null value in the input properties, then append the default option value to the argString
     */
    def Map addOptionDefaults(ScheduledExecution scheduledExecution, Map optparams) throws ExecutionServiceException {
        def newmap = new HashMap(optparams)

        final options = scheduledExecution.options
        if (options) {
            def defaultoptions=[:]
            options.each {Option opt ->
                if (null==optparams[opt.name] && opt.defaultValue) {
                    defaultoptions[opt.name]=opt.defaultValue
                }
            }
            if(defaultoptions){
                newmap.putAll(defaultoptions)
            }
        }
        return newmap
    }


    /**
     * evaluate the options in the input properties, and if any Options defined for the Job have regex constraints,
     * require the values in the properties to match the regular expressions.  Throw ExecutionServiceException if
     * any options don't match.
     */
    def boolean validateInputOptionValues(ScheduledExecution scheduledExecution, Map props) throws ExecutionServiceException{
        def optparams = ExecutionService.filterOptParams(props)
        if(!optparams && props.argString){
            optparams = parseJobOptsFromString(scheduledExecution,props.argString)
        }
        return validateOptionValues(scheduledExecution,optparams)
    }
    /**
     * evaluate the options value map, and if any Options defined for the Job have regex constraints,
     * require the values in the properties to match the regular expressions.  Throw ExecutionServiceException if
     * any options don't match.
     */
    def boolean validateOptionValues(ScheduledExecution scheduledExecution, Map optparams) throws ExecutionServiceValidationException {

        def fail = false
        def StringBuffer sb = new StringBuffer()

        def failedkeys=[:]
        if (scheduledExecution.options) {
            scheduledExecution.options.each {Option opt ->
                if (!opt.multivalued && optparams[opt.name] && !(optparams[opt.name] instanceof String)) {
                    fail = true
                    if (!failedkeys[opt.name]) {
                        failedkeys[opt.name] = ''
                    }
                    final String msg = "Option '${opt.name}' value: ${opt.secureInput ? '***' : optparams[opt.name]} does not allow multiple values.\n"
                    sb << msg
                    failedkeys[opt.name] += msg
                    return
                }
                if (opt.required && !optparams[opt.name]) {
                    fail = true
                    if (!failedkeys[opt.name]) {
                        failedkeys[opt.name] = ''
                    }
                    final String msg = "Option '${opt.name}' is required.\n"
                    sb << msg
                    failedkeys[opt.name] += msg
                    return
                }
                if(opt.multivalued){
                    if (opt.regex && !opt.enforced && optparams[opt.name]) {
                        def val = [optparams[opt.name]].flatten()
                        val.each{value->
                            if (!(value ==~ opt.regex)) {
                                fail = true
                                if (!failedkeys[opt.name]) {
                                    failedkeys[opt.name] = ''
                                }

                            }
                        }
                        if (fail) {
                            final String msg = "Option '${opt.name}' values: ${optparams[opt.name]} did not all match regular expression: '${opt.regex}'\n"
                            sb << msg
                            failedkeys[opt.name] += msg
                            return
                        }
                    }
                    if (opt.enforced && opt.values && optparams[opt.name]) {
                        def val = [optparams[opt.name]].flatten();
                        if (!opt.values.containsAll(val.grep{it})) {
                            fail = true
                            if (!failedkeys[opt.name]) {
                                failedkeys[opt.name] = ''
                            }
                            final String msg = "Option '${opt.name}' values: ${optparams[opt.name]} were not all in the allowed values: ${opt.values}\n"
                            sb << msg
                            failedkeys[opt.name] += msg
                            return
                        }
                    }
                }else{
                    if (opt.regex && !opt.enforced && optparams[opt.name]) {
                        if (!(optparams[opt.name] ==~ opt.regex)) {
                            fail = true
                            if (!failedkeys[opt.name]) {
                                failedkeys[opt.name] = ''
                            }
                            String msg = opt.secureInput? "Option '${opt.name}' value was not valid\n":"Option '${opt.name}' doesn't match regular expression ${opt.regex}, value: ${optparams[opt.name]}\n"
                            sb << msg
                            failedkeys[opt.name] += msg
                            return
                        }
                    }
                    if (opt.enforced && opt.values && optparams[opt.name] && optparams[opt.name] instanceof String && !opt.values.contains(optparams[opt.name])){
                        fail=true
                        if(!failedkeys[opt.name]){
                            failedkeys[opt.name]=''
                        }
                        final String msg = opt.secureInput ? "Option '${opt.name}' value was not valid\n" : "Option '${opt.name}' value: ${optparams[opt.name]} was not in the allowed values: ${opt.values}\n"
                        sb << msg
                        failedkeys[opt.name]+=msg
                        return
                    }
                }
            }
        }
        if (fail) {
            def msg = sb.toString()
            throw new ExecutionServiceValidationException(msg,optparams,failedkeys)
        }
        return !fail
    }

    /**
     *  Parse an argString for a Job, treating multi-valued options as delimiter-separated and converting to a List of values
     * @param scheduledExecution
     * @param argString
     * @return map of option name to value, where value is a String or a List of Strings
     */
    def Map parseJobOptsFromString(ScheduledExecution scheduledExecution, String argString){
        def optparams = frameworkService.parseOptsFromString(argString)
        if(optparams){
            //look for multi-valued options and try to split on delimiters
            scheduledExecution.options.each{Option opt->
                if(opt.multivalued && optparams[opt.name]){
                    def arr = optparams[opt.name].split(Pattern.quote(opt.delimiter))
                    optparams[opt.name]=arr as List
                }
            }
        }
        return optparams
    }

    def saveExecutionState( schedId, exId, Map props, Map execmap=null){
        def ScheduledExecution scheduledExecution
        def Execution execution = Execution.get(exId)
        execution.properties=props
        if (props.failedNodes) {
            execution.failedNodeList = props.failedNodes.join(",")
        }
        def boolean execSaved=false
        if (execution.save(flush:true)) {
            log.debug("saved execution status. id: ${execution.id}")
            execSaved=true
        } else {

            execution.errors.allErrors.each { log.warn(it.defaultMessage) }
            log.error("failed to save execution status")
        }
        if (schedId) {
            scheduledExecution = ScheduledExecution.get(schedId)
        }

        def jobname="adhoc"
        def jobid=null
        def summary= summarizeJob(scheduledExecution, execution)
        if (scheduledExecution) {
            jobname = scheduledExecution.groupPath ? scheduledExecution.generateFullName() : scheduledExecution.jobName
            jobid = scheduledExecution.id
        }
        if(execSaved) {
            //summarize node success
            String node=null
            int sucCount=-1;
            int failedCount=-1;
            int totalCount=0;
            if (execmap && execmap.noderecorder && execmap.noderecorder instanceof NodeRecorder) {
                NodeRecorder rec = (NodeRecorder) execmap.noderecorder
                final HashSet<String> success = rec.getSuccessfulNodes()
                final Map<String,Object> failedMap = rec.getFailedNodes()
                final HashSet<String> failed = new HashSet<String>(failedMap.keySet())
                final HashSet<String> matched = rec.getMatchedNodes()
                node = [success.size(),failed.size(),matched.size()].join("/")
                sucCount=success.size()
                failedCount=failed.size()
                totalCount=matched.size()
            }
            logExecution(null, execution.project, execution.user, "true" == execution.status, exId,
                execution.dateStarted, jobid, jobname, summary, props.cancelled,
                node, execution.abortedby)

            def context = execmap?.thread?.context
            notificationService.triggerJobNotification(props.status == 'true' ? 'success' : 'failure', schedId, [execution: execution,nodestatus:[succeeded:sucCount,failed:failedCount,total:totalCount],context:context])
        }
    }
    public String summarizeJob(ScheduledExecution job=null,Execution exec){
//        if(job){
//            return job.groupPath?job.generateFullName():job.jobName
//        }else{
            //summarize execution
            StringBuffer sb = new StringBuffer()
            final def wfsize = exec.workflow.commands.size()

            if(wfsize>0){
                sb<<exec.workflow.commands[0].summarize()
            }else{
                sb<< "[Empty workflow]"
            }
            if(wfsize>1){
                sb << " [... ${wfsize} steps]"
            }
            return sb.toString()
//        }
    }
    /**
     * Update a scheduledExecution statistics with a successful execution duration
     * @param schedId
     * @param execution
     * @return
     */
    def updateScheduledExecStatistics(Long schedId, eId, long time){
        def success = false
        try {
            ScheduledExecution.withNewSession {
                def scheduledExecution = ScheduledExecution.get(schedId)

                if (scheduledExecution.scheduled) {
                    scheduledExecution.nextExecution = scheduledExecutionService.nextExecutionTime(scheduledExecution)
                }
                //TODO: record job stats in separate domain class
                if (null == scheduledExecution.execCount || 0 == scheduledExecution.execCount || null == scheduledExecution.totalTime || 0 == scheduledExecution.totalTime) {
                    scheduledExecution.execCount = 1
                    scheduledExecution.totalTime = time
                } else if (scheduledExecution.execCount > 0 && scheduledExecution.execCount < 10) {
                    scheduledExecution.execCount++
                    scheduledExecution.totalTime += time
                } else if (scheduledExecution.execCount >= 10) {
                    def popTime = scheduledExecution.totalTime.intdiv(scheduledExecution.execCount)
                    scheduledExecution.totalTime -= popTime
                    scheduledExecution.totalTime += time
                }
                if (scheduledExecution.save(flush:true)) {
                    log.info("updated scheduled Execution")
                } else {
                    scheduledExecution.errors.allErrors.each {log.warn(it.defaultMessage)}
                    log.warn("failed saving execution to history")
                }
                success = true
            }
        } catch (org.springframework.dao.OptimisticLockingFailureException e) {
            log.error("Caught OptimisticLockingFailure, will retry updateScheduledExecStatistics for ${eId}")
        } catch (StaleObjectStateException e) {
            log.error("Caught StaleObjectState, will retry updateScheduledExecStatistics for ${eId}")
        }
        return success
    }

    def File maybeCreateAdhocLogDir(Execution execution, Framework framework) {
        return maybeCreateAdhocLogDir(execution.project,framework)
    }


    /**
     * create a log dir for the ScheduledExecution job based on group and jobname
     */
    def File maybeCreateJobLogDir(ScheduledExecution job, Framework framework){
        def logdir = new File(Constants.getFrameworkLogsDir(framework.getBaseDir().getAbsolutePath()),
            "rundeck/${job.project}/${job.groupPath?job.groupPath+'/':''}${job.jobName}")

        if (!logdir.exists()) {
            log.info("Creating log dir: " + logdir.getAbsolutePath())
            logdir.mkdirs()
        }
        return logdir
    }
    def File maybeCreateLogDir(String project, String type, String name, Framework framework){
        def logdir = new File(Constants.getFrameworkLogsDir(framework.getBaseDir().getAbsolutePath()),
            "rundeck/${project}/deployments/${type}/${name}/logs")

        if (!logdir.exists()) {
            log.info("Creating log dir: " + logdir.getAbsolutePath())
            logdir.mkdirs()
        }
        return logdir
    }
    def File maybeCreateAdhocLogDir(String project, Framework framework){
        def logdir = new File(Constants.getFrameworkLogsDir(framework.getBaseDir().getAbsolutePath()),
            "rundeck/${project}/run/logs")

        if (!logdir.exists()) {
            log.info("Creating log dir: " + logdir.getAbsolutePath())
            logdir.mkdirs()
        }
        return logdir
    }



    /**
    * Generate an argString from a map of options and values
     */
    public static String generateArgline(Map<String,String> opts){
        def argsList = []
        for (Map.Entry<String, String> entry : opts.entrySet()) {
            String val = opts.get(entry.key)
            argsList<<'-'+entry.key
            argsList<<val
        }
        return OptsUtil.join(argsList)
    }

    /**
    * Generate an argString from a map of options and values
     */
    public static String generateJobArgline(ScheduledExecution sched,Map<String,Object> opts){
        def newopts = [:]
        def addOptVal={key,obj,Option opt=null->
            String val
            if (obj instanceof String[] || obj instanceof Collection) {
                //join with delimiter
                if (opt && opt.delimiter) {
                    val = obj.grep { it }.join(opt.delimiter)
                } else {
                    val = obj.grep { it }.join(",")
                }
            } else {
                val = (String) obj
            }
            newopts[key] = val
        }
        for (Option opt : sched.options.findAll {opts.containsKey(it.name)}) {
            addOptVal(opt.name, opts.get(opt.name),opt)
        }
        //add any input options that don't match job options, to preserve information
        opts.keySet().findAll {!newopts[it]}.sort().each {
            addOptVal(it, opts[it])
        }
        return generateArgline(newopts)
    }
    /**
     * Returns a map of option names to values, from input parameters of the form "option.NAME"
     * @param params
     * @return
     */
    public static Map filterOptParams(Map params) {
        def result = [ : ]
        def optpatt = '^option\\.(.*)$'
        params.each { key, val ->
            def matcher = key =~ optpatt
            if (matcher.matches()) {
                def optname = matcher.group(1)
                if(val instanceof Collection){
                    result[optname] = new ArrayList(val).grep{it}
                }else if (val instanceof String[]){
                    result[optname] = new ArrayList(Arrays.asList(val)).grep{it}
                }else if(val instanceof String){
                    result[optname]=val
                }else{
                    System.err.println("unable to determine parameter value type: "+val + " ("+val.getClass().getName()+")")
                }
            }
        }
        return result
    }

    def int countNowRunning() {

        def total = Execution.createCriteria().count{
            and {
                isNull("dateCompleted")
            }
        };
        return total
    }
    def public static EXEC_FORMAT_SEQUENCE=['time','level','user','module','command','node','context']

    @Override
    public boolean isNodeDispatchStep(StepExecutionItem item) {
        if (!(item instanceof JobExecutionItem)) {
            throw new IllegalArgumentException("Unsupported item type: " + item.getClass().getName());
        }
        JobExecutionItem jitem = (JobExecutionItem) item;
        return jitem.isNodeStep()
    }

    /**
     * Execute the workflow step, the executionItem is expected to be a {@link JobExecutionItem} to execute a Job reference workflow step.
     * @param executionContext
     * @param executionItem
     * @return
     * @throws StepException
     */
    StepExecutionResult executeWorkflowStep(StepExecutionContext executionContext, StepExecutionItem executionItem) throws StepException{
        if (!(executionItem instanceof JobExecutionItem)) {
            throw new IllegalArgumentException("Unsupported item type: " + executionItem.getClass().getName());
        }
        def createFailure = { FailureReason reason, String msg ->
            return new StepExecutionResultImpl(null, reason, msg)
        }
        def createSuccess = {
            return new StepExecutionResultImpl()
        }
        JobExecutionItem jitem = (JobExecutionItem) executionItem
        return runJobRefExecutionItem(executionContext, jitem, null, null, createFailure, createSuccess)
    }

    ///////////////
      //for loading i18n messages
      //////////////

      /**
       * @parameter key
       * @returns corresponding value from messages.properties
       */
      def lookupMessage(String theKey, Object[] data, String defaultMessage=null) {
          def locale = getLocale()
          def theValue = null
          MessageSource messageSource = applicationContext.getBean("messageSource")
          try {
              theValue =  messageSource.getMessage(theKey,data,locale )
          } catch (org.springframework.context.NoSuchMessageException e){
              log.error "Missing message ${theKey}"
          } catch (java.lang.NullPointerException e) {
              log.error "Expression does not exist."
          }
          if(null==theValue && defaultMessage){
              MessageFormat format = new MessageFormat(defaultMessage);
              theValue=format.format(data)
          }
          return theValue
      }


      /**
       * Get the locale
       * @return locale
       * */
      def getLocale() {
          def Locale locale = null
          try {
              locale = RCU.getLocale(getSession().request)
          }
          catch(java.lang.IllegalStateException e){
              //log.debug "Running in console?"
          }
          //log.debug "locale: ${locale}"
          return locale
      }
      /**
       * Get the HTTP Session
       * @return session
       **/
      private HttpSession getSession() {
          return RequestContextHolder.currentRequestAttributes().getSession()
      }

    /**
     * Execute a job reference workflow with a particular context, optionally overriding the target node set,
     * and return the result based on the createFailure/createSuccess closures
     * @param executionContext
     * @param jitem
     * @param nodeselector
     * @param nodeSet
     * @param createFailure closure that takes {@link FailureReason} and String as arguments, and returns a {@link StepExecutionResult} or {@link NodeStepResult}
     * @param createSuccess closure that returns a {@link StepExecutionResult} or {@link NodeStepResult}
     * @return
     */
    private def runJobRefExecutionItem(StepExecutionContext executionContext, JobExecutionItem jitem,
            NodesSelector nodeselector, INodeSet nodeSet, Closure createFailure, Closure createSuccess) {
        RequestHelper.doWithMockRequest {
            def id
            def result

            def group = null
            def name = null
            def m = jitem.jobIdentifier =~ '^/?(.+)/([^/]+)$'
            if (m.matches()) {
                group = m.group(1)
                name = m.group(2)
            } else {
                name = jitem.jobIdentifier
            }
            def schedlist = ScheduledExecution.findAllScheduledExecutions(group, name, executionContext.getFrameworkProject())
            if (!schedlist || 1 != schedlist.size()) {
                def msg = "Job [${jitem.jobIdentifier}] not found, project: ${executionContext.getFrameworkProject()}"
                executionContext.getExecutionListener().log(0, msg)
                throw new StepException(msg, JobReferenceFailureReason.NotFound)
            }
            id = schedlist[0].id
            def StepExecutionContext newContext
            def WorkflowExecutionItem newExecItem

            ScheduledExecution.withTransaction { status ->
                ScheduledExecution se = ScheduledExecution.get(id)

                if (!frameworkService.authorizeProjectJobAll(executionContext.getFramework(), se, [AuthConstants.ACTION_RUN], se.project)) {
                    def msg = "Unauthorized to execute job [${jitem.jobIdentifier}}: ${se.extid}"
                    executionContext.getExecutionListener().log(0, msg);
                    result = createFailure(JobReferenceFailureReason.Unauthorized, msg)
                    return
                }
                String[] newargs = jitem.args
                //substitute any data context references in the arguments
                if (null != newargs && executionContext.dataContext) {
                    newargs = DataContextUtils.replaceDataReferences(newargs, executionContext.dataContext)
                }

                final jobOptsMap = frameworkService.parseOptsFromArray(newargs)
                jobOptsMap = addOptionDefaults(se, jobOptsMap)

                //select secureAuth and secure options from the args to pass
                def secAuthOpts = selectSecureOptionInput(se, [optparams: jobOptsMap], false)
                def secOpts = selectSecureOptionInput(se, [optparams: jobOptsMap], true)

                //for secAuthOpts, evaluate each in context of original private data context
                def evalSecAuthOpts = [:]
                secAuthOpts.each { k, v ->
                    def newv = DataContextUtils.replaceDataReferences(v, executionContext.privateDataContext)
                    if (newv != v || !v.startsWith('${option.')) {
                        evalSecAuthOpts[k] = newv
                    }
                }

                //for secOpts, evaluate each in context of original secure option data context
                def evalSecOpts = [:]
                secOpts.each { k, v ->
                    def newv = DataContextUtils.replaceDataReferences(v, [option: executionContext.dataContext['secureOption']])
                    if (newv != v || !v.startsWith('${option.')) {
                        evalSecOpts[k] = newv
                    }
                }

                //for plain opts, evaluate in context of non secure data context
                final plainOpts = removeSecureOptionEntries(se, jobOptsMap)

                //define nonsecure opts entries
                def plainOptsContext = executionContext.dataContext['option']?.findAll { !executionContext.dataContext['secureOption'] || null == executionContext.dataContext['secureOption'][it.key] }
                def evalPlainOpts = [:]
                plainOpts.each { k, v ->
                    evalPlainOpts[k] = DataContextUtils.replaceDataReferences(v, [option: plainOptsContext])
                    //XXX: missing option references could be removed instead of passed on
                }

                //validate the option values
                try {
                    validateOptionValues(se, evalPlainOpts + evalSecOpts + evalSecAuthOpts)
                } catch (ExecutionServiceValidationException e) {
                    executionContext.getExecutionListener().log(0, "Option input was not valid for [${jitem.jobIdentifier}]: ${e.message}");
                    def msg = "Invalid options: ${e.errors.keySet()}"
                    result = createFailure(JobReferenceFailureReason.InvalidOptions, msg.toString())
                    return
                }

                //arg list for new context
                def stringList = evalPlainOpts.collect { ["-" + it.key, it.value] }.flatten()
                newargs = stringList.toArray(new String[stringList.size()]);

                //construct job data context
                def jobcontext = new HashMap<String, String>()
                jobcontext.id = se.extid
                jobcontext.execid = executionContext.dataContext.job?.execid ?: null;
                jobcontext.loglevel = mappedLogLevels[executionContext.loglevel]
                jobcontext.name = se.jobName
                jobcontext.group = se.groupPath
                jobcontext.project = se.project
                jobcontext.username = executionContext.getUser()
                jobcontext['user.name'] = jobcontext.username
                newExecItem = createExecutionItemForExecutionContext(se, executionContext.getFramework(), executionContext.getUser())
                newContext = createContext(se, executionContext, jobcontext, newargs, evalSecAuthOpts, evalSecOpts)
                return
            }
            if (null != result) {
                return result
            }

            if(null!=nodeSet && null!=nodeselector){
                //override the node set from the filter
                newContext = com.dtolabs.rundeck.core.execution.ExecutionContextImpl.builder(newContext)
                        .nodes(nodeSet)
                        .nodeSelector(nodeselector)
                        .build()
            }

            if (newContext.getNodes().getNodeNames().size() < 1) {
                String msg = "No nodes matched for the filters: " + newContext.getNodeSelector()
                executionContext.getExecutionListener().log(0, msg)
                throw new StepException(msg, JobReferenceFailureReason.NoMatchedNodes)
            }

            def WorkflowExecutionService service = executionContext.getFramework().getWorkflowExecutionService()

            def wresult = service.getExecutorForItem(newExecItem).executeWorkflow(newContext, newExecItem)

            if (!wresult || !wresult.success) {
                result = createFailure(JobReferenceFailureReason.JobFailed, "Job [${jitem.jobIdentifier}] failed")
            } else {
                result = createSuccess()
            }
            result.sourceResult = wresult

            return result
        }
    }
    /**
     * Execute the node step, the executionItem is expected to be a {@link JobExecutionItem} to execute a Job reference
     * as a node step on a single node.
     * @param executionContext
     * @param executionItem
     * @return
     * @throws StepException
     */
    @Override
    NodeStepResult executeNodeStep(StepExecutionContext executionContext, NodeStepExecutionItem executionItem,
                                   INodeEntry node) throws NodeStepException {
        if (!(executionItem instanceof JobExecutionItem)) {
            throw new IllegalArgumentException("Unsupported item type: " + executionItem.getClass().getName());
        }
        def createFailure= { FailureReason reason, String msg ->
            return NodeExecutorResultImpl.createFailure(reason, msg, node)
        }
        def createSuccess={
            return NodeExecutorResultImpl.createSuccess(node)
        }
        JobExecutionItem jitem = (JobExecutionItem) executionItem
        //don't override node filters, to allow option inputs to be used in the filters
        return runJobRefExecutionItem(executionContext, jitem, null, null, createFailure, createSuccess)
    }
}

/**
 * Exception thrown by the ExecutionService
 */
class ExecutionServiceException extends Exception{

    def ExecutionServiceException() {
    }
    def ExecutionServiceException(s) {
        super(s);
    }
    def ExecutionServiceException(s, throwable) {
        super(s, throwable);
    }
}


class ExecutionServiceValidationException extends ExecutionServiceException{

    Map<String,String> options;
    Map<String,String> errors;
    def ExecutionServiceValidationException() {
    }
    def ExecutionServiceValidationException(s,options,errors) {
        super(s);
        this.options=options;
        this.errors=errors;
    }
    def ExecutionServiceValidationException(s,options, errors,throwable) {
        super(s, throwable);
        this.options=options;
        this.errors=errors;
    }
    public Map<String,String> getOptions(){
        return options;
    }
    public Map<String,String> getErrors(){
        return errors;
    }
}
