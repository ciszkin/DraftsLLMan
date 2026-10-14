package by.ciszkin.draftsllman.engine

import by.ciszkin.draftsllman.scene.ResolvedBox

sealed interface ToolResult {
    data class Committed(
        val message: String,
        val touchedNodeId: String? = null,
        val touchedBox: ResolvedBox? = null,
    ) : ToolResult

    data class Query(val payload: Any) : ToolResult

    data class Critique(val result: CritiqueResult) : ToolResult

    data class Rejected(val errors: List<String>) : ToolResult
    data class SolverFailure(val message: String) : ToolResult
    data class Failure(val message: String) : ToolResult
}

data class CritiqueResult(
    val globalPngB64: String,
    val roiPngB64: String?,
    val boxUsed: ResolvedBox?,
)
