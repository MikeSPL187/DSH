package com.dsh

/** Модель для новой сессии: id в каталоге Host, провайдер и уровень рассуждений (G-23). */
@kotlinx.serialization.Serializable
data class ModelChoice(val modelId: String, val providerId: String, val reasoningEffort: String? = null)
