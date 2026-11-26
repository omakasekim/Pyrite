package dev.omakasekim.pyrite.vm

/** Kind of control-flow or call edge leaving a basic block. */
enum class CfgEdgeKind {
    FALLTHROUGH,
    JUMP,
    BRANCH_TAKEN,
    BRANCH_FALLTHROUGH,
    CALL,
}

/** A directed edge from a basic block to a target bytecode offset. */
data class CfgEdge(val target: Int, val kind: CfgEdgeKind)

/** A single verified reachable basic block in a [VerifiedProgram]. */
data class BasicBlock(
    val start: Int,
    val end: Int,
    val entryDepth: Int,
    val owner: Int,
    val instructions: List<String>,
    val edges: List<CfgEdge>,
) {
    val id: String get() = Disassembler.label(start)
}

/**
 * Extracts verified basic-block control-flow and call graphs from a [VerifiedProgram] and exports
 * them as deterministic Graphviz DOT, Mermaid flowcharts, or JSON-ready maps.
 */
object CfgExporter {
    fun build(program: VerifiedProgram): List<BasicBlock> {
        val code = program.code
        val reachable = ArrayList<Int>()
        val leaders = LinkedHashSet<Int>()
        val targets = HashSet<Int>()
        var pc = 0
        while (pc < code.size) {
            val op = code[pc]
            val len = Op.length(op)
            if (program.isReachable(pc)) {
                reachable.add(pc)
                if (pc == 0 || program.functions.any { it.entry == pc }) {
                    leaders.add(pc)
                }
                if (Op.isBranch(op)) {
                    val target = Verifier.readU16(code, pc + 1)
                    targets.add(target)
                    leaders.add(target)
                    if (op == Op.JZ || op == Op.JNZ || op == Op.CALL) {
                        val next = pc + len
                        if (next < code.size && program.isReachable(next)) leaders.add(next)
                    }
                } else if (op == Op.HALT || op == Op.RET) {
                    val next = pc + len
                    if (next < code.size && program.isReachable(next)) leaders.add(next)
                }
            }
            pc += len
        }
        val sortedLeaders = leaders.filter { program.isReachable(it) }.sorted()
        val leaderSet = sortedLeaders.toHashSet()
        val blocks = ArrayList<BasicBlock>(sortedLeaders.size)
        for (start in sortedLeaders) {
            val insns = ArrayList<String>()
            val edges = ArrayList<CfgEdge>()
            var cur = start
            var lastEnd = start
            while (cur < code.size && program.isReachable(cur)) {
                if (cur != start && cur in leaderSet) {
                    edges.add(CfgEdge(cur, CfgEdgeKind.FALLTHROUGH))
                    break
                }
                val op = code[cur]
                val len = Op.length(op)
                insns.add(formatInsn(code, cur))
                lastEnd = cur + len
                when {
                    op == Op.JMP -> {
                        edges.add(CfgEdge(Verifier.readU16(code, cur + 1), CfgEdgeKind.JUMP))
                        break
                    }
                    op == Op.JZ || op == Op.JNZ -> {
                        edges.add(CfgEdge(Verifier.readU16(code, cur + 1), CfgEdgeKind.BRANCH_TAKEN))
                        if (lastEnd < code.size && program.isReachable(lastEnd)) {
                            edges.add(CfgEdge(lastEnd, CfgEdgeKind.BRANCH_FALLTHROUGH))
                        }
                        break
                    }
                    op == Op.CALL -> {
                        edges.add(CfgEdge(Verifier.readU16(code, cur + 1), CfgEdgeKind.CALL))
                        if (lastEnd < code.size && program.isReachable(lastEnd)) {
                            edges.add(CfgEdge(lastEnd, CfgEdgeKind.FALLTHROUGH))
                        }
                        break
                    }
                    op == Op.HALT || op == Op.RET -> break
                    else -> cur = lastEnd
                }
            }
            blocks.add(
                BasicBlock(
                    start = start,
                    end = lastEnd,
                    entryDepth = program.stackDepthAt(start),
                    owner = program.ownerOf(start),
                    instructions = insns,
                    edges = edges,
                ),
            )
        }
        return blocks
    }

