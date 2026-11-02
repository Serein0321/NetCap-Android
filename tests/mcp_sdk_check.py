"""Use the installed official Python MCP SDK against an Android-forwarded endpoint.

The token is read via adb from the isolated debuggable test install, never printed.
"""
import asyncio
import json
import subprocess
import sys
import xml.etree.ElementTree as ET

import httpx
from mcp import ClientSession
from mcp.client.streamable_http import streamable_http_client


async def main():
    adb, serial = sys.argv[1:3]
    subprocess.run([adb, "-s", serial, "forward", "tcp:18765", "tcp:18765"], check=True, capture_output=True)
    prefs = subprocess.check_output([adb, "-s", serial, "shell", "run-as", "io.github.netcap.app", "cat", "shared_prefs/mcp.xml"], text=True)
    token = next(n.text for n in ET.fromstring(prefs) if n.attrib.get("name") == "token")
    async with httpx.AsyncClient(headers={"Authorization": "Bearer " + token}, timeout=15, trust_env=False) as http:
        async with streamable_http_client("http://127.0.0.1:18765/mcp", http_client=http) as (read, write, _):
            async with ClientSession(read, write) as session:
                initialized = await session.initialize()
                assert initialized.serverInfo.name == "NetCap Android"
                listed = await session.list_tools()
                assert len(listed.tools) == 6
                sessions = await session.call_tool("netcap_list_sessions", {})
                assert not sessions.isError
                data = json.loads(sessions.content[0].text)
                sid = data["sessions"][0]["sessionId"]
                for tool in listed.tools:
                    args = {} if tool.name in {"netcap_status", "netcap_list_sessions"} else {"sessionId": sid}
                    if tool.name == "netcap_get_packet":
                        args["row"] = 0
                    result = await session.call_tool(tool.name, args)
                    assert not result.isError, tool.name
                    json.loads(result.content[0].text)
                bad = await session.call_tool("netcap_get_packet", {"sessionId": sid, "row": -1})
                assert bad.isError
                print(f"PASS official Python MCP SDK: initialize {initialized.protocolVersion}, 6 tools, real Android fixture, tool errors")


asyncio.run(main())
