package com.chambersxdu.alphaphoto

internal class CameraConnectionGate {
    enum class BeginResult {
        STARTED,
        ALREADY_CONNECTING,
        ALREADY_READY,
    }

    private var state = State.IDLE

    @Synchronized
    fun begin(): BeginResult = when (state) {
        State.IDLE -> {
            state = State.CONNECTING
            BeginResult.STARTED
        }
        State.CONNECTING -> BeginResult.ALREADY_CONNECTING
        State.READY -> BeginResult.ALREADY_READY
    }

    @Synchronized
    fun ready() {
        state = State.READY
    }

    @Synchronized
    fun failed() {
        state = State.IDLE
    }

    private enum class State {
        IDLE,
        CONNECTING,
        READY,
    }
}