    fun toDot(program: VerifiedProgram): String {
        val blocks = build(program)
        val byOwner = blocks.groupBy { it.owner }
        val functionsByEntry = program.functions.associateBy { it.entry }
        return buildString {
            appendLine("digraph pyrite_cfg {")
            appendLine("  node [shape=box, fontname=\"monospace\"];")
            for ((owner, ownerBlocks) in byOwner.entries.sortedBy { it.key }) {
                val fn = functionsByEntry[owner]
                val clusterLabel = if (fn == null) {
                    "entry (max stack ${program.maxStack})"
                } else {
                    "fn_${Disassembler.label(fn.entry)} (argc=${fn.argc}, locals=${fn.localCount}, maxStack=${fn.maxStack})"
                }
                appendLine("  subgraph cluster_${Disassembler.label(owner)} {")
                appendLine("    label=\"${escape(clusterLabel)}\";")
                for (block in ownerBlocks) {
                    val header = "${block.id} [depth=${block.entryDepth}]"
                    val body = (listOf(header) + block.instructions).joinToString("\\l") { escape(it) } + "\\l"
                    appendLine("    ${block.id} [label=\"$body\"];")
                }
                appendLine("  }")
            }
            for (block in blocks) {
                for (edge in block.edges) {
                    val target = Disassembler.label(edge.target)
                    val attrs = when (edge.kind) {
                        CfgEdgeKind.FALLTHROUGH -> ""
                        CfgEdgeKind.JUMP -> " [label=\"jmp\"]"
                        CfgEdgeKind.BRANCH_TAKEN -> " [label=\"taken\", color=\"#2563eb\"]"
                        CfgEdgeKind.BRANCH_FALLTHROUGH -> " [label=\"next\", color=\"#64748b\"]"
                        CfgEdgeKind.CALL -> " [label=\"call\", style=\"dashed\", color=\"#9333ea\"]"
                    }
                    appendLine("  ${block.id} -> $target$attrs;")
                }
            }
            appendLine("}")
        }
    }

    fun toMermaid(program: VerifiedProgram): String {
        val blocks = build(program)
        return buildString {
            appendLine("flowchart TD")
            for (block in blocks) {
                val lines = (listOf("${block.id} (depth=${block.entryDepth})") + block.instructions)
                    .joinToString("<br/>") { escape(it) }
                appendLine("  ${block.id}[\"$lines\"]")
            }
            for (block in blocks) {
                for (edge in block.edges) {
                    val target = Disassembler.label(edge.target)
                    when (edge.kind) {
                        CfgEdgeKind.FALLTHROUGH -> appendLine("  ${block.id} --> $target")
                        CfgEdgeKind.JUMP -> appendLine("  ${block.id} -->|jmp| $target")
                        CfgEdgeKind.BRANCH_TAKEN -> appendLine("  ${block.id} -->|taken| $target")
                        CfgEdgeKind.BRANCH_FALLTHROUGH -> appendLine("  ${block.id} -->|next| $target")
                        CfgEdgeKind.CALL -> appendLine("  ${block.id} -.->|call| $target")
                    }
                }
            }
        }
    }

    fun toMap(program: VerifiedProgram): Map<String, Any?> {
        val blocks = build(program)
        return linkedMapOf(
            "ok" to true,
            "codeBytes" to program.code.size,
            "blockCount" to blocks.size,
            "edgeCount" to blocks.sumOf { it.edges.size },
            "maxStack" to program.maxStack,
            "blocks" to blocks.map { b ->
                linkedMapOf(
                    "id" to b.id,
                    "start" to b.start,
                    "end" to b.end,
                    "entryDepth" to b.entryDepth,
                    "owner" to Disassembler.label(b.owner),
                    "instructions" to b.instructions,
                    "edges" to b.edges.map { e ->
                        linkedMapOf(
                            "target" to Disassembler.label(e.target),
                            "offset" to e.target,
                            "kind" to e.kind.name.lowercase(),
                        )
                    },
                )
            },
        )
    }

    private fun formatInsn(code: ByteArray, pc: Int): String {
        val op = code[pc]
        val name = Op.mnemonic(op) ?: error("verified code held opcode $op")
        return when {
            op == Op.CONST16 || op == Op.CONST32 -> "$name ${Verifier.readConst(code, pc)}"
            Op.isBranch(op) -> "$name ${Disassembler.label(Verifier.readU16(code, pc + 1))}"
            op == Op.LOAD || op == Op.STORE || op == Op.ENTER -> "$name ${code[pc + 1].toInt() and 0xFF}"
            else -> name
        }
    }

    private fun escape(text: String): String =
        text.replace("\\", "\\\\").replace("\"", "\\\"")
}
