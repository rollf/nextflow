package nextflow.processor
import java.nio.file.Path

import groovy.transform.InheritConstructors
import groovy.util.logging.Slf4j
import groovyx.gpars.dataflow.DataflowQueue
import groovyx.gpars.dataflow.DataflowReadChannel
import groovyx.gpars.dataflow.DataflowWriteChannel
import groovyx.gpars.dataflow.operator.DataflowEventAdapter
import groovyx.gpars.dataflow.operator.DataflowOperator
import groovyx.gpars.dataflow.operator.DataflowProcessor
import groovyx.gpars.dataflow.operator.PoisonPill
import nextflow.script.EachInParam
import nextflow.script.EnvInParam
import nextflow.script.FileInParam
import nextflow.script.FileSharedParam
import nextflow.script.InParam
import nextflow.script.ScriptType
import nextflow.script.SharedParam
import nextflow.script.StdInParam
import nextflow.script.ValueInParam
import nextflow.script.ValueSharedParam
import nextflow.util.CacheHelper
import nextflow.util.FileHelper
/**
 * Defines the parallel tasks execution logic
 *
 * @author Paolo Di Tommaso <paolo.ditommaso@gmail.com>
 */

@Slf4j
@InheritConstructors
class ParallelTaskProcessor extends TaskProcessor {

    /**
     * Keeps track of the task instance executed by the current thread
     */
    protected final ThreadLocal<TaskRun> currentTask = new ThreadLocal<>()

    @Override
    protected void createOperator() {

        def opInputs = new ArrayList(taskConfig.inputs.getChannels())
        def opOutputs = new ArrayList(taskConfig.outputs.getChannels())

        // append the shared obj to the input list
        def sharedCount = taskConfig.getInputs().count { it instanceof SharedParam }

        /*
         * check if there are some iterators declaration
         * the list holds the index in the list of all *inputs* for the {@code each} declaration
         */
        def iteratorIndexes = []
        taskConfig.inputs.eachWithIndex { param, index ->
            if( param instanceof EachInParam ) {
                log.trace "Process ${name} > got each param: ${param.name} at index: ${index} -- ${param.dump()}"
                iteratorIndexes << index
            }
        }

        /*
         * When one (or more) {@code each} are declared as input, it is created an extra
         * operator which will receive the inputs from the channel (excepts the values over iterate)
         *
         * The operator will *expand* the received inputs, iterating over the user provided value and
         * forwarding the final values the the second *parallel* processor executing the user specified task
         */
        if( iteratorIndexes ) {
            log.debug "Creating *combiner* operator for each param(s) at index(es): ${iteratorIndexes}"

            final size = opInputs.size()
            // the script implementing the iterating process
            final forwarder = createForwardWrapper(size, iteratorIndexes)
            // the channel forwarding the data from the *iterator* process to the target task
            final linkingChannels = new ArrayList(size)
            size.times { linkingChannels[it] = new DataflowQueue() }

            // instantiate the iteration process
            def params = [inputs: opInputs, outputs: linkingChannels, maxForks: 1, listeners: [new IteratorProcessInterceptor()]]
            session.allProcessors << (processor = new DataflowOperator(group, params, forwarder).start())

            // set as next inputs the result channels of the iteration process
            opInputs = linkingChannels
        }


        /*
         * create a mock closure to trigger the operator
         */
        final wrapper = createCallbackWrapper( opInputs.size(), this.&invokeTask )

        /*
         * define the max forks attribute:
         * - by default the process execution is parallel using the poolSize value
         * - when there is at least one shared variable it is executed in serial mode (maxForks==1) to guarantee thread safe access
         * - otherwise use the value defined by the user via 'taskConfig'
         */
        def maxForks = session.config.poolSize
        if( sharedCount ) {
            log.debug "Process declares shared inputs -- Using thread safe mode (maxForks=1)"
            maxForks = 1
            blocking = true
        }
        else if( taskConfig.maxForks ) {
            maxForks = taskConfig.maxForks
        }
        log.debug "Creating operator > $name -- maxForks: $maxForks"

        /*
         * finally create the operator
         */
        def params = [inputs: opInputs, outputs: opOutputs, maxForks: maxForks, listeners: [new TaskProcessorInterceptor()] ]
        session.allProcessors << (processor = new DataflowOperator(group, params, wrapper).start())

    }

