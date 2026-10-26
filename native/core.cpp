// 原生核心只负责采集和可靠写盘；Android 界面不跨线程持有这里的缓冲。
#include <arpa/inet.h>
#include <dirent.h>
#include <fcntl.h>
#include <linux/if_packet.h>
#include <net/ethernet.h>
#include <net/if.h>
#include <poll.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <sys/time.h>
#include <sys/vfs.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <fstream>
#include <iomanip>
#include <iostream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

static std::atomic<bool> running{true};
static std::string esc(const std::string& s) {  // 为什么：进程名和错误必须作为数据进入 JSON。
    std::string o;
    for (unsigned char c : s) {
        if (c == '"' || c == '\\') {
            o += '\\';
            o += c;
        } else if (c >= 32)
            o += c;
    }
    return o;
}
static void event(const std::string& type, const std::string& message) {
    printf("{\"event\":\"%s\",\"message\":\"%s\"}\n", type.c_str(), esc(message).c_str());
    fflush(stdout);
}
static uint16_t be16(const uint8_t* p) { return (uint16_t(p[0]) << 8) | p[1]; }
static void le(uint8_t* p, uint64_t v, int n) {
    for (int i = 0; i < n; i++) p[i] = uint8_t(v >> (8 * i));
}
static uint64_t millis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}
static void signal_stop(int) { running = false; }

