package com.farosos.directchat

/** Un mensaje del canal directo (#61), en el orden en que se escribió. */
data class ChatMessage(val fromVictim: Boolean, val text: String, val sentAtEpochSeconds: Long)