    /**
     * Implements the closure which *combines* all the iteration
     *
     * @param numOfInputs Number of in/out channel
     * @param indexes The list of indexes which identify the position of iterators in the input channels
     * @return The clousre implementing the iteration/forwarding logic
     */
    protected createForwardWrapper( int numOfInputs, List indexes ) {

        final args = []
        numOfInputs.times { args << "x$it" }

        /*
         * Explaining the following closure:
         *
         * - it has to be evaluated as a string since the number of input must much the number input channel
         *   that is known only at runtime
         *
         * - 'out' holds the list of all input values which need to be forwarded (bound) to output as many
         *   times are the items in the iteration list
         *
         * - the iteration list(s) is (are) passed like in the closure inputs like the other values,
         *   the *indexes* argument defines the which of them are the iteration lists
         *
         * - 'itr' holds the list of all iteration lists
         *
         * - using the groovy method a combination of all values is create (cartesian product)
         *   see http://groovy.codehaus.org/groovy-jdk/java/util/Collection.html#combinations()
         *
         * - the resulting values are replaced in the 'out' array of values and forwarded out
         *
         */

        final str =
            """
            { ${args.join(',')} ->
                def out = [ ${args.join(',')} ]
                def itr = [ ${indexes.collect { 'x'+it }.join(',')} ]
                def cmb = itr.combinations()
                for( entries in cmb ) {
                    def count = 0
                    n.times { i->
                        if( i in indexes ) { out[i] = entries[count++] }
                    }
                    bindAllOutputValues( *out )
                }
            }
            """

        final Binding binding = new Binding( indexes: indexes, n: numOfInputs )
        final result = (Closure)new GroovyShell(binding).evaluate (str)

        return result

    }


    /**
     * Create the {@code TaskDef} data structure and initialize the task execution context
     * with the received input values
     *
     * @param values
     * @return
     */
    final protected TaskRun setupTask(List values) {
        log.trace "Setup new process > $name"

        final TaskRun task = createTaskRun()

        // -- map the inputs to a map and use to delegate closure values interpolation
        final ctx = new DelegateMap(this)
        final firstRun = task.index == 1
        int count = 0

        /*
         * initialize the inputs for this task instances
         */
        task.inputs.keySet().each { InParam param ->

            // add the value to the task instance
            def val = decodeInputValue(param,values)

            switch(param) {
                case EachInParam:
                case ValueInParam:
                    ctx[param.name] = val
                    break

                case FileInParam:
                    def fileParam = param as FileInParam
                    def normalized = normalizeInputToFiles(val,count)
                    def resolved = expandWildcards( fileParam.filePattern, normalized )
                    ctx[ param.name ] = singleItemOrList(resolved)
                    count += resolved.size()
                    val = resolved
                    break

                case FileSharedParam:
                    def fileParam = param as FileSharedParam
                    if( firstRun ) {
                        def normalized = normalizeInputToFiles(val,count)
                        if( normalized.size() > 1 )
                            throw new IllegalStateException("Cannot share multiple files")

                        def resolved = expandWildcards( fileParam.filePattern, normalized )
                        count += resolved.size()
                        val = resolved
                        // track this obj
                        sharedObjs[(SharedParam)param] = val
                    }
                    else {
                        val = sharedObjs[(SharedParam)param]
                    }

                    ctx[ fileParam.name ] = singleItemOrList(val)
                    break

                case ValueSharedParam:
                    if( firstRun )
                        sharedObjs[(SharedParam)param] = val
                    else
                        val = sharedObjs[(SharedParam)param]

                    ctx[param.name] = val
                    break

                case StdInParam:
                case EnvInParam:
                    // nothing to do
                    break

                default:
                    log.debug "Unsupported input param type: ${param?.class?.simpleName}"
            }

            // add the value to the task instance context
            task.setInput(param, val)
        }

        /*
         * initialize the task code to be executed
         */
        task.code = this.code.clone() as Closure
        task.code.delegate = ctx
        task.code.setResolveStrategy(Closure.DELEGATE_FIRST)

        return task
    }