struct View {
    int version = 0, proto = 0, sp = 0, dp = 0, payload = 0, length = 0;
    bool fragmented = false, valid = false, transport_valid = false;
    std::string src, dst;
};
static View parse(const uint8_t* p, size_t n) {  // 为什么：原包保存与能否解码分开，所有偏移先验证。
    View v;
    char a[INET6_ADDRSTRLEN] = {}, b[INET6_ADDRSTRLEN] = {};
    if (n < 1) return v;
    v.version = p[0] >> 4;
    size_t off = 0, end = n;
    if (v.version == 4) {
        if (n < 20) return v;
        off = (p[0] & 15) * 4;
        size_t total = be16(p + 2);
        if (off < 20 || off > n || total < off) return v;
        end = std::min(n, total);
        inet_ntop(AF_INET, p + 12, a, sizeof(a));
        inet_ntop(AF_INET, p + 16, b, sizeof(b));
        v.proto = p[9];
        v.fragmented = (be16(p + 6) & 0x3fff) != 0;
    } else if (v.version == 6) {
        if (n < 40) return v;
        off = 40;
        size_t len = be16(p + 4);
        if (len) end = std::min(n, len + 40);
        inet_ntop(AF_INET6, p + 8, a, sizeof(a));
        inet_ntop(AF_INET6, p + 24, b, sizeof(b));
        v.proto = p[6];
        for (int i = 0; i < 12; i++) {
            if (v.proto == 44) {
                if (off + 8 > end) return v;
                v.fragmented = (be16(p + off + 2) & 0xfff9) != 0;
                v.proto = p[off];
                off += 8;
                if (v.fragmented) break;
            } else if (v.proto == 0 || v.proto == 43 || v.proto == 60) {
                if (off + 2 > end) return v;
                size_t len2 = (p[off + 1] + 1u) * 8u;
                if (off + len2 > end) return v;
                v.proto = p[off];
                off += len2;
            } else if (v.proto == 51) {
                if (off + 2 > end) return v;
                size_t len2 = (p[off + 1] + 2u) * 4u;
                if (off + len2 > end) return v;
                v.proto = p[off];
                off += len2;
            } else
                break;
        }
    } else
        return v;
    v.src = a;
    v.dst = b;
    v.valid = true;
    v.payload = int(off);
    v.length = int(end - off);
    if (v.fragmented) return v;
    if (v.proto == 6) {
        if (off + 20 > end) return v;
        size_t h = (p[off + 12] >> 4) * 4u;
        if (h < 20 || off + h > end) return v;
        v.sp = be16(p + off);
        v.dp = be16(p + off + 2);
        v.payload = int(off + h);
        v.length = int(end - off - h);
        v.transport_valid = true;
    } else if (v.proto == 17) {
        if (off + 8 > end) return v;
        size_t u = be16(p + off + 4);
        if (u < 8) return v;
        v.sp = be16(p + off);
        v.dp = be16(p + off + 2);
        v.payload = int(off + 8);
        v.length = int(std::min(end - off, u) - 8);
        v.transport_valid = u <= end - off;
    }
    return v;
}
struct Packet {
    std::vector<uint8_t> bytes;
    View view;
    uint64_t sec = 0, queued = 0;
    uint32_t us = 0, wire = 0, iface = 0;
    int uid = -1;
    bool outgoing = false, unknown = false;
    uint64_t decided = 0, table_age = 0, decision_epoch = 0;
    std::string evidence = "not-requested", reason, iface_name;
};
struct Counters {
    std::atomic<uint64_t> received{0}, accepted{0}, written{0}, bytes{0}, kernel{0}, queue{0},
        filtered{0}, unresolved{0}, truncated{0}, up{0}, down{0}, protocol_filtered{0},
        other_uid{0}, unknown_written{0}, unknown_bytes{0}, parse_failed{0}, pending_full{0},
        expired{0}, ambiguous{0}, kernel_samples{0}, kernel_errors{0};
} counts;
static bool retain_unknown = false;
static bool kernel_stats_available = false;
static bool kernel_timestamp = false;
static std::mutex queue_mu, error_mu;
static std::condition_variable queue_cv;
static std::deque<Packet> packets;
static size_t queue_bytes = 0;
static bool producer_done = false;
static std::string write_error;
static void fail(
    const std::string& s) {  // 为什么：写盘失败必须终止采集并传回原因，不能继续显示成功。
    {
        std::lock_guard<std::mutex> l(error_mu);
        if (write_error.empty()) write_error = s;
    }
    running = false;
}
static std::string failure() {
    std::lock_guard<std::mutex> l(error_mu);
    return write_error;
}
static bool put(Packet&& p) {
    std::lock_guard<std::mutex> l(queue_mu);
    if (queue_bytes + p.bytes.size() > 16u * 1024u * 1024u) {
        counts.queue++;
        return false;
    }
    queue_bytes += p.bytes.size();
    packets.push_back(std::move(p));
    counts.accepted++;
    queue_cv.notify_one();
    return true;
}
static bool checked_write(FILE* f, const void* p, size_t n) {
    if (fwrite(p, 1, n, f) != n) {
        fail(std::string("写入失败: ") + strerror(errno));
        return false;
    }
    return true;
}
static bool flush(FILE* f, bool durable) {
    if (fflush(f) != 0 || (durable && fsync(fileno(f)) != 0)) {
        fail(std::string("刷新文件失败: ") + strerror(errno));
        return false;
    }
    return true;
}
static FILE* file_owned(const std::string& path, int owner) {
    int fd = open(path.c_str(), O_CREAT | O_EXCL | O_WRONLY | O_CLOEXEC, 0600);
    if (fd < 0) {
        fail(std::string("创建文件失败: ") + strerror(errno));
        return nullptr;
    }
    if (fchown(fd, owner, owner) != 0) {
        // 为什么：共享存储的 FUSE/sdcardfs 由系统合成所有者，不能等同普通文件系统强行 chown。
        struct statfs fs{};
        if (fstatfs(fd, &fs) != 0 ||
            (uint64_t(fs.f_type) != 0x65735546u && uint64_t(fs.f_type) != 0x5dca2df5u)) {
            fail(std::string("设置文件所有者失败: ") + strerror(errno));
            close(fd);
            return nullptr;
        }
    }
    FILE* f = fdopen(fd, "wb");
    if (!f) {
        close(fd);
        fail("打开输出流失败");
    }
    return f;
}
struct Sink {
    FILE *pcap = nullptr, *idx = nullptr, *audit = nullptr;
    std::string dir;
    int owner;
    uint32_t segment = 0;
    uint64_t offset = 24, seq = 0, last_flush = millis();
    std::vector<uint8_t> batch;
    Sink(const std::string& path, int uid) : dir(path), owner(uid) {}
    bool publish(bool durable) {
        if (!pcap || !flush(pcap, durable)) return false;
        if (audit && !flush(audit, durable)) return false;
        if (!batch.empty()) {
            if (!checked_write(idx, batch.data(), batch.size())) return false;
            batch.clear();
        }
        return flush(idx, durable);
    }
    bool next_segment() {
        if (pcap) {
            if (!publish(true)) return false;
            if (fclose(pcap) != 0) fail("关闭 PCAP 失败");
            pcap = nullptr;
        }
        char name[64];
        snprintf(name, sizeof(name), "/capture_%04u.pcap", ++segment);
        pcap = file_owned(dir + name, owner);
        if (!pcap) return false;
        uint8_t h[24] = {};
        le(h, 0xa1b2c3d4, 4);
        le(h + 4, 2, 2);
        le(h + 6, 4, 2);
        le(h + 16, 262144, 4);
        le(h + 20, 101, 4);
        offset = 24;
        return checked_write(pcap, h, 24);
    }
    bool open() {
        idx = file_owned(dir + "/packets.idx", owner);
        audit = file_owned(dir + "/attribution.jsonl", owner);
        return idx && audit && next_segment();
    }
    bool append(const Packet& p) {
        if (offset + 16 + p.bytes.size() > 64u * 1024u * 1024u && !next_segment()) return false;
        uint8_t hdr[16], row[64] = {};
        le(hdr, p.sec, 4);
        le(hdr + 4, p.us, 4);
        le(hdr + 8, p.bytes.size(), 4);
        le(hdr + 12, p.wire, 4);
        le(row, ++seq, 8);
        le(row + 8, p.sec, 8);
        le(row + 16, p.us, 4);
        le(row + 20, segment, 4);
        le(row + 24, offset + 16, 8);
        le(row + 32, p.bytes.size(), 4);
        le(row + 36, p.wire, 4);
        le(row + 40, p.iface, 4);
        le(row + 44, p.view.payload, 4);
        le(row + 48, p.view.length, 4);
        le(row + 52, uint32_t(p.uid), 4);
        le(row + 56, p.view.sp, 2);
        le(row + 58, p.view.dp, 2);
        row[60] = p.view.proto;
        row[61] = p.outgoing ? 1 : 0;
        row[62] = p.view.version;
        bool parse_failed = !p.view.valid || ((p.view.proto == 6 || p.view.proto == 17) &&
                                              !p.view.fragmented && !p.view.transport_valid);
        row[63] = (p.view.fragmented ? 1 : 0) | (p.wire > p.bytes.size() ? 2 : 0) |
                  (parse_failed ? 4 : 0) | (p.unknown ? 8 : 0);
        if (!checked_write(pcap, hdr, 16) || !checked_write(pcap, p.bytes.data(), p.bytes.size()))
            return false;
        uint64_t delay = p.decided >= p.queued ? p.decided - p.queued : 0;
        char name[IF_NAMESIZE] = {};
        if_indextoname(p.iface, name);
        std::string interface_name = p.iface_name.empty() ? name : p.iface_name;
        char data[1024];
        int n = snprintf(
            data, sizeof(data),
            "{\"id\":%llu,\"packetSeconds\":%llu,\"packetMicros\":%u,\"decisionEpochMillis\":%llu,"
            "\"waitMillis\":%llu,\"snapshotAgeMillis\":%llu,\"uid\":%d,\"classification\":\"%s\","
            "\"evidence\":\"%s\",\"reason\":\"%s\",\"interface\":%u,\"interfaceName\":\"%s\","
            "\"direction\":\"%s\"}\n",
            (unsigned long long)seq, (unsigned long long)p.sec, p.us,
            (unsigned long long)(p.decision_epoch ? p.decision_epoch
                                                  : p.sec * 1000 + p.us / 1000 + delay),
            (unsigned long long)delay, (unsigned long long)p.table_age, p.uid,
            p.unknown ? "unknown" : "selected", p.evidence.c_str(), p.reason.c_str(), p.iface,
            esc(interface_name).c_str(), p.outgoing ? "outgoing" : "incoming");
        if (n < 0 || size_t(n) >= sizeof(data) || !checked_write(audit, data, n)) return false;
        batch.insert(batch.end(), row, row + 64);
        offset += 16 + p.bytes.size();
        if (p.unknown) {
            counts.unknown_written++;
            counts.unknown_bytes += p.bytes.size();
        } else {
            counts.written++;
            counts.bytes += p.bytes.size();
            if (p.outgoing)
                counts.up++;
            else
                counts.down++;
        }
        if (parse_failed) counts.parse_failed++;
        return true;
    }
    void finish() {
        if (pcap) {
            publish(true);
            if (fclose(pcap) != 0) fail("关闭 PCAP 失败");
            pcap = nullptr;
        }
        if (audit) {
            if (fclose(audit) != 0) fail("关闭归属记录失败");
            audit = nullptr;
        }
        if (idx) {
            flush(idx, true);
            if (fclose(idx) != 0) fail("关闭索引失败");
            idx = nullptr;
        }
    }
    ~Sink() { finish(); }
};
static void writer(std::string dir, int owner) {
    Sink primary(dir, owner), unknown(dir + "/unattributed", owner);
    if (!primary.open() || (retain_unknown && !unknown.open())) return;
    for (;;) {
        Packet p;
        bool has = false;
        {
            std::unique_lock<std::mutex> l(queue_mu);
            queue_cv.wait_for(l, std::chrono::milliseconds(200),
                              [] { return !packets.empty() || producer_done; });
            if (!packets.empty()) {
                p = std::move(packets.front());
                packets.pop_front();
                queue_bytes -= p.bytes.size();
                has = true;
            } else if (producer_done)
                break;
        }
        if (!failure().empty()) break;
        if (has && !(p.unknown ? unknown : primary).append(p)) break;
        for (Sink* sink : {&primary, &unknown}) {
            if (sink->pcap &&
                (millis() - sink->last_flush > 200 || sink->batch.size() >= 64 * 256)) {
                if (!sink->publish(false)) break;
                sink->last_flush = millis();
            }
        }
    }
    primary.finish();
    unknown.finish();
}

