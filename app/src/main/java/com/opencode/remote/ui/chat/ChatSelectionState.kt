package com.opencode.remote.ui.chat

import com.opencode.remote.data.api.dto.AgentInfo

data class ModelSelectionRef(
    val providerId: String,
    val modelId: String,
)

data class ModelSelectionOption(
    val ref: ModelSelectionRef,
    val providerName: String,
    val modelName: String,
    val variants: List<String> = emptyList(),
) {
    val displayLabel: String
        get() = "$providerName / $modelName"
}

data class ChatSelectionConfig(
    val agent: String? = null,
    val model: ModelSelectionRef? = null,
    val variant: String? = null,
)

data class ChatSelectionUiState(
    val isDialogOpen: Boolean = false,
    val availableAgents: List<AgentInfo> = emptyList(),
    val availableModels: List<ModelSelectionOption> = emptyList(),
    val committed: ChatSelectionConfig = ChatSelectionConfig(),
    val draft: ChatSelectionConfig = ChatSelectionConfig(),
) {
    fun resolveModel(ref: ModelSelectionRef?): ModelSelectionOption? =
        ref?.let { target ->
            availableModels.firstOrNull { option ->
                option.ref.providerId == target.providerId && option.ref.modelId == target.modelId
            }
        }

    val draftVariants: List<String>
        get() = resolveModel(draft.model)?.variants.orEmpty()

    val hasExplicitOverrides: Boolean
        get() = committed.agent != null || committed.model != null || committed.variant != null
}
