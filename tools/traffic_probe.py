#!/usr/bin/env python3
"""NetCap numbered test traffic. Standard library only; run against your own endpoint."""
import argparse, concurrent.futures, json, socket, struct, threading, time, uuid
MAGIC=b"NCAPTEST"
def payload(run,kind,seq,size):
    return struct.pack("!8s16sBI",MAGIC,run,kind,seq).ljust(max(size,29),b"X")
def parse(data):
    if len(data)<29 or data[:8]!=MAGIC:return None
    _,run,kind,seq=struct.unpack("!8s16sBI",data[:29]);return run.hex(),kind,seq

def exact(sock,n):
    data=bytearray()
    while len(data)<n:
        part=sock.recv(n-len(data))
        if not part:raise EOFError()
        data.extend(part)
    return bytes(data)

def serve(args):
    family=socket.AF_INET6 if ":" in args.host else socket.AF_INET
    udp=socket.socket(family,socket.SOCK_DGRAM);tcp=socket.socket(family,socket.SOCK_STREAM)
    tcp.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
    udp.bind((args.host,args.port));port=udp.getsockname()[1];tcp.bind((args.host,port));tcp.listen(16)
    udp.settimeout(.2);tcp.settimeout(.2);end=time.monotonic()+args.seconds
    lock=threading.Lock();seen={};errors=[];pool=concurrent.futures.ThreadPoolExecutor(max_workers=16)
    def note(data,transport):
        item=parse(data)
        if item:
            run,kind,seq=item
            with lock:seen.setdefault((run,transport,kind),set()).add(seq)
        return item
    def connection(conn):
        with conn:
            conn.settimeout(2)
            while time.monotonic()<end:
                try:
                    n=struct.unpack("!I",exact(conn,4))[0]
                    if not 29<=n<=60000:return
                    data=exact(conn,n);item=note(data,"tcp")
                    if item:
                        ack=bytearray(data);ack[24]|=128;conn.sendall(struct.pack("!I",len(ack))+ack)
                except (OSError,EOFError):return
    def accept():
        while time.monotonic()<end:
            try:conn,_=tcp.accept();pool.submit(connection,conn)
            except socket.timeout:continue
            except OSError:break
    acceptor=threading.Thread(target=accept);acceptor.start()
    print(json.dumps({"ready":True,"host":args.host,"port":port}),flush=True)
    try:
        while time.monotonic()<end:
            try:
                data,peer=udp.recvfrom(65535);item=note(data,"udp")
                if item:
                    ack=bytearray(data);ack[24]|=128;udp.sendto(ack,peer)
            except socket.timeout:pass
            except OSError as e:errors.append(str(e))
    finally:
        udp.close();tcp.close();acceptor.join();pool.shutdown(wait=True)
    report={"role":"server","streams":[{"run":r,"transport":t,"kind":k,"uniqueReceived":len(v),"first":min(v),"last":max(v)}
        for (r,t,k),v in seen.items()],"errors":errors}
    output(args.output,report)

def output(path,data):
    text=json.dumps(data,ensure_ascii=False,indent=2);print(text,flush=True)
    if path:
        with open(path,"w",encoding="utf-8")as f:f.write(text+"\n")

def client(args):
    family=socket.AF_INET6 if ":" in args.host else socket.AF_INET
    run=uuid.uuid4().bytes;report={"role":"client","run":run.hex(),"host":args.host,"port":args.port,"streams":[],"errors":[]}
    def udp_test(count,short):
        kind=2 if short else 1;sent=0;acks=set();sock=None
        try:
            if not short:sock=socket.socket(family,socket.SOCK_DGRAM);sock.settimeout(.15)
            start=time.monotonic()
            for seq in range(count):
                if short:sock=socket.socket(family,socket.SOCK_DGRAM);sock.settimeout(.15)
                try:
                    sock.sendto(payload(run,kind,seq,args.size),(args.host,args.port));sent+=1
                    if short:
                        data,_=sock.recvfrom(65535);p=parse(data)
                        if p and p[0]==run.hex() and p[1]==kind|128:acks.add(p[2])
                    else:
                        sock.setblocking(False)
                        while True:
                            try:
                                data,_=sock.recvfrom(65535);p=parse(data)
                                if p and p[0]==run.hex() and p[1]==kind|128:acks.add(p[2])
                            except BlockingIOError:break
                        sock.settimeout(.15)
                except OSError as e:
                    if len(report["errors"])<20:report["errors"].append(str(e))
                finally:
                    if short:sock.close()
                if args.rate>0:
                    delay=start+(seq+1)/args.rate-time.monotonic()
                    if delay>0:time.sleep(delay)
            if not short:
                end=time.monotonic()+2;sock.settimeout(.15)
                while time.monotonic()<end:
                    try:
                        data,_=sock.recvfrom(65535);p=parse(data)
                        if p and p[0]==run.hex() and p[1]==kind|128:acks.add(p[2])
                    except socket.timeout:pass
        finally:
            if sock:sock.close()
        report["streams"].append({"transport":"udp","kind":kind,"attempted":count,"sent":sent,"uniqueEchoes":len(acks)})
    def tcp_test(count,short):
        kind=4 if short else 3;sent=acked=0;sock=None
        try:
            for seq in range(count):
                try:
                    if sock is None:sock=socket.socket(family,socket.SOCK_STREAM);sock.settimeout(2);sock.connect((args.host,args.port))
                    data=payload(run,kind,seq,args.size);sock.sendall(struct.pack("!I",len(data))+data);sent+=1
                    n=struct.unpack("!I",exact(sock,4))[0]
                    if n>60000:raise ValueError("invalid echo length")
                    p=parse(exact(sock,n))
                    if p==(run.hex(),kind|128,seq):acked+=1
                except (OSError,EOFError,ValueError)as e:
                    if len(report["errors"])<20:report["errors"].append(str(e))
                    if sock:sock.close();sock=None
                if short and sock:sock.close();sock=None
        finally:
            if sock:sock.close()
        report["streams"].append({"transport":"tcp","kind":kind,"attempted":count,"sent":sent,"echoes":acked})
    udp_test(args.udp,False);udp_test(args.short_udp,True);tcp_test(args.tcp,False);tcp_test(args.short_tcp,True)
    output(args.output,report)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--serve",action="store_true")
    p.add_argument("--host",default="127.0.0.1");p.add_argument("--port",type=int,default=37991);p.add_argument("--seconds",type=float,default=120)
    p.add_argument("--udp",type=int,default=1000);p.add_argument("--short-udp",type=int,default=30)
    p.add_argument("--tcp",type=int,default=100);p.add_argument("--short-tcp",type=int,default=30)
    p.add_argument("--rate",type=float,default=200);p.add_argument("--size",type=int,default=256);p.add_argument("--output")
    a=p.parse_args()
    if not 29<=a.size<=60000 or min(a.udp,a.short_udp,a.tcp,a.short_tcp)<0:p.error("invalid size/count")
    serve(a)if a.serve else client(a)
if __name__=="__main__":main()
