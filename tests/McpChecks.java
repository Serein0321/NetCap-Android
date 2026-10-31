package io.github.netcap.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Real loopback transport plus real PCAP/index queries. No Android runtime required. */
public final class McpChecks {
    private static int checks;
    private static final String TOKEN = "0123456789abcdef0123456789abcdef0123456789abcdef";
    private static void require(boolean value, String message) throws Exception { checks++; if (!value) throw new Exception(message); }
    private static JSONObject request(String method, JSONObject params) throws Exception { return new JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", method).put("params", params); }
    private static JSONObject args(String id) throws Exception { return new JSONObject().put("sessionId", id); }
    private static final class Response { int status; JSONObject body; }
    private static Response http(int port, String method, String path, String token, String extra, String body) throws Exception {
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(10000);
            byte[] data = body.getBytes(StandardCharsets.UTF_8);
            String headers = method + " " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\nAccept: application/json, text/event-stream\r\nContent-Type: application/json\r\nContent-Length: " + data.length + "\r\n" + (token == null ? "" : "Authorization: Bearer " + token + "\r\n") + extra + "\r\n";
            s.getOutputStream().write(headers.getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().write(data);
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Response response = new Response(); response.status = Integer.parseInt(in.readLine().split(" ")[1]);
            String line; while (!(line = in.readLine()).isEmpty()) {}
            StringBuilder result = new StringBuilder(); while ((line = in.readLine()) != null) result.append(line);
            if (result.length() > 0) response.body = new JSONObject(result.toString()); return response;
        }
    }
    public static void main(String[] argv) throws Exception {
        File root = new File(argv[0]); root.mkdirs();
        File session = new File(root, "folder/session"); session.mkdirs();
        List<byte[]> packets = new ArrayList<>();
        packets.add(WorkbenchChecks.udp("hello MCP".getBytes(StandardCharsets.UTF_8), false));
        packets.add(WorkbenchChecks.tcp(100, 0x18, "GET / HTTP/1.1".getBytes(StandardCharsets.UTF_8)));
        for (int i = 0; i < 4200; i++) packets.add(WorkbenchChecks.udp(new byte[] {(byte) i}, false));
        PacketStore.importPcap(new ByteArrayInputStream(WorkbenchChecks.pcap(packets)), session, new JSONObject().put("label", "MCP fixture"));
        McpTools tools = new McpTools(new McpTools.Source() {
            public List<File> roots() { return Arrays.asList(root); }
            public JSONObject status() throws Exception { return new JSONObject().put("readOnly", true); }
        });
        JSONArray definitions = McpTools.definitions(); require(definitions.length() == 6, "six tools");
        for (int i = 0; i < definitions.length(); i++) require(definitions.getJSONObject(i).getJSONObject("annotations").getBoolean("readOnlyHint"), "read-only hint");
        JSONObject listing = tools.call("netcap_list_sessions", new JSONObject());
        require(listing.getJSONArray("sessions").length() == 1, "recursive session discovery");
        String id = listing.getJSONArray("sessions").getJSONObject(0).getString("sessionId");
        require(id.matches("[a-f0-9]{32}"), "opaque id");
        require(tools.call("netcap_get_session", args(id)).getLong("packets") == 4202, "index count");
        JSONObject query = tools.call("netcap_query_packets", args(id));
        require(query.getJSONArray("packets").length() == 128 && query.getLong("nextCursor") == 4073, "pagination is bounded");
        JSONObject tcp = tools.call("netcap_query_packets", args(id).put("protocol", 6));
        require(tcp.getJSONArray("packets").length() == 0 && tcp.getLong("scanned") == 4096 && tcp.getLong("nextCursor") == 105, "sparse query scan bound");
        tcp = tools.call("netcap_query_packets", args(id).put("protocol", 6).put("cursor", tcp.getLong("nextCursor")));
        require(tcp.getJSONArray("packets").length() == 1 && tcp.getJSONArray("packets").getJSONObject(0).getLong("row") == 1, "sparse resume");
        require(tools.call("netcap_query_packets", args(id).put("text", "hex:68656c6c6f").put("cursor", 1)).getJSONArray("packets").length() == 1, "hex content query");
        JSONObject packet = tools.call("netcap_get_packet", args(id).put("row", 0));
        require(packet.getString("payloadUtf8Preview").equals("hello MCP"), "payload from PCAP");
        require(packet.getString("source").equals("10.0.0.1"), "real source address");
        JSONObject connections = tools.call("netcap_connections", args(id));
        require(connections.getLong("scanned") == 4096 && connections.getBoolean("partial"), "connection window bound");
        JSONObject next = tools.call("netcap_connections", args(id).put("cursor", 4096));
        require(next.getLong("scanned") == 106 && next.getLong("nextCursor") == -1 && next.getBoolean("partial"), "window continuation");
        String[] invalids = {"../outside", "0123", id + "/.."};
        for (String value : invalids) try { tools.call("netcap_get_session", args(value)); require(false, "reject invalid id"); } catch (IOException expected) { checks++; }
        JSONObject[] badArgs = {args(id).put("row", -1), args(id).put("row", 99999), args(id).put("row", "1"), args(id).put("row", 1.5), args(id).put("row", 0).put("maxBytes", 20000), args(id).put("row", 0).put("arbitraryPath", root.getPath())};
        for (JSONObject a : badArgs) try { tools.call("netcap_get_packet", a); require(false, "reject invalid packet argument"); } catch (IOException expected) { checks++; }
        try (McpServer server = new McpServer(0, false, TOKEN, tools, message -> {})) {
            server.start(); int port = server.port();
            for (String version : McpServer.VERSIONS) {
                Response init = http(port, "POST", "/mcp", TOKEN, "", request("initialize", new JSONObject().put("protocolVersion", version)).toString());
                require(init.status == 200 && init.body.getJSONObject("result").getString("protocolVersion").equals(version), "initialize " + version);
            }
            require(http(port, "POST", "/mcp", null, "", "{}").status == 401, "missing auth");
            require(http(port, "POST", "/mcp", "wrong", "", "{}").status == 401, "invalid auth");
            require(http(port, "POST", "/mcp", TOKEN, "Origin: https://evil.test\r\n", "{}").status == 403, "origin validation");
            require(http(port, "GET", "/mcp", TOKEN, "", "").status == 405, "no standalone SSE");
            require(http(port, "DELETE", "/mcp", TOKEN, "", "").status == 405, "stateless transport");
            require(http(port, "POST", "/wrong", TOKEN, "", "{}").status == 404, "endpoint routing");
            require(http(port, "POST", "/mcp", TOKEN, "MCP-Protocol-Version: 9999\r\n", "{}").status == 400, "version check");
            Response listed = http(port, "POST", "/mcp", TOKEN, "MCP-Protocol-Version: 2025-11-25\r\n", request("tools/list", new JSONObject()).toString());
            require(listed.body.getJSONObject("result").getJSONArray("tools").length() == 6, "tools/list over HTTP");
            require(http(port, "POST", "/mcp", TOKEN, "", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}").status == 202, "notification accepted without body");
            require(http(port, "POST", "/mcp", TOKEN, "", "{").body.getJSONObject("error").getInt("code") == -32700, "parse error");
            require(http(port, "POST", "/mcp", TOKEN, "", "[]").body.getJSONObject("error").getInt("code") == -32600, "no batches");
            require(http(port, "POST", "/mcp", TOKEN, "", "{} {}").body.getJSONObject("error").getInt("code") == -32700, "no trailing input");
            require(http(port, "POST", "/mcp", TOKEN, "", new String(new char[60]).replace('\0', '[') + new String(new char[60]).replace('\0', ']')).body.getJSONObject("error").getInt("code") == -32700, "nesting bound");
            require(http(port, "POST", "/mcp", TOKEN, "", request("no_method", new JSONObject()).toString()).body.getJSONObject("error").getInt("code") == -32601, "unknown method");
            for (int i = 0; i < definitions.length(); i++) {
                String name = definitions.getJSONObject(i).getString("name");
                JSONObject a = name.equals("netcap_status") || name.equals("netcap_list_sessions") ? new JSONObject() : args(id);
                if (name.equals("netcap_get_packet")) a.put("row", 0);
                Response called = http(port, "POST", "/mcp", TOKEN, "", request("tools/call", new JSONObject().put("name", name).put("arguments", a)).toString());
                require(!called.body.getJSONObject("result").getBoolean("isError"), "tool over HTTP " + name);
            }
            Response bad = http(port, "POST", "/mcp", TOKEN, "", request("tools/call", new JSONObject().put("name", "netcap_get_packet").put("arguments", args(id).put("row", -1))).toString());
            require(bad.body.getJSONObject("result").getBoolean("isError"), "tool execution error");
            try (ServerSocket occupied = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                try { new McpServer(occupied.getLocalPort(), false, TOKEN, tools, message -> {}); require(false, "bind occupied port"); } catch (BindException expected) { checks++; }
            }
            require(http(port, "POST", "/mcp", TOKEN, "", new String(new char[65537]).replace('\0', 'x')).status == 413, "request bound");
            server.close();
            try { new Socket("127.0.0.1", port).close(); require(false, "closed listener"); } catch (ConnectException expected) { checks++; }
        }
        require(new File(session, "packets.idx").length() == 4202L * 64, "source index unchanged");
        File empty = new File(root, "empty"); empty.mkdirs(); PacketStore.saveManifest(empty, new JSONObject());
        listing = tools.call("netcap_list_sessions", new JSONObject());
        String emptyId = "";
        for (int i = 0; i < listing.getJSONArray("sessions").length(); i++) { JSONObject e = listing.getJSONArray("sessions").getJSONObject(i); if (e.getLong("packets") == 0) emptyId = e.getString("sessionId"); }
        require(tools.call("netcap_query_packets", args(emptyId)).getJSONArray("packets").length() == 0, "empty session");
        require(tools.call("netcap_connections", args(emptyId)).getJSONArray("connections").length() == 0, "empty connections");
        System.out.println("MCP checks passed: " + checks);
    }
}
