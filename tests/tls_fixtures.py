"""Generate own TLS 1.2/1.3 handshakes in memory; no socket, real traffic or bundled keys.
Requires Python cryptography (only to create an ephemeral self-signed test certificate).
"""
from pathlib import Path
import datetime as dt
import ipaddress
import json
import ssl
import struct
import sys
from cryptography import x509
from cryptography.x509.oid import NameOID
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import rsa

out = Path(sys.argv[1]).resolve()
out.mkdir(parents=True, exist_ok=True)
key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, "test.netcap.invalid")])
now = dt.datetime.now(dt.timezone.utc)
cert = (x509.CertificateBuilder().subject_name(name).issuer_name(name).public_key(key.public_key())
        .serial_number(x509.random_serial_number()).not_valid_before(now-dt.timedelta(days=1))
        .not_valid_after(now+dt.timedelta(days=1))
        .add_extension(x509.SubjectAlternativeName([x509.DNSName("test.netcap.invalid")]), False)
        .sign(key, hashes.SHA256()))
(out/"ephemeral-cert.pem").write_bytes(cert.public_bytes(serialization.Encoding.PEM))
(out/"ephemeral-key.pem").write_bytes(key.private_bytes(serialization.Encoding.PEM,
        serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

def tcp(seq, side, flags, data, ipv6):
    src, dst = ("2001:db8::1", "2001:db8::2") if ipv6 else ("192.0.2.1", "198.51.100.2")
    sp, dp = 12345, 8443
    if side:
        src, dst, sp, dp = dst, src, dp, sp
    header = struct.pack("!HHIIBBHHH", sp, dp, seq, 0, 0x50, flags, 65535, 0, 0)
    if ipv6:
        ip = struct.pack("!IHBB", 6<<28, 20+len(data), 6, 64)+ipaddress.ip_address(src).packed+ipaddress.ip_address(dst).packed
    else:
        ip = struct.pack("!BBHHHBBH", 0x45, 0, 40+len(data), 0, 0, 64, 6, 0)+ipaddress.ip_address(src).packed+ipaddress.ip_address(dst).packed
    return ip+header+data

metadata = {}
for label, version, ipv6 in [("tls12", ssl.TLSVersion.TLSv1_2, False), ("tls13", ssl.TLSVersion.TLSv1_3, True)]:
    server = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    server.minimum_version = server.maximum_version = version
    server.load_cert_chain(out/"ephemeral-cert.pem", out/"ephemeral-key.pem")
    server.set_alpn_protocols(["h2", "http/1.1"])
    client = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    # Only this synthetic test uses a self-signed server. No app trust settings are changed.
    client.check_hostname = False
    client.verify_mode = ssl.CERT_NONE
    client.minimum_version = client.maximum_version = version
    client.set_alpn_protocols(["h2", "http/1.1"])
    ci, co, si, so = ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO(), ssl.MemoryBIO()
    c = client.wrap_bio(ci, co, server_side=False, server_hostname="test.netcap.invalid")
    s = server.wrap_bio(si, so, server_side=True)
    records=[]; done=[False,False]
    for _ in range(30):
        for side, peer, target, output in [(0,c,si,co),(1,s,ci,so)]:
            try:
                peer.do_handshake()
                done[side] = True
            except ssl.SSLWantReadError:
                pass
            data = output.read()
            if data:
                records.append((side,data)); target.write(data)
        if all(done): break
    assert all(done)
    c.write(b"NETCAP_SYNTHETIC_APPLICATION_DATA")
    data=co.read(); records.append((0,data)); si.write(data)
    assert s.read() == b"NETCAP_SYNTHETIC_APPLICATION_DATA"
    packets=[tcp(100,0,2,b"",ipv6),tcp(900,1,18,b"",ipv6)]
    seq=[101,901]
    for side,data in records:
        # Deliberately split TLS record/header boundaries into TCP segments, reorder and duplicate.
        chunks=[]; pos=0
        for n in [1,2,7,19,53,127,521,len(data)]:
            part=data[pos:pos+n]
            if not part: continue
            chunks.append(tcp(seq[side],side,24,part,ipv6)); seq[side]+=len(part); pos+=len(part)
        if len(chunks)>3: chunks[1],chunks[2]=chunks[2],chunks[1]
        packets.extend(chunks)
        packets.append(chunks[-1])
    raw=bytearray(struct.pack("<IHHIIII",0xa1b2c3d4,2,4,0,0,262144,101))
    for i,p in enumerate(packets):
        raw+=struct.pack("<IIII",1700000000,i*1000,len(p),len(p))+p
    (out/(label+".pcap")).write_bytes(raw)
    metadata[label]={"version":c.version(),"cipher":c.cipher()[0],"alpn":c.selected_alpn_protocol(),"packets":len(packets),"ipv6":ipv6}
(out/"expected.json").write_text(json.dumps(metadata,indent=2),encoding="utf-8")
print("Generated real MemoryBIO TLS fixtures; no network: "+json.dumps(metadata))