static std::string proc_addr(const std::string& text, bool ipv6, int& port) {
    size_t c = text.find(':');
    if (c == std::string::npos) return "";
    port = strtoul(text.c_str() + c + 1, nullptr, 16);
    uint8_t b[16] = {};
    if (c != (ipv6 ? 32u : 8u)) return "";
    for (size_t i = 0; i < c / 8; i++) {
        uint32_t w = strtoul(text.substr(i * 8, 8).c_str(), nullptr, 16);
        for (int j = 0; j < 4; j++) b[i * 4 + j] = w >> (8 * j);
    }
    char a[INET6_ADDRSTRLEN];
    inet_ntop(ipv6 ? AF_INET6 : AF_INET, b, a, sizeof(a));
    return a;
}
static std::string key(int proto, const std::string& a, int ap, const std::string& b, int bp) {
    return std::to_string(proto) + "|" + a + "|" + std::to_string(ap) + "|" + b + "|" +
           std::to_string(bp);
}
using Sockets = std::map<std::string, int>;
static Sockets sockets() {  // 为什么：按协议和两端地址关联，避免仅端口相同就认成目标应用。
    Sockets out;
    for (bool ipv6 : {false, true})
        for (int proto : {6, 17}) {
            std::ifstream f(std::string("/proc/net/") + (proto == 6 ? "tcp" : "udp") +
                            (ipv6 ? "6" : ""));
            std::string line;
            std::getline(f, line);
            while (std::getline(f, line)) {
                std::istringstream ss(line);
                std::vector<std::string> t;
                std::string x;
                while (ss >> x) t.push_back(x);
                if (t.size() < 10) continue;
                int ap = 0, bp = 0;
                std::string a = proc_addr(t[1], ipv6, ap), b = proc_addr(t[2], ipv6, bp);
                if (a.empty() || b.empty()) continue;
                int uid = atoi(t[7].c_str());
                if (a.rfind("::ffff:", 0) == 0) a = a.substr(7);
                if (b.rfind("::ffff:", 0) == 0) b = b.substr(7);
                if (a == "0.0.0.0" || a == "::") a = "*";
                if (b == "0.0.0.0" || b == "::") b = "*";
                std::string k = key(proto, a, ap, b, bp);
                auto it = out.find(k);
                if (it != out.end() && it->second != uid)
                    it->second = -2;
                else
                    out[k] = uid;
            }
        }
    return out;
}
static int owner_of(Packet& p, const Sockets& table) {
    if (p.view.fragmented || p.view.sp == 0) {
        p.evidence = p.view.fragmented ? "fragment" : "no-transport-tuple";
        return -1;
    }
    const auto& v = p.view;
    std::string local = p.outgoing ? v.src : v.dst, remote = p.outgoing ? v.dst : v.src;
    int lp = p.outgoing ? v.sp : v.dp, rp = p.outgoing ? v.dp : v.sp;
    int match = 0;
    for (const auto& k : {key(v.proto, local, lp, remote, rp), key(v.proto, "*", lp, remote, rp),
                          key(v.proto, local, lp, "*", 0), key(v.proto, "*", lp, "*", 0)}) {
        auto it = table.find(k);
        if (it != table.end()) {
            p.evidence = it->second == -2 ? "ambiguous-uid"
                         : match == 0     ? "exact-socket-tuple"
                                          : "wildcard-socket-tuple";
            return it->second;
        }
        match++;
    }
    p.evidence = "socket-not-found";
    return -1;
}
static void stats(bool final) {
    std::ostringstream s;
    s << "{\"event\":\"" << (final ? "stopped" : "stats") << "\"";
    auto n = [&](const char* k, uint64_t v) { s << ",\"" << k << "\":" << v; };
    n("kernelStatsSamples", counts.kernel_samples);
    n("kernelStatsFailures", counts.kernel_errors);
    uint64_t accepted = counts.accepted.load(),
             written = counts.written.load() + counts.unknown_written.load();
    n("unsavedAccepted", accepted > written ? accepted - written : 0);
    n("writeFailure", failure().empty() ? 0 : 1);
    n("received", counts.received);
    n("accepted", counts.accepted);
    n("written", counts.written);
    n("bytes", counts.bytes);
    n("kernelDrops", counts.kernel);
    n("queueDrops", counts.queue);
    n("filtered", counts.filtered);
    n("protocolFiltered", counts.protocol_filtered);
    n("otherUidFiltered", counts.other_uid);
    n("unresolved", counts.unresolved);
    n("unknownWritten", counts.unknown_written);
    n("unknownBytes", counts.unknown_bytes);
    n("parseFailedSaved", counts.parse_failed);
    n("pendingLimit", counts.pending_full);
    n("pendingExpired", counts.expired);
    n("ambiguousDecisions", counts.ambiguous);
    n("truncated", counts.truncated);
    n("up", counts.up);
    n("down", counts.down);
    s << ",\"kernelStatsAvailable\":" << (kernel_stats_available ? "true" : "false")
      << ",\"timestampSource\":\"" << (kernel_timestamp ? "kernel" : "userspace")
      << "\",\"error\":\"" << esc(failure()) << "\"}";
    puts(s.str().c_str());
    fflush(stdout);
}
static int capture(const std::string& dir, int owner, int target, int protocol, int iface,
                   bool keep_unknown) {
    retain_unknown = keep_unknown && target >= 0;
    int fd = socket(AF_PACKET, SOCK_DGRAM | SOCK_CLOEXEC, htons(ETH_P_ALL));
    if (fd < 0) {
        event("error", std::string("无法创建抓包 socket: ") + strerror(errno));
        return 2;
    }
    if (iface > 0) {
        sockaddr_ll bindaddr{};
        bindaddr.sll_family = AF_PACKET;
        bindaddr.sll_protocol = htons(ETH_P_ALL);
        bindaddr.sll_ifindex = iface;
        if (bind(fd, (sockaddr*)&bindaddr, sizeof(bindaddr)) != 0) {
            event("error", "无法绑定接口");
            close(fd);
            return 2;
        }
    }
    int rcv = 8 * 1024 * 1024;
    setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &rcv, sizeof(rcv));
    int enabled = 1;
    bool timestamp = setsockopt(fd, SOL_SOCKET, SO_TIMESTAMPNS, &enabled, sizeof(enabled)) == 0;
    kernel_timestamp = timestamp;
    try {
        std::thread disk([&] {
            try {
                writer(dir, owner);
            } catch (const std::exception& e) {
                fail(std::string("写盘线程异常: ") + e.what());
            }
        });
        // 为什么：任何接收/分配异常都会先结束生产并 join，不能让 joinable 线程析构终止进程。
        struct JoinWriter {
            std::thread& thread;
            ~JoinWriter() {
                if (thread.joinable()) {
                    running = false;
                    {
                        std::lock_guard<std::mutex> lock(queue_mu);
                        producer_done = true;
                    }
                    queue_cv.notify_all();
                    thread.join();
                }
            }
        } join_writer{disk};
        event("ready",
              target < 0 ? "整机 IP 抓包已启动" : "应用关联模式已启动；未知归属不会假称精确匹配");
        std::vector<uint8_t> buffer(262144);
        std::deque<Packet> pending;
        size_t pending_bytes = 0;
        auto resolve = [&](Packet& p, int who, const char* cause, uint64_t snapshot) {
            p.decided = millis();
            timeval decision_time{};
            gettimeofday(&decision_time, nullptr);
            p.decision_epoch = uint64_t(decision_time.tv_sec) * 1000 + decision_time.tv_usec / 1000;
            p.table_age = snapshot ? p.decided - snapshot : 0;
            if (who == target) {
                p.uid = who;
                put(std::move(p));
            } else if (who >= 0) {
                counts.filtered++;
                counts.other_uid++;
            } else {
                if (p.evidence == "ambiguous-uid") counts.ambiguous++;
                p.reason = p.view.fragmented ? "fragment-no-owner"
                           : p.view.sp == 0  ? "no-transport-tuple"
                                             : cause;
                if (retain_unknown) {
                    p.unknown = true;
                    p.uid = -1;
                    put(std::move(p));
                } else
                    counts.unresolved++;
            }
        };
        Sockets table;
        std::map<uint32_t, std::pair<std::string, uint64_t>> interface_names;
        uint64_t refreshed = 0, last_stats = 0, last_space = 0;
        while (running) {
            const uint64_t now = millis();
            bool refreshed_now = false;
            if (target >= 0 && now - refreshed >= 100) {
                table = sockets();
                refreshed = now;
                refreshed_now = true;
            }
            if (refreshed_now)
                for (auto it = pending.begin(); it != pending.end();) {
                    int who = owner_of(*it, table);
                    bool expired = now - it->queued > 2000;
                    if (who >= 0 || expired) {
                        pending_bytes -= it->bytes.size();
                        if (who < 0) counts.expired++;
                        resolve(*it, who, "timeout", refreshed);
                        it = pending.erase(it);
                    } else
                        ++it;
                }
            if (now - last_space > 1000) {
                struct statvfs s{};
                if (statvfs(dir.c_str(), &s) != 0)
                    fail("无法读取存储可用空间");
                else if (uint64_t(s.f_bavail) * s.f_frsize < 64u * 1024u * 1024u)
                    fail("存储空间不足，已停止并保留现有记录");
                last_space = now;
            }
            if (now - last_stats > 500) {
                tpacket_stats st{};
                socklen_t len = sizeof(st);
                if (getsockopt(fd, SOL_PACKET, PACKET_STATISTICS, &st, &len) == 0) {
                    counts.kernel += st.tp_drops;
                    kernel_stats_available = true;
                    counts.kernel_samples++;
                } else
                    counts.kernel_errors++;
                stats(false);
                last_stats = now;
            }
            pollfd fds[2] = {{fd, POLLIN, 0}, {STDIN_FILENO, POLLIN | POLLHUP, 0}};
            int rc = poll(fds, 2, 100);
            if (rc < 0) {
                if (errno == EINTR) continue;
                fail("等待收包失败");
                break;
            }
            if (fds[1].revents & (POLLIN | POLLHUP | POLLERR)) {
                char cmd[128];
                ssize_t n = read(STDIN_FILENO, cmd, sizeof(cmd));
                if (n <= 0 || memchr(cmd, '\n', n)) {
                    running = false;
                    break;
                }
            }
            if (!(fds[0].revents & POLLIN)) continue;
            sockaddr_ll from{};
            char control[CMSG_SPACE(sizeof(timespec))];
            iovec io{buffer.data(), buffer.size()};
            msghdr msg{};
            msg.msg_name = &from;
            msg.msg_namelen = sizeof(from);
            msg.msg_iov = &io;
            msg.msg_iovlen = 1;
            msg.msg_control = control;
            msg.msg_controllen = sizeof(control);
            ssize_t n = recvmsg(fd, &msg, MSG_DONTWAIT | MSG_TRUNC);
            if (n < 0) {
                if (errno == EAGAIN || errno == EINTR) continue;
                fail(std::string("收包失败: ") + strerror(errno));
                break;
            }
            if (from.sll_protocol != htons(ETH_P_IP) && from.sll_protocol != htons(ETH_P_IPV6))
                continue;
            if (n == 0) continue;
            counts.received++;
            size_t caplen = std::min(size_t(n), buffer.size());
            Packet p;
            p.bytes.assign(buffer.begin(), buffer.begin() + caplen);
            p.wire = n;
            p.iface = from.sll_ifindex;
            auto& cached_name = interface_names[p.iface];
            if (cached_name.first.empty() || millis() - cached_name.second > 1000) {
                char name[IF_NAMESIZE] = {};
                if_indextoname(from.sll_ifindex, name);
                cached_name = {name, millis()};
            }
            p.iface_name = cached_name.first;
            p.outgoing = from.sll_pkttype == PACKET_OUTGOING;
            p.view = parse(buffer.data(), caplen);
            p.queued = millis();
            timeval tv{};
            gettimeofday(&tv, nullptr);
            p.sec = tv.tv_sec;
            p.us = tv.tv_usec;
            if (timestamp)
                for (cmsghdr* c = CMSG_FIRSTHDR(&msg); c; c = CMSG_NXTHDR(&msg, c))
                    if (c->cmsg_level == SOL_SOCKET && c->cmsg_type == SCM_TIMESTAMPNS &&
                        c->cmsg_len >= CMSG_LEN(sizeof(timespec))) {
                        timespec ts;
                        memcpy(&ts, CMSG_DATA(c), sizeof(ts));
                        p.sec = ts.tv_sec;
                        p.us = ts.tv_nsec / 1000;
                    }
            if (size_t(n) > caplen) counts.truncated++;
            if (protocol && p.view.proto != protocol) {
                counts.filtered++;
                counts.protocol_filtered++;
                continue;
            }
            if (target < 0) {
                p.decided = millis();
                put(std::move(p));
                continue;
            }
            int who = owner_of(p, table);
            if (who >= 0)
                resolve(p, who, "immediate", refreshed);
            else if (pending_bytes + p.bytes.size() <= 8u * 1024u * 1024u) {
                pending_bytes += p.bytes.size();
                pending.push_back(std::move(p));
            } else {
                counts.pending_full++;
                resolve(p, who, "pending-limit", refreshed);
            }
        }
        if (target >= 0) {
            table = sockets();
            refreshed = millis();
            for (auto& p : pending) {
                int who = owner_of(p, table);
                resolve(p, who, "capture-stopped", refreshed);
            }
        }
        tpacket_stats st{};
        socklen_t len = sizeof(st);
        if (getsockopt(fd, SOL_PACKET, PACKET_STATISTICS, &st, &len) == 0) {
            counts.kernel += st.tp_drops;
            kernel_stats_available = true;
            counts.kernel_samples++;
        } else
            counts.kernel_errors++;
        close(fd);
        {
            std::lock_guard<std::mutex> l(queue_mu);
            producer_done = true;
        }
        queue_cv.notify_all();
        disk.join();
        stats(true);
        return failure().empty() ? 0 : 3;
    } catch (const std::exception& e) {
        event("error", e.what());
        close(fd);
        return 3;
    }
}
static void processes(bool include_system) {  // 为什么：真实 PID/UID/RSS/启动时刻由 Root
                                              // 快照提供，标签和图标交由 Android 获取。
    DIR* d = opendir("/proc");
    if (!d) {
        event("error", "无法读取 /proc");
        return;
    }
    dirent* ent;
    while ((ent = readdir(d))) {
        int pid = atoi(ent->d_name);
        if (pid <= 0) continue;
        std::string base = "/proc/" + std::to_string(pid);
        std::ifstream cmd(base + "/cmdline", std::ios::binary);
        std::string name;
        std::getline(cmd, name, '\0');
        if (name.empty()) continue;
        std::ifstream status(base + "/status");
        std::string line;
        int uid = -1;
        long rss = 0;
        while (std::getline(status, line)) {
            if (line.rfind("Uid:", 0) == 0) uid = atoi(line.c_str() + 4);
            if (line.rfind("VmRSS:", 0) == 0) rss = atol(line.c_str() + 6);
        }
        if (uid < 0 || (!include_system && uid < 10000)) continue;
        std::ifstream stat(base + "/stat");
        std::getline(stat, line);
        uint64_t started = 0;
        size_t end = line.rfind(')');
        if (end != std::string::npos) {
            std::istringstream ss(line.substr(end + 1));
            std::string word;
            for (int field = 3; field <= 22 && ss >> word; field++)
                if (field == 22) started = strtoull(word.c_str(), nullptr, 10);
        }
        printf("{\"pid\":%d,\"uid\":%d,\"rss\":%ld,\"started\":%llu,\"name\":\"%s\"}\n", pid, uid,
               rss, (unsigned long long)started, esc(name).c_str());
    }
    closedir(d);
    fflush(stdout);
}
static int selftest() {  // 为什么：无需 Root 即可验证畸形头、IPv6 扩展、分片和解析偏移的界限。
    uint8_t udp[32] = {};
    udp[0] = 0x45;
    udp[3] = 32;
    udp[9] = 17;
    udp[20] = 1;
    udp[22] = 2;
    udp[25] = 12;
    View v = parse(udp, sizeof(udp));
    if (!v.valid || v.payload != 28 || v.length != 4 || v.sp != 256) return 10;
    udp[6] = 0x20;
    v = parse(udp, sizeof(udp));
    if (!v.fragmented || v.sp != 0) return 11;
    uint8_t ipv6[56] = {};
    ipv6[0] = 0x60;
    ipv6[5] = 16;
    ipv6[6] = 0;
    ipv6[40] = 17;
    ipv6[53] = 8;
    v = parse(ipv6, sizeof(ipv6));
    if (!v.valid || v.proto != 17 || v.payload != 56 || v.length != 0) return 12;
    uint32_t seed = 12345;
    for (int n = 0; n < 256; n++)
        for (int repeat = 0; repeat < 256; repeat++) {
            std::vector<uint8_t> b(n);
            for (auto& x : b) {
                seed = seed * 1664525u + 1013904223u;
                x = seed >> 24;
            }
            v = parse(b.data(), b.size());
            if (v.valid && (v.payload < 0 || v.length < 0 || size_t(v.payload) > b.size() ||
                            size_t(v.length) > b.size() - v.payload))
                return 13;
        }
    event("selftest", "通过：IPv4/IPv6/分片/扩展头及 65536 组长度边界输入");
    return 0;
}
static int writer_test(
    const std::string& dir) {  // 为什么：在真实 Android 文件系统验证跨分段写盘和固定索引。
    std::thread disk([&] {
        try {
            writer(dir, getuid());
        } catch (const std::exception& e) {
            fail(e.what());
        }
    });
    try {
        for (int i = 0; i < 1200 && running; i++) {
            while (running) {
                bool full;
                {
                    std::lock_guard<std::mutex> l(queue_mu);
                    full = queue_bytes > 12u * 1024u * 1024u;
                }
                if (!full) break;
                usleep(1000);
            }
            Packet p;
            p.bytes.resize(60000);
            p.bytes[0] = 0x45;
            le(p.bytes.data() + 2, 0, 2);
            p.bytes[2] = 60000 >> 8;
            p.bytes[3] = 60000 & 255;
            p.bytes[8] = 64;
            p.bytes[9] = 17;
            p.bytes[12] = 10;
            p.bytes[15] = 1;
            p.bytes[16] = 10;
            p.bytes[19] = 2;
            p.bytes[20] = 1;
            p.bytes[22] = 2;
            const int udp = 60000 - 20;
            p.bytes[24] = udp >> 8;
            p.bytes[25] = udp & 255;
            le(p.bytes.data() + 28, i + 1, 8);
            unsigned sum = 0;
            for (int k = 0; k < 20; k += 2) sum += be16(p.bytes.data() + k);
            while (sum >> 16) sum = (sum & 65535) + (sum >> 16);
            p.bytes[10] = uint8_t((~sum) >> 8);
            p.bytes[11] = uint8_t(~sum);
            p.view = parse(p.bytes.data(), p.bytes.size());
            p.sec = 1700000000 + i;
            p.wire = p.bytes.size();
            p.outgoing = i % 2 == 0;
            put(std::move(p));
            if (i % 64 == 63) usleep(10000);  // 仅离线写盘测试：让读取检查能覆盖正在增长的索引。
        }
    } catch (const std::exception& e) {
        fail(e.what());
    }
    {
        std::lock_guard<std::mutex> l(queue_mu);
        producer_done = true;
    }
    queue_cv.notify_all();
    disk.join();
    if (!failure().empty() || counts.written != 1200 || counts.queue != 0) {
        event("error", failure().empty() ? "写盘数量不一致" : failure());
        return 20;
    }
    event("writer-test", "通过：1200 个 60000 字节原包，跨越 64 MiB 分段，完整索引与停止排空");
    return 0;
}
static int reliability_test(const std::string& dir) {
    std::string unknown = dir + "/unattributed";
    if (mkdir(unknown.c_str(), 0700) != 0) return 30;
    retain_unknown = true;
    Sockets table;
    Packet sample;
    sample.view.proto = 17;
    sample.view.src = "10.0.0.1";
    sample.view.dst = "10.0.0.2";
    sample.view.sp = 1234;
    sample.view.dp = 4321;
    sample.outgoing = true;
    table[key(17, "10.0.0.1", 1234, "10.0.0.2", 4321)] = 12345;
    if (owner_of(sample, table) != 12345 || sample.evidence != "exact-socket-tuple") return 31;
    table.clear();
    table[key(17, "*", 1234, "*", 0)] = 12345;
    if (owner_of(sample, table) != 12345 || sample.evidence != "wildcard-socket-tuple") return 32;
    table[key(17, "*", 1234, "*", 0)] = -2;
    if (owner_of(sample, table) != -2 || sample.evidence != "ambiguous-uid") return 33;
    std::thread disk([&] { writer(dir, getuid()); });
    for (int i = 0; i < 20; i++) {
        Packet p;
        p.bytes.resize(32);
        p.bytes[0] = 0x45;
        p.bytes[3] = 32;
        p.bytes[9] = 17;
        p.bytes[12] = 10;
        p.bytes[15] = 1;
        p.bytes[16] = 10;
        p.bytes[19] = 2;
        p.bytes[20] = 4;
        p.bytes[21] = 0xd2;
        p.bytes[22] = 0x10;
        p.bytes[23] = 0xe1;
        p.bytes[25] = 12;
        p.bytes[28] = uint8_t(i);
        p.view = parse(p.bytes.data(), p.bytes.size());
        p.wire = 32;
        p.sec = 1700000000 + i;
        p.queued = millis();
        p.decided = p.queued + 100;
        p.outgoing = i % 2 == 0;
        p.unknown = i >= 10;
        p.uid = p.unknown ? -1 : 12345;
        p.evidence = p.unknown ? "socket-not-found" : "exact-socket-tuple";
        p.reason = p.unknown ? "timeout" : "";
        put(std::move(p));
    }
    {
        std::lock_guard<std::mutex> l(queue_mu);
        producer_done = true;
    }
    queue_cv.notify_all();
    disk.join();
    stats(true);
    if (!failure().empty() || counts.written != 10 || counts.unknown_written != 10) return 34;
    event("reliability-test", "通过：精确/通配/歧义归属依据，10 主会话包与 10 未知包独立写盘");
    return 0;
}
int main(int argc, char** argv) {
    std::signal(SIGTERM, signal_stop);
    std::signal(SIGINT, signal_stop);
    std::signal(SIGPIPE, SIG_IGN);
    setvbuf(stdout, nullptr, _IOLBF, 0);
    if (argc == 2 && strcmp(argv[1], "selftest") == 0) return selftest();
    if (argc == 3 && strcmp(argv[1], "writer-test") == 0) {
        struct stat sb{};
        if (stat(argv[2], &sb) != 0 || !S_ISDIR(sb.st_mode) || sb.st_uid != getuid()) return 21;
        return writer_test(argv[2]);
    }
    if (argc == 3 && strcmp(argv[1], "reliability-test") == 0) {
        struct stat sb{};
        if (stat(argv[2], &sb) != 0 || !S_ISDIR(sb.st_mode) || sb.st_uid != getuid()) return 35;
        return reliability_test(argv[2]);
    }
    if (getuid() != 0) {
        event("error", "尚未获得 Root 权限，请在 Root 管理器中授权");
        return 1;
    }
    if (argc == 2 && strcmp(argv[1], "processes-stream") == 0) {
        // 只读常驻查询通道：首次 su 授权后复用，退出/输入关闭即结束，不另起守护进程。
        event("catalog-ready", "ready");
        std::string request;
        while (running && std::getline(std::cin, request)) {
            if (request == "quit") break;
            if (request != "list") continue;
            processes(false);
            event("catalog-end", "done");
        }
        return 0;
    }
    if (argc == 2 && (strcmp(argv[1], "processes") == 0 || strcmp(argv[1], "processes-all") == 0)) {
        processes(strcmp(argv[1], "processes-all") == 0);
        return 0;
    }
    if (argc == 2 && strcmp(argv[1], "probe") == 0) {
        int fd = socket(AF_PACKET, SOCK_DGRAM | SOCK_CLOEXEC, htons(ETH_P_ALL));
        if (fd < 0) {
            event("error", std::string("已有 Root，但抓包 socket 不可用: ") + strerror(errno));
            return 2;
        }
        close(fd);
        event("ready", "Root 核心及 AF_PACKET 抓包 socket 可用");
        return 0;
    }
    if ((argc == 7 || argc == 8) && strcmp(argv[1], "capture") == 0) {
        char* end = nullptr;
        long owner = strtol(argv[3], &end, 10);
        struct stat sb{};
        if (!end || *end || owner < 10000 || stat(argv[2], &sb) != 0 || !S_ISDIR(sb.st_mode)) {
            event("error", "会话目录或应用 UID 校验失败");
            return 1;
        }
        return capture(argv[2], owner, atoi(argv[4]), atoi(argv[5]), atoi(argv[6]),
                       argc == 8 && strcmp(argv[7], "1") == 0);
    }
    event("error", "不支持的核心命令");
    return 1;
}
