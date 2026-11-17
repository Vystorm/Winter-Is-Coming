#!/usr/bin/env python3
"""Lay out real game screenshots on a labeled Cairo canvas, preserving every image pixel.
No generative editing, recoloring, cropping, or synthetic terrain is used.
Optional web previews are uniformly scaled copies; full-resolution regions remain pixel-exact.
"""
import argparse,hashlib,json,shutil
from pathlib import Path
import cairo
from PIL import Image
p=argparse.ArgumentParser();p.add_argument('report',type=Path);p.add_argument('output',type=Path);p.add_argument('--natural',action='store_true');p.add_argument('--version',default='1.0.1');p.add_argument('--web',action='store_true',help='Also export a 50-percent web preview; full-resolution proof remains unchanged');a=p.parse_args()
r=json.loads(a.report.read_text());assert r['passed'], 'Only completed client runs may be presented'
a.output.mkdir(parents=True,exist_ok=True);captures={};evidence=[]
for item in r['captures']:
 source=Path(item['screenshot']);target=a.output/('minecraft-'+item['stage']+'.png');shutil.copy2(source,target)
 assert hashlib.sha256(source.read_bytes()).digest()==hashlib.sha256(target.read_bytes()).digest()
 captures[item['stage']]=target;evidence.append({'stage':item['stage'],'file':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'camera':{k:v for k,v in item.items() if k not in ['stage','screenshot']}})
def label(ctx,x,y,text,size=28,color=(.9,.95,1),bold=False):
 ctx.set_source_rgb(*color);ctx.select_font_face('DejaVu Sans',cairo.FONT_SLANT_NORMAL,cairo.FONT_WEIGHT_BOLD if bold else cairo.FONT_WEIGHT_NORMAL);ctx.set_font_size(size);ctx.move_to(x,y);ctx.show_text(text)
for left,right,name,title,labell,labelr in [
 ('before','peak','minecraft-before-winter-comparison','WINTER IS COMING  /  BEFORE & CHRISTMAS','BEFORE WINTER  /  01 NOV 2026','CHRISTMAS PEAK  /  24 DEC 2026'),
 ('peak','after','minecraft-thaw-comparison','WINTER IS COMING  /  WINTER & THAW','CHRISTMAS PEAK  /  24 DEC 2026','AFTER THAW  /  02 MAR 2027')]:
 first=cairo.ImageSurface.create_from_png(str(captures[left]));second=cairo.ImageSurface.create_from_png(str(captures[right]));w,h=first.get_width(),first.get_height();assert (w,h)==(second.get_width(),second.get_height())
 canvas=cairo.ImageSurface(cairo.FORMAT_RGB24,w*2+120,h+250);ctx=cairo.Context(canvas);ctx.set_source_rgb(.035,.07,.105);ctx.paint()
 label(ctx,40,63,title,42,bold=True);label(ctx,40,122,labell,29,(.45,.87,.9),True);label(ctx,w+80,122,labelr,29,(.45,.87,.9),True)
 for img,x in [(first,40),(second,w+80)]:ctx.set_source_surface(img,x,150);ctx.paint()
 label(ctx,40,h+203,f'Actual Minecraft 26.2 client captures | Winter Is Coming {a.version} | Same camera, FOV, noon and clear weather',27)
 label(ctx,40,h+237,('Normal world, observed 96x96 area | Accelerated dates | Frost radii: ocean 40 / inland 12 | Vanilla textures, no shaders | Full-resolution originals included.' if a.natural else 'Controlled test fixture: ocean radius 8 (default 40). Vanilla textures, no shaders. Original screenshot pixels preserved in full-resolution version.'),23,(.63,.73,.8))
 out=a.output/(name+'.png');canvas.write_to_png(str(out))
 result=Image.open(out).convert('RGB')
 for stage,x in [(left,40),(right,w+80)]:
  original=Image.open(captures[stage]).convert('RGB');assert result.crop((x,150,x+w,150+h)).tobytes()==original.tobytes(), 'Screenshot pixels were altered'
 evidence.append({'file':out.name,'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'sourceStages':[left,right],'originalImagePixelsPreserved':True})
 if a.web:
  preview=cairo.ImageSurface(cairo.FORMAT_RGB24,canvas.get_width()//2,canvas.get_height()//2);webctx=cairo.Context(preview);webctx.scale(.5,.5);webctx.set_source_surface(canvas,0,0);webctx.get_source().set_filter(cairo.FILTER_BEST);webctx.paint()
  webpath=a.output/(name+'-web.png');preview.write_to_png(str(webpath))
  assert webpath.stat().st_size<5*1024*1024, 'Web preview exceeds the site image limit'
  evidence.append({'file':webpath.name,'sha256':hashlib.sha256(webpath.read_bytes()).hexdigest(),'source':out.name,'operation':'Uniform 50% scaling for web; no content edits','width':preview.get_width(),'height':preview.get_height()})
(a.output/'native-capture-manifest.json').write_text(json.dumps({'method':'Real Fabric client game test captures; original pixels copied without alteration into comparison canvases.','captures':evidence},indent=2)+'\n')
print(json.dumps(evidence,indent=2))
