#!/usr/bin/env python3
"""Run only in a new isolated directory, using an existing accepted-EULA server cache.
No live server/world is modified. Does not download or accept an EULA.
"""
import argparse,hashlib,json,shutil,subprocess,tempfile
from pathlib import Path
p=argparse.ArgumentParser()
p.add_argument('--base',type=Path,required=True)
p.add_argument('--output',type=Path,required=True)
p.add_argument('--java',type=Path,required=True)
p.add_argument('--winter',type=Path,required=True)
p.add_argument('--core',type=Path,required=True)
p.add_argument('--harness',type=Path,required=True)
p.add_argument('--packet',action='store_true',help='Use the native packet/weather capture harness')
p.add_argument('--capture',action='store_true',help='Pause each phase until the real client has saved its screenshot')
p.add_argument('--natural',action='store_true',help='Generate a normal world (seed 20261024), requires naturalVisualJar and --capture')
p.add_argument('--tick-rate',type=int,default=80,help='Accelerated natural-world test tick rate, 20..1000')
p.add_argument('--ram-journal',action='store_true',help='Visual-only tmpfs journal; never a durability test; archived on shutdown')
a=p.parse_args()
if not 20<=a.tick_rate<=1000:p.error('--tick-rate must be 20..1000')
if a.natural and not a.capture: p.error('--natural requires --capture and the naturalVisualJar harness')
root=a.output.resolve();root.mkdir(exist_ok=False,parents=True);(root/'plugins').mkdir()
for name in ['server.jar','eula.txt']:shutil.copy2(a.base/name,root/name)
for name in ['cache','libraries','versions']:
 if (a.base/name).exists():shutil.copytree(a.base/name,root/name)
for jar in [a.winter,a.core,a.harness]:shutil.copy2(jar,root/'plugins'/jar.name)
props=(a.base/'server.properties').read_text().splitlines()
updates={'server-ip':'127.0.0.1','server-port':'25716','enable-rcon':'false','enable-query':'false','online-mode':'false','pause-when-empty-seconds':'-1','view-distance':('8' if a.capture else '2'),'simulation-distance':'2'}
if a.natural: updates.update({'level-type':'minecraft:normal','generator-settings':'{}','level-seed':'20261024'})
props=[s for s in props if s.partition('=')[0] not in updates]
(root/'server.properties').write_text('\n'.join(props+[k+'='+v for k,v in updates.items()])+'\n')
manifest={'inputs':{str(jar.resolve()):hashlib.sha256(jar.read_bytes()).hexdigest() for jar in [a.winter,a.core,a.harness,a.base/'server.jar']},'method':('Normally generated Minecraft world; accelerated dates and terrain cycles; native client capture handshake.' if a.natural else 'Actual Purpur block snapshots; accelerated fixture; not Minecraft screenshots.')}
manifest['tickRate']=a.tick_rate if a.natural else None
manifest['journalStorage']='temporary tmpfs; visual test only, not power-loss durability' if a.ram_journal else 'normal filesystem'
(root/'inputs.json').write_text(json.dumps(manifest,indent=2)+'\n')
ram_journal=None
if a.ram_journal:
 ram_journal=Path(tempfile.mkdtemp(prefix='winter-visual-',dir='/dev/shm'))/'blocks.jsonl'
 ram_journal.touch();journal_link=root/'plugins/WinterIsComing/blocks.jsonl';journal_link.parent.mkdir(exist_ok=True);journal_link.symlink_to(ram_journal)
with (root/'visual.log').open('w') as log:
 proc=subprocess.Popen([str(a.java),'-Xms512M','-Xmx2G','-XX:ActiveProcessorCount=4','-Dterminal.jline=false','-Dterminal.ansi=false','-Dwinter.visual=true',*(['-Dwinter.visual.natural=true','-Dwinter.visual.tickRate='+str(a.tick_rate)] if a.natural else []),*(['-Dwinter.visual.capture=true'] if a.capture else []),'-jar','server.jar','--nogui'],cwd=root,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
 try:proc.wait(timeout=7200 if a.natural else 2400 if a.capture else 1500)
 except subprocess.TimeoutExpired:
  proc.stdin.write('stop\n');proc.stdin.flush()
  try:proc.wait(timeout=40)
  except subprocess.TimeoutExpired:proc.kill();proc.wait()
if ram_journal is not None:
 journal_link.unlink();shutil.copy2(ram_journal,journal_link);ram_journal.unlink();ram_journal.parent.rmdir()
result=root/('plugins/WinterPacketVisual/snapshots/result.json' if a.packet else 'plugins/WinterVisual/result.json')
assert result.exists(), 'No visual test result; see visual.log'
print(result.read_text())
assert json.loads(result.read_text())['passed'], 'Visual fixture failed'
