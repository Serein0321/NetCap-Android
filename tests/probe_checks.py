import json,subprocess,sys,time,socket,os
os.environ["PYTHONIOENCODING"]="utf-8"
from pathlib import Path
root=Path(__file__).resolve().parents[1];out=root/'build'/'host-tests';out.mkdir(parents=True,exist_ok=True)
for host,label in [('127.0.0.1','ipv4'),('::1','ipv6')]:
    target=out/f'probe-{label}-server.json';client=out/f'probe-{label}-client.json'
    server=subprocess.Popen([sys.executable,str(root/'tools'/'traffic_probe.py'),'--serve','--host',host,'--port','0','--seconds','7','--output',str(target)],stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf-8')
    ready=json.loads(server.stdout.readline());assert ready['ready'],ready
    run=subprocess.run([sys.executable,str(root/'tools'/'traffic_probe.py'),'--host',host,'--port',str(ready['port']),'--udp','200','--short-udp','15','--tcp','40','--short-tcp','15','--rate','1000','--output',str(client)],capture_output=True,text=True,encoding='utf-8',timeout=20)
    assert run.returncode==0,run.stderr
    stdout,stderr=server.communicate(timeout=12);assert server.returncode==0,stderr
    c=json.loads(client.read_text(encoding="utf-8"));s=json.loads(target.read_text(encoding="utf-8"));assert not c['errors'],c
    for stream in c['streams']:
        expected=stream['attempted'];assert stream['sent']==expected,stream
        assert stream.get('uniqueEchoes',stream.get('echoes'))==expected,stream
        received=next(x for x in s['streams']if x['kind']==stream['kind']and x['run']==c['run'])
        assert received['uniqueReceived']==expected,received
    print(f'PASS {label}: UDP 200 + short UDP 15 + TCP 40 + short TCP 15; sent/received/echo counts agree')
print('Host loopback traffic only; Android AF_PACKET, attribution, high load and physical network switching remain device tests.')