    /**
     * The processor execution body
     *
     * @param processor
     * @param values
     */
    final protected void invokeTask( def args ) {

        // create and initialize the task instance to be executed
        List params = args instanceof List ? args : [args]
        final task = setupTask(params)

        // -- call the closure and execute the script
        currentTask.set(task)

        // Important!
        // when the task is implemented by a script string
        // Invokes the closure which return the script whit all the variables replaced with the actual values
        if( type == ScriptType.SCRIPTLET ) {
            task.script = getScriptlet(task.code)
        }

        // create an hash for the inputs and code
        // Does it exist in the cache?
        // NO --> launch the task
        // YES --> Does it exited OK and exists the expected outputs?
        //          YES --> return the outputs
        //          NO  --> launch the task

        def keys = [ session.uniqueId, task.script ]
        // add all the input name-value pairs to the key generator
        task.inputs.each { keys << it.key.name << it.value }

        log.trace "[${task.name}] cache keys: ${keys}"
        def hash = CacheHelper.hasher(keys).hash()
        Path folder = FileHelper.getWorkFolder(session.workDir, hash)
        log.trace "[${task.name}] cacheable folder: $folder"

        def cached = session.cacheable && taskConfig.cacheable && checkCachedOutput(task,folder, hash)
        if( !cached ) {
            log.info "[${getHashLog(hash)}] Running process > ${task.name}"

            // set the working directory
            task.workDirectory = createTaskFolder(folder, hash)
            log.trace "[${task.name}] actual run folder: ${task.workDirectory}"

            // submit task for execution
            submitTask( task )
        }
    }


    class TaskProcessorInterceptor extends DataflowEventAdapter {

        @Override
        public List<Object> beforeRun(final DataflowProcessor processor, final List<Object> messages) {
            log.trace "Before run > ${name} -- messages: ${messages}"
            // this counter increment must be here, otherwise it is not coherent
            instanceCount.incrementAndGet()
            return messages;
        }


        @Override
        void afterRun(DataflowProcessor processor, List<Object> messages) {
            log.trace "After run > ${currentTask.get()?.name ?: name}"
            currentTask.remove()
        }

        @Override
        public Object messageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            if( log.isTraceEnabled() ) {
                def channelName = taskConfig.inputs?.names?.get(index)
                def taskName = currentTask.get()?.name ?: name
                log.trace "Message arrived > ${taskName} -- ${channelName} => ${message}"
            }

            return message;
        }

        @Override
        public Object controlMessageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            if( log.isTraceEnabled() ) {
                def channelName = taskConfig.inputs?.names?.get(index)
                def taskName = currentTask.get()?.name ?: name
                log.trace "Control message arrived > ${taskName} -- ${channelName} => ${message}"
            }

            if( message == PoisonPill.instance ) {
                log.trace "Poison pill arrived for process > ${currentTask.get()?.name ?: name} -- terminated"
                receivedPoisonPill = true

                // check if the task is terminated
                checkProcessTermination()

                // this control message avoid to stop the operator and
                // propagate the PoisonPill to the downstream processes
                return StopQuietly.instance
            }
            else {
                return message;
            }
        }

        @Override
        public void afterStop(final DataflowProcessor processor) {
            log.trace "After stop > ${name}"

            // bind shared outputs
            ParallelTaskProcessor.this.sharedObjs?.each { param, obj ->
                if( !param.outChannel ) return
                log.trace "Binding shared out param: ${param.name} = ${obj}"
                if( obj instanceof Collection )
                    obj = obj[0]
                if( obj instanceof FileHolder )
                    obj = obj.storePath
                param.outChannel.bind( obj )
            }
        }

        /**
         * Invoked if an exception occurs. Unless overridden by subclasses this implementation returns true to terminate the operator.
         * If any of the listeners returns true, the operator will terminate.
         * Exceptions outside of the operator's body or listeners' messageSentOut() handlers will terminate the operator irrespective of the listeners' votes.
         * When using maxForks, the method may be invoked from threads running the forks.
         * @param processor
         * @param error
         * @return
         */
        public boolean onException(final DataflowProcessor processor, final Throwable error) {
            handleException( error, currentTask.get() )
        }

    }

    /*
     * logger class for the *iterator* processor
     */
    class IteratorProcessInterceptor extends DataflowEventAdapter {

        @Override
        public boolean onException(final DataflowProcessor processor, final Throwable e) {
            log.error "process '$name' > error on internal iteration process", e
            return true;
        }

        @Override
        public Object messageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            log.trace "process '$name' > message arrived for iterator '${taskConfig.inputs.names[index]}' with value: '$message'"
            return message;
        }

        @Override
        public Object messageSentOut(final DataflowProcessor processor, final DataflowWriteChannel<Object> channel, final int index, final Object message) {
            log.trace "process '$name' > message forwarded for iterator '${taskConfig.inputs.names[index]}' with value: '$message'"
            return message;
        }


        @Override
        public Object controlMessageArrived(final DataflowProcessor processor, final DataflowReadChannel<Object> channel, final int index, final Object message) {
            log.trace "process '$name' > control message arrived for iterator '${taskConfig.inputs.names[index]}'"
            return message;
        }
    }


}
