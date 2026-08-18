package com.silverbullet.kode.voiceserver.refine

import com.silverbullet.kode.voice.contract.VoiceRefineRequest
import com.silverbullet.kode.voice.contract.VoiceRefineResponse

fun interface TranscriptRefiner {
    suspend fun refine(request: VoiceRefineRequest): VoiceRefineResponse
}
