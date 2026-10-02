package com.konprostart.tariffiacode.feature.assistant

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

class TariffiaCodeSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = TariffiaCodeVoiceSession(this)
}
