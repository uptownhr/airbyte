/*
 * Copyright (c) 2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.cdk.load.task

import io.airbyte.cdk.load.command.DestinationCatalog
import io.airbyte.cdk.load.command.DestinationConfiguration
import io.airbyte.cdk.load.command.DestinationStream
import io.airbyte.cdk.load.message.BatchEnvelope
import io.airbyte.cdk.load.message.BatchState
import io.airbyte.cdk.load.message.ChannelMessageQueue
import io.airbyte.cdk.load.message.CheckpointMessageWrapped
import io.airbyte.cdk.load.message.DestinationRecordRaw
import io.airbyte.cdk.load.message.DestinationStreamEvent
import io.airbyte.cdk.load.message.MessageQueue
import io.airbyte.cdk.load.message.MessageQueueSupplier
import io.airbyte.cdk.load.message.PartitionedQueue
import io.airbyte.cdk.load.message.PipelineEvent
import io.airbyte.cdk.load.message.QueueWriter
import io.airbyte.cdk.load.message.SimpleBatch
import io.airbyte.cdk.load.message.StreamKey
import io.airbyte.cdk.load.pipeline.BatchUpdate
import io.airbyte.cdk.load.pipeline.InputPartitioner
import io.airbyte.cdk.load.pipeline.LoadPipeline
import io.airbyte.cdk.load.state.Reserved
import io.airbyte.cdk.load.state.StreamManager
import io.airbyte.cdk.load.state.SyncManager
import io.airbyte.cdk.load.task.implementor.CloseStreamTaskFactory
import io.airbyte.cdk.load.task.implementor.FailStreamTask
import io.airbyte.cdk.load.task.implementor.FailStreamTaskFactory
import io.airbyte.cdk.load.task.implementor.FailSyncTaskFactory
import io.airbyte.cdk.load.task.implementor.FileTransferQueueMessage
import io.airbyte.cdk.load.task.implementor.OpenStreamTaskFactory
import io.airbyte.cdk.load.task.implementor.ProcessBatchTaskFactory
import io.airbyte.cdk.load.task.implementor.ProcessFileTaskFactory
import io.airbyte.cdk.load.task.implementor.ProcessRecordsTaskFactory
import io.airbyte.cdk.load.task.implementor.SetupTaskFactory
import io.airbyte.cdk.load.task.implementor.TeardownTaskFactory
import io.airbyte.cdk.load.task.internal.DefaultInputConsumerTaskFactory
import io.airbyte.cdk.load.task.internal.FlushCheckpointsTaskFactory
import io.airbyte.cdk.load.task.internal.FlushTickTask
import io.airbyte.cdk.load.task.internal.ReservingDeserializingInputFlow
import io.airbyte.cdk.load.task.internal.SpillToDiskTask
import io.airbyte.cdk.load.task.internal.SpillToDiskTaskFactory
import io.airbyte.cdk.load.task.internal.UpdateBatchStateTaskFactory
import io.airbyte.cdk.load.task.internal.UpdateCheckpointsTask
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class DestinationTaskLauncherUTest {
    private val taskScopeProvider: TaskScopeProvider = mockk(relaxed = true)
    private val catalog: DestinationCatalog = mockk(relaxed = true)
    private val syncManager: SyncManager = mockk(relaxed = true)

    // Internal Tasks
    private val inputConsumerTaskFactory: DefaultInputConsumerTaskFactory = mockk(relaxed = true)
    private val spillToDiskTaskFactory: SpillToDiskTaskFactory = mockk(relaxed = true)
    private val flushTickTask: FlushTickTask = mockk(relaxed = true)

    // Implementor Tasks
    private val setupTaskFactory: SetupTaskFactory = mockk(relaxed = true)
    private val openStreamTaskFactory: OpenStreamTaskFactory = mockk(relaxed = true)
    private val processRecordsTaskFactory: ProcessRecordsTaskFactory = mockk(relaxed = true)
    private val processFileTaskFactory: ProcessFileTaskFactory = mockk(relaxed = true)
    private val processBatchTaskFactory: ProcessBatchTaskFactory = mockk(relaxed = true)
    private val closeStreamTaskFactory: CloseStreamTaskFactory = mockk(relaxed = true)
    private val teardownTaskFactory: TeardownTaskFactory = mockk(relaxed = true)

    // Checkpoint Tasks
    private val flushCheckpointsTaskFactory: FlushCheckpointsTaskFactory = mockk(relaxed = true)
    private val updateCheckpointsTask: UpdateCheckpointsTask = mockk(relaxed = true)
    private val config: DestinationConfiguration = mockk(relaxed = true)

    // Exception tasks
    private val failStreamTaskFactory: FailStreamTaskFactory = mockk(relaxed = true)
    private val failSyncTaskFactory: FailSyncTaskFactory = mockk(relaxed = true)

    // Input Comsumer requirements
    private val inputFlow: ReservingDeserializingInputFlow = mockk(relaxed = true)
    private val recordQueueSupplier:
        MessageQueueSupplier<DestinationStream.Descriptor, Reserved<DestinationStreamEvent>> =
        mockk(relaxed = true)
    private val checkpointQueue: QueueWriter<Reserved<CheckpointMessageWrapped>> =
        mockk(relaxed = true)
    private val fileTransferQueue: MessageQueue<FileTransferQueueMessage> = mockk(relaxed = true)
    private val openStreamQueue: MessageQueue<DestinationStream> = mockk(relaxed = true)

    private val recordQueueForPipeline:
        PartitionedQueue<PipelineEvent<StreamKey, DestinationRecordRaw>> =
        mockk(relaxed = true)
    private val batchUpdateQueue: ChannelMessageQueue<BatchUpdate> = mockk(relaxed = true)
    private val partitioner: InputPartitioner = mockk(relaxed = true)
    private val updateBatchTaskFactory: UpdateBatchStateTaskFactory = mockk(relaxed = true)

    private fun getDefaultDestinationTaskLauncher(
        useFileTransfer: Boolean,
        loadPipeline: LoadPipeline? = null
    ): DefaultDestinationTaskLauncher<Nothing> {
        return DefaultDestinationTaskLauncher(
            taskScopeProvider,
            catalog,
            config,
            syncManager,
            inputConsumerTaskFactory,
            spillToDiskTaskFactory,
            flushTickTask,
            setupTaskFactory,
            openStreamTaskFactory,
            processRecordsTaskFactory,
            processFileTaskFactory,
            processBatchTaskFactory,
            closeStreamTaskFactory,
            teardownTaskFactory,
            flushCheckpointsTaskFactory,
            updateCheckpointsTask,
            failStreamTaskFactory,
            failSyncTaskFactory,
            useFileTransfer,
            inputFlow,
            recordQueueSupplier,
            checkpointQueue,
            fileTransferQueue,
            openStreamQueue,

            // New interface shim
            recordQueueForPipeline,
            batchUpdateQueue,
            loadPipeline,
            partitioner,
            updateBatchTaskFactory,
            hasThrown = AtomicBoolean(false),
            heartbeatTask = mockk(relaxed = true)
        )
    }

    @BeforeEach
    fun init() {
        coEvery { taskScopeProvider.launch(any()) } returns Unit

        val stream = mockk<DestinationStream>(relaxed = true)
        val streamDescriptor = DestinationStream.Descriptor("namespace", "name")
        every { stream.descriptor } returns streamDescriptor
        coEvery { catalog.streams } returns listOf(stream)
    }

    @Test
    fun `don't start the spill-to-disk task when file transfer is enabled`() = runTest {
        val destinationTaskLauncher = getDefaultDestinationTaskLauncher(true)
        // This is needed to let the run method to complete
        destinationTaskLauncher.handleTeardownComplete()
        destinationTaskLauncher.run()

        coVerify { spillToDiskTaskFactory wasNot Called }
    }

    @Test
    fun `start the spill-to-disk task when file transfer is disabled`() = runTest {
        val spillToDiskTask = mockk<SpillToDiskTask>(relaxed = true)
        coEvery { spillToDiskTaskFactory.make(any(), any()) } returns spillToDiskTask

        val destinationTaskLauncher = getDefaultDestinationTaskLauncher(false)
        // This is needed to let the run method to complete
        destinationTaskLauncher.handleTeardownComplete()
        destinationTaskLauncher.run()

        coVerify { spillToDiskTaskFactory.make(any(), any()) }
    }

    @Test
    fun `handle exception`() = runTest {
        val destinationTaskLauncher = getDefaultDestinationTaskLauncher(true)
        launch { destinationTaskLauncher.run() }
        val e = Exception("e")
        destinationTaskLauncher.handleException(e)
        destinationTaskLauncher.handleTeardownComplete()

        // mock stream manager always returns `false` from `setClosed()`, so we set
        // shouldRunStreamLoaderClose = false.
        coVerify { failStreamTaskFactory.make(any(), e, any(), shouldRunStreamLoaderClose = false) }
        coVerify { taskScopeProvider.launch(match { it is FailStreamTask }) }
    }

    @Test
    fun `run close stream no more than once per stream`() = runTest {
        val destinationTaskLauncher = getDefaultDestinationTaskLauncher(true)
        val streamManager =
            mockk<StreamManager>(relaxed = true) {
                // simulate more realistic behavior.
                // The first call to setClosed returns true; subsequent calls return false.
                every { setClosed() } returnsMany (listOf(true, false))
            }
        coEvery { syncManager.getStreamManager(any()) } returns streamManager
        coEvery { streamManager.isBatchProcessingComplete() } returns true
        val descriptor = DestinationStream.Descriptor("namespace", "name")
        destinationTaskLauncher.handleNewBatch(
            descriptor,
            BatchEnvelope(SimpleBatch(BatchState.COMPLETE), null, descriptor)
        )
        destinationTaskLauncher.handleNewBatch(
            descriptor,
            BatchEnvelope(SimpleBatch(BatchState.COMPLETE), null, descriptor)
        )
        coVerify(exactly = 1) { closeStreamTaskFactory.make(any(), any()) }
    }

    @Test
    fun `successful completion triggers scope close`() = runTest {
        // This should close the scope provider.
        val taskLauncher = getDefaultDestinationTaskLauncher(false)
        launch {
            taskLauncher.run()
            coVerify { taskScopeProvider.close() }
        }
        taskLauncher.handleTeardownComplete()
    }

    @Test
    fun `completion with failure triggers scope kill`() = runTest {
        val taskLauncher = getDefaultDestinationTaskLauncher(false)
        launch {
            taskLauncher.run()
            coVerify { taskScopeProvider.kill() }
        }
        taskLauncher.handleTeardownComplete(success = false)
    }

    @Test
    fun `exceptions in tasks throw`() = runTest {
        coEvery { spillToDiskTaskFactory.make(any(), any()) } answers
            {
                val task = mockk<SpillToDiskTask>(relaxed = true)
                coEvery { task.execute() } throws Exception("spill to disk task failed")
                task
            }
        coEvery { taskScopeProvider.launch(any()) } coAnswers
            {
                val task = firstArg<Task>()
                task.execute()
            }

        val taskLauncher = getDefaultDestinationTaskLauncher(false)
        val job = launch { taskLauncher.run() }
        taskLauncher.handleTeardownComplete()
        job.join()
        coVerify {
            failStreamTaskFactory.make(
                any(),
                any(),
                match { it.namespace == "namespace" && it.name == "name" },
                // mock stream manager always returns `false` from `setClosed()`, so we set
                // shouldRunStreamLoaderClose = false.
                shouldRunStreamLoaderClose = false,
            )
        }
    }

    @Test
    fun `numOpenStreamWorkers open stream tasks are launched`() = runTest {
        val numOpenStreamWorkers = 3
        val destinationTaskLauncher = getDefaultDestinationTaskLauncher(false)

        coEvery { config.numOpenStreamWorkers } returns numOpenStreamWorkers

        val job = launch { destinationTaskLauncher.run() }
        destinationTaskLauncher.handleTeardownComplete()
        job.join()

        coVerify(exactly = numOpenStreamWorkers) { openStreamTaskFactory.make() }

        coVerify(exactly = 0) { openStreamQueue.publish(any()) }
    }

    @Test
    fun `streams are opened when setup completes`() = runTest {
        val launcher = getDefaultDestinationTaskLauncher(false)

        coEvery { openStreamQueue.publish(any()) } returns Unit

        launcher.handleSetupComplete()

        coVerify(exactly = catalog.streams.size) { openStreamQueue.publish(any()) }
    }

    @Test
    fun `don't start the load pipeline if not provided, do start old tasks`() = runTest {
        val launcher = getDefaultDestinationTaskLauncher(false, null as LoadPipeline?)
        coEvery { config.numProcessRecordsWorkers } returns 1
        coEvery { config.numProcessBatchWorkers } returns 1
        val job = assertDoesNotThrow { launch { launcher.run() } }
        launcher.handleTeardownComplete(true)
        job.join()
        coVerify { processRecordsTaskFactory.make(any()) }
        coVerify { processBatchTaskFactory.make(any()) }
        job.cancel()
    }

    @Test
    fun `start the load pipeline if provided`() = runTest {
        val pipeline = mockk<LoadPipeline>(relaxed = true)
        val launcher = getDefaultDestinationTaskLauncher(false, pipeline)
        val job = launch { launcher.run() }
        launcher.handleTeardownComplete(true)
        job.join()
        coVerify { pipeline.start(any()) }
        job.cancel()
    }
}
