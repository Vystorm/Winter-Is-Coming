#!/usr/bin/env python3
"""Run stacked-snow checks over two starts of a fresh isolated server."""
import argparse,hashlib,json,shutil,subprocess
from pathlib import Path
p=argparse.ArgumentParser()
for key in ('base','output','java','winter','core','harness'):p.add_argument('--'+key,type=Path,required=True)
p.add_argument('--port',type=int,required=True)
a=p.parse_args();root=a.output.resolve();root.mkdir(exist_ok=False,parents=True);(root/'plugins').mkdir()
for name in ('server.jar','eula.txt'):shutil.copy2(a.base/name,root/name)
for name in ('cache','libraries','versions'):
 if (a.base/name).exists():shutil.copytree(a.base/name,root/name)
for jar in (a.winter,a.core,a.harness):shutil.copy2(jar,root/'plugins'/jar.name)
updates={'server-ip':'127.0.0.1','server-port':str(a.port),'enable-rcon':'false','enable-query':'false','online-mode':'false','pause-when-empty-seconds':'-1','view-distance':'2','simulation-distance':'2'}
props=[line for line in (a.base/'server.properties').read_text().splitlines() if line.partition('=')[0] not in updates]
(root/'server.properties').write_text('\n'.join(props+[k+'='+v for k,v in updates.items()])+'\n')
(root/'inputs.json').write_text(json.dumps({str(f.resolve()):hashlib.sha256(f.read_bytes()).hexdigest() for f in (a.winter,a.core,a.harness,a.base/'server.jar')},indent=2)+'\n')
for phase in (1,2):
 with (root/f'phase-{phase}.log').open('w') as log:
  proc=subprocess.Popen([str(a.java),'-Xms512M','-Xmx2G','-XX:ActiveProcessorCount=4','-Dterminal.jline=false','-Dterminal.ansi=false','-Dwinter.stack.acceptance=true','-jar','server.jar','--nogui'],cwd=root,stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True)
  try:proc.wait(timeout=600)
  except subprocess.TimeoutExpired:
   proc.stdin.write('stop\n');proc.stdin.flush()
   try:proc.wait(timeout=40)
   except subprocess.TimeoutExpired:proc.kill();proc.wait()
 result=root/'plugins/WinterStackAcceptance'/f'result-stage{phase}.json'
 assert result.exists(),f'No successful result for phase {phase}; inspect phase log and result.json'
 report=json.loads(result.read_text());assert report['passed'],report
 print(json.dumps({'phase':phase,'passed':report['passed'],'checks':report['checks'],'counts':report['counts']}),flush=True)
