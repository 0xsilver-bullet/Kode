package com.silverbullet.kode.feature.voice.presentation

import app.cash.turbine.test
import com.silverbullet.kode.core.datastore.VoiceBindingRecord
import com.silverbullet.kode.core.datastore.VoiceBindingStore
import com.silverbullet.kode.core.datastore.VoiceSettings
import com.silverbullet.kode.core.datastore.VoiceSettingsStore
import com.silverbullet.kode.core.model.EnvironmentId
import com.silverbullet.kode.feature.voice.domain.AudioChunk
import com.silverbullet.kode.feature.voice.domain.AudioRecorder
import com.silverbullet.kode.feature.voice.domain.MicPermission
import com.silverbullet.kode.feature.voice.domain.VoiceLiveSession
import com.silverbullet.kode.feature.voice.domain.VoiceServerApi
import com.silverbullet.kode.feature.voice.domain.VoiceServerException
import com.silverbullet.kode.voice.contract.VoiceCompleted
import com.silverbullet.kode.voice.contract.VoicePairResponse
import com.silverbullet.kode.voice.contract.VoiceReady
import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceRefineResponse
import com.silverbullet.kode.voice.contract.VoiceServerDescriptor
import com.silverbullet.kode.voice.contract.VoiceServerMessage
import com.silverbullet.kode.voice.contract.VoiceStart
import com.silverbullet.kode.voice.contract.VoiceTranscript
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VoicePromptViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val environmentId = EnvironmentId("env-1")

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // ------------------------------------------------------------------ fakes

    private class FakeSession : VoiceLiveSession {
        val serverMessages = Channel<VoiceServerMessage>(Channel.UNLIMITED)
        val audioFrames = mutableListOf<ByteArray>()
        var stopped = false
        var aborted = false

        override val events: Flow<VoiceServerMessage> = serverMessages.consumeAsFlow()

        override suspend fun sendAudio(bytes: ByteArray) {
            audioFrames += bytes
        }

        override suspend fun stop() {
            stopped = true
        }

        override suspend fun abort() {
            aborted = true
        }

        override suspend fun close() {
            serverMessages.close()
        }
    }

    private class FakeApi(
        val session: FakeSession,
        var refineResult: () -> VoiceRefineResponse = { VoiceRefineResponse("unused", changed = false) },
    ) : VoiceServerApi {
        var openedWith: VoiceStart? = null
        var refinedWith: VoiceRefineRequest? = null
        var failOpen = false

        override suspend fun fetchDescriptor(baseUrl: String) =
            VoiceServerDescriptor(serverId = "srv", label = "test")

        override suspend fun pair(baseUrl: String, code: String, clientLabel: String, clientOs: String) =
            VoicePairResponse(accessToken = "kv_x", serverId = "srv", label = "test")

        override suspend fun refine(binding: VoiceBindingRecord, request: VoiceRefineRequest): VoiceRefineResponse {
            refinedWith = request
            return refineResult()
        }

        override suspend fun openSession(binding: VoiceBindingRecord, start: VoiceStart): VoiceLiveSession {
            if (failOpen) throw VoiceServerException("connection refused")
            openedWith = start
            return session
        }
    }

    private class FakeBindingStore(records: List<VoiceBindingRecord>) : VoiceBindingStore {
        private val state = MutableStateFlow(records)
        override val bindings: Flow<List<VoiceBindingRecord>> = state

        override suspend fun upsert(record: VoiceBindingRecord) {
            state.value = state.value.filterNot { it.environmentId == record.environmentId } + record
        }

        override suspend fun remove(environmentId: EnvironmentId) {
            state.value = state.value.filterNot { it.environmentId == environmentId }
        }
    }

    private class FakeSettingsStore(refinement: Boolean) : VoiceSettingsStore {
        private val state = MutableStateFlow(VoiceSettings(refinementEnabled = refinement))
        override val settings: Flow<VoiceSettings> = state

        override suspend fun update(transform: (VoiceSettings) -> VoiceSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeRecorder : AudioRecorder {
        override val isAvailable: Boolean = true
        val chunks = Channel<AudioChunk>(Channel.UNLIMITED)
        override fun record(): Flow<AudioChunk> = chunks.consumeAsFlow()
    }

    private val grantedPermission = object : MicPermission {
        override suspend fun ensure(): Boolean = true
    }

    private fun binding() = VoiceBindingRecord(
        environmentId = environmentId,
        serverUrl = "http://voice.test:8484/",
        serverId = "srv",
        label = "test",
        accessToken = "kv_token",
    )

    private fun viewModel(
        api: FakeApi,
        refinement: Boolean = true,
        bindings: List<VoiceBindingRecord> = listOf(binding()),
        recorder: AudioRecorder = FakeRecorder(),
        sendPrompt: suspend (String) -> Result<Unit> = { Result.success(Unit) },
    ) = VoicePromptViewModel(
        environmentId = environmentId,
        projectDir = "/work/kode",
        recentMessages = { emptyList() },
        sendPrompt = sendPrompt,
        bindingStore = FakeBindingStore(bindings),
        settingsStore = FakeSettingsStore(refinement),
        api = api,
        recorder = recorder,
        permission = grantedPermission,
    )

    // ------------------------------------------------------------------ tests

    @Test
    fun happyPathWithRefinementEndsInReview() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session, refineResult = { VoiceRefineResponse("refined text", changed = true) })
        val recorder = FakeRecorder()
        val vm = viewModel(api, recorder = recorder)

        vm.state.map { it::class }.test {
            assertEquals(VoicePromptUiState.Idle::class, awaitItem())
            vm.begin()
            assertEquals(VoicePromptUiState.Connecting::class, awaitItem())

            session.serverMessages.send(VoiceReady(keytermCount = 12))
            assertEquals(VoicePromptUiState.Recording::class, awaitItem())

            recorder.chunks.send(AudioChunk(ByteArray(320), amplitude = 0.5f))
            session.serverMessages.send(VoiceTranscript("hello", isFinal = false))
            assertEquals(VoicePromptUiState.Recording::class, awaitItem())

            session.serverMessages.send(VoiceTranscript("hello world", isFinal = true))
            assertEquals(VoicePromptUiState.Recording::class, awaitItem())

            vm.stopTalking()
            assertEquals(VoicePromptUiState.Finalizing::class, awaitItem())
            dispatcher.scheduler.advanceUntilIdle()
            assertTrue(session.stopped)

            session.serverMessages.send(VoiceCompleted("hello world"))
            assertEquals(VoicePromptUiState.Refining::class, awaitItem())
            assertEquals(VoicePromptUiState.Review::class, awaitItem())
        }

        val review = vm.state.value as VoicePromptUiState.Review
        assertEquals("refined text", review.text)
        assertTrue(review.wasRefined)
        // The start message carried the project directory, and the audio reached the socket.
        assertEquals("/work/kode", api.openedWith?.projectDir)
        assertEquals(1, session.audioFrames.size)
        assertEquals("hello world", api.refinedWith?.transcript)
    }

    @Test
    fun refinementOffGoesStraightToReview() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session)
        val vm = viewModel(api, refinement = false)

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceTranscript("ship it", isFinal = true))
        session.serverMessages.send(VoiceCompleted("ship it"))
        dispatcher.scheduler.advanceUntilIdle()

        val review = assertIs<VoicePromptUiState.Review>(vm.state.value)
        assertEquals("ship it", review.text)
        assertEquals(false, review.wasRefined)
        assertEquals(null, api.refinedWith)
    }

    @Test
    fun refineFailureFallsBackToRawTranscript() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session, refineResult = { throw VoiceServerException("refiner down") })
        val vm = viewModel(api)

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceCompleted("raw words"))
        dispatcher.scheduler.advanceUntilIdle()

        val review = assertIs<VoicePromptUiState.Review>(vm.state.value)
        assertEquals("raw words", review.text)
        assertTrue(review.refineFailed)
    }

    @Test
    fun acceptSendsAndDismisses() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session)
        var sent: String? = null
        val vm = viewModel(api, refinement = false, sendPrompt = { sent = it; Result.success(Unit) })

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceCompleted("do the thing"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.accept()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("do the thing", sent)
        assertEquals(VoicePromptUiState.Dismissed, vm.state.value)
    }

    @Test
    fun failedSendKeepsTheReviewWithAnError() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session)
        val vm = viewModel(
            api,
            refinement = false,
            sendPrompt = { Result.failure(Exception("no route to environment")) },
        )

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceCompleted("do the thing"))
        dispatcher.scheduler.advanceUntilIdle()
        vm.accept()
        dispatcher.scheduler.advanceUntilIdle()

        val review = assertIs<VoicePromptUiState.Review>(vm.state.value)
        assertEquals("no route to environment", review.error)
        assertEquals(false, review.isSending)
    }

    @Test
    fun editKeepsTheDialogAndSendsTheModifiedText() = runTest(dispatcher) {
        val session = FakeSession()
        val api = FakeApi(session)
        var sent: String? = null
        val vm = viewModel(api, refinement = false, sendPrompt = { sent = it; Result.success(Unit) })

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceCompleted("do the thing"))
        dispatcher.scheduler.advanceUntilIdle()

        vm.edit()
        val editing = assertIs<VoicePromptUiState.Editing>(vm.state.value)
        assertEquals("do the thing", editing.draft)

        vm.onDraftChanged("do the thing carefully")
        vm.sendEdited()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals("do the thing carefully", sent)
        assertEquals(VoicePromptUiState.Dismissed, vm.state.value)
    }

    @Test
    fun missingBindingFailsWithGuidance() = runTest(dispatcher) {
        val vm = viewModel(FakeApi(FakeSession()), bindings = emptyList())
        vm.begin()
        dispatcher.scheduler.advanceUntilIdle()

        val failed = assertIs<VoicePromptUiState.Failed>(vm.state.value)
        assertTrue(failed.message.contains("Settings"))
    }

    @Test
    fun emptyTranscriptFailsInsteadOfReviewingNothing() = runTest(dispatcher) {
        val session = FakeSession()
        val vm = viewModel(FakeApi(session))

        vm.begin()
        session.serverMessages.send(VoiceReady())
        session.serverMessages.send(VoiceCompleted("   "))
        dispatcher.scheduler.advanceUntilIdle()

        assertIs<VoicePromptUiState.Failed>(vm.state.value)
    }

    @Test
    fun cancelAbortsTheSession() = runTest(dispatcher) {
        val session = FakeSession()
        val vm = viewModel(FakeApi(session))

        vm.begin()
        session.serverMessages.send(VoiceReady())
        dispatcher.scheduler.advanceUntilIdle()

        vm.cancel()
        dispatcher.scheduler.advanceUntilIdle()

        assertEquals(VoicePromptUiState.Dismissed, vm.state.value)
        assertTrue(session.aborted)

        vm.reset()
        assertEquals(VoicePromptUiState.Idle, vm.state.value)
    }
}
