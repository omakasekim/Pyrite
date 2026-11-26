package dev.omakasekim.pyrite.vm

import dev.omakasekim.pyrite.Cli
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CfgExporterTest {
    @Test
    fun buildsBasicBlocksAndExportsDotMermaidAndJsonForBranchingAndCalls() {
        val source = """
            push 5
            call double
            dup
            push 0
            lt
            jz done
            neg
        done:
            halt
        double:
            enter 1
            load 0
            dup
            add
            ret
        """
        val program = Verifier.verify(Assembler.assemble(source))
        val blocks = CfgExporter.build(program)
        assertTrue(blocks.size >= 4, "expected entry, branch, merge, and callee blocks: $blocks")
        assertTrue(blocks.any { b -> b.edges.any { it.kind == CfgEdgeKind.CALL } })
        assertTrue(blocks.any { b -> b.edges.any { it.kind == CfgEdgeKind.BRANCH_TAKEN } })

        val dot = CfgExporter.toDot(program)
        assertTrue(dot.startsWith("digraph pyrite_cfg {"), dot)
        assertTrue("style=\"dashed\"" in dot && "label=\"call\"" in dot, dot)
        assertTrue("subgraph cluster_" in dot, dot)

        val mermaid = CfgExporter.toMermaid(program)
        assertTrue(mermaid.startsWith("flowchart TD"), mermaid)
        assertTrue("-.->|call|" in mermaid, mermaid)
        assertTrue("-->|taken|" in mermaid, mermaid)

        val map = CfgExporter.toMap(program)
        assertEquals(true, map["ok"])
        assertEquals(blocks.size, map["blockCount"])
    }

    @Test
    fun cliCfgSupportsDotMermaidJsonAndOptimize() {
        val dir = Files.createTempDirectory("pyrite-cfg").toFile().apply { deleteOnExit() }
        val file = File(dir, "prog.asm")
        file.writeText("push 6\ndup\nmul\nhalt\n")

        fun runCli(vararg args: String): Pair<Int, String> {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val status = Cli(ByteArrayInputStream(ByteArray(0)), PrintStream(out), PrintStream(err)).run(args.toList().toTypedArray())
            return status to out.toString(Charsets.UTF_8).replace("\r\n", "\n")
        }

        val (dotCode, dotOut) = runCli("cfg", file.path, "--optimize")
        assertEquals(0, dotCode)
        assertTrue("push 36" in dotOut && "digraph pyrite_cfg" in dotOut, dotOut)

        val (mCode, mOut) = runCli("cfg", file.path, "--format", "mermaid")
        assertEquals(0, mCode)
        assertTrue(mOut.startsWith("flowchart TD"), mOut)

        val (jCode, jOut) = runCli("cfg", file.path, "--json")
        assertEquals(0, jCode)
        assertTrue("\"ok\":true" in jOut && "\"blockCount\":1" in jOut, jOut)

        val (badCode, _) = runCli("cfg", file.path, "--format", "svg")
        assertEquals(2, badCode)
    }
}
