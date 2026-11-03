"""Exercise SpanDelta through the official MCP Python SDK using the packaged Java app."""
import argparse
import asyncio
import json
import os
from pathlib import Path

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def investigate(distribution: Path, database: Path, baseline: str, candidate: str) -> None:
    java = Path(os.environ["JAVA_HOME"], "bin", "java.exe" if os.name == "nt" else "java")
    server = StdioServerParameters(command=str(java), args=[
        "-cp", str(distribution.resolve() / "lib" / "*"),
        "io.github.almeida96.spandelta.MainKt", "mcp", "--db", str(database.resolve()),
    ])
    async with stdio_client(server) as streams, ClientSession(*streams) as session:
        await session.initialize()
        tools = await session.list_tools()
        assert {tool.name for tool in tools.tools} == {"list_datasets", "compare_releases", "inspect_trace"}
        response = await session.call_tool("compare_releases", {"baseline": baseline, "candidate": candidate, "limit": 5})
        if response.isError:
            raise RuntimeError(response.content[0].text)
        result = response.structuredContent
        exemplar = next((row for row in result["operations"] if row["candidate"]), None)
        output = {"verdict": result["verdict"], "counts": result["counts"], "example": None}
        if exemplar:
            reference = exemplar["candidate"]["p95Example"]
            trace = await session.call_tool("inspect_trace", {"dataset": candidate, "traceId": reference["traceId"]})
            if trace.isError:
                raise RuntimeError(trace.content[0].text)
            evidence = trace.structuredContent
            assert evidence["traceId"] == reference["traceId"]
            output["example"] = {"operation": exemplar["key"], "p95Ms": exemplar["candidate"]["p95Ms"],
                                 "traceId": evidence["traceId"], "spanId": reference["spanId"],
                                 "spanCount": evidence["total"], "warnings": evidence["warnings"]}
        print(json.dumps(output, indent=2))


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("distribution", type=Path)
    parser.add_argument("database", type=Path)
    parser.add_argument("--baseline", default="baseline")
    parser.add_argument("--candidate", default="candidate")
    args = parser.parse_args()
    asyncio.run(investigate(args.distribution, args.database, args.baseline, args.candidate))
