#!/usr/bin/env python3
"""Compare endpoint counters with NetCap's UDP sequence evidence (not raw packet totals)."""
import argparse,json
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--client',required=True);p.add_argument('--server');p.add_argument('--integrity',required=True,nargs='+');a=p.parse_args()
def load(path):
    with open(path,encoding='utf-8')as f:return json.load(f)
client=load(a.client);server=load(a.server)if a.server else None
reports=[load(path)for path in a.integrity];run=client['run'];rows=[]
for flow in client['streams']:
    kind=flow['kind']
    if flow['transport']!='udp':continue
    received=next((x['uniqueReceived']for x in (server or {}).get('streams',[])if x['run']==run and x['kind']==kind),None)
    def observed(k):
        values=[item for report in reports for item in report.get('testStreams',[])if item['stream']==f'{run} /type{k}']
        # Reports may overlap: summing distinct counts would claim false coverage. Keep per-file evidence.
        return [{'unique':x['uniqueSequences'],'first':x['first'],'last':x['last'],'internalMissing':x['internalMissing']}for x in values]
    rows.append({'kind':kind,'attempted':flow['attempted'],'sendCallsSucceeded':flow['sent'],
        'serverUniqueReceived':received,'clientUniqueEchoes':flow['uniqueEchoes'],
        'capturedRequestsPerReport':observed(kind),'capturedEchoesPerReport':observed(kind|128)})
print(json.dumps({'run':run,'udp':rows,'scope':'Compare only this numbered run. Multiple reports are not blindly summed. TCP logical frames require endpoint counters plus TCP byte-range evidence; physical network switching and AF_PACKET coverage require a phone run.'},ensure_ascii=False,indent=2))
