#!/usr/bin/env python3
"""Independent checks of exported blocks, including fair shore coverage across chunks."""
import json,math,argparse,hashlib
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('snapshots',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
files=sorted(a.snapshots.glob('*.json'));assert len(files)==5
snapshots=[json.loads(f.read_text()) for f in files];failures=[];records=[]
def states(s):return {(b['x'],b['y'],b['z']):b['data'] for b in s['blocks']}
for f,s in zip(files,snapshots):
    blocks={(b['x'],b['y'],b['z']):b for b in s['blocks']}
    missing=[];radius=math.floor(8*s['intensity']*.85)
    if s['stage'] in ['early','peak','thaw']:
        for x in range(32):
            coast=11+math.floor(1.2*math.sin(x/5)+.5)
            for distance in range(1,radius+1):
                pos=(x,70,coast+distance-1)
                if blocks[pos]['material']!='ICE':missing.append(pos)
    if missing:failures.append(f"{s['stage']}: {len(missing)} guaranteed near-shore cells remain unfrozen")
    assert all(blocks[(x,70,z)]['material']=='WATER' for x in range(32) for z in range(23,32)), 'Far ocean changed'
    protected=[b for b in s['blocks'] if b['material']=='SNOW' and 25<=b['x']<=28 and 3<=b['z']<=6]
    if protected:failures.append('Protected plot received snow')
    records.append({'snapshot':f.name,'sha256':hashlib.sha256(f.read_bytes()).hexdigest(),'date':s['date'],'intensity':s['intensity'],'summary':s['summary'],'guaranteedShoreCellsMissing':len(missing)})
restored=states(snapshots[0])==states(snapshots[-1])
if not restored:failures.append('Final block states differ from baseline')
if not snapshots[2]['summary']['snowLayers']>snapshots[1]['summary']['snowLayers']:failures.append('No seasonal snow increase')
if not snapshots[2]['summary']['snowLayers']>snapshots[3]['summary']['snowLayers']:failures.append('No seasonal snow decrease')
result={'passed':not failures,'failures':failures,'allFinalBlockStatesRestored':restored,'stages':records}
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2));raise SystemExit(bool(failures))
